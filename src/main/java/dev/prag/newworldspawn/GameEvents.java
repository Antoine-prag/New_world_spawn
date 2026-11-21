package dev.prag.newworldspawn;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evenements du dimension spawn :
 * - bordure 20 chunks au demarrage ;
 * - aventure FORCEE dans la dimension (restauree a la sortie) ;
 * - pas de PvP dedans ;
 * - eau "poids plume" : la nage est quasi impossible dans CETTE dimension uniquement ;
 * - /setspawn all : tous les joueurs qui se connectent arrivent dans la dimension spawn
 *   (jamais au respawn/death : les lits continuent de definir le point de respawn).
 */
@EventBusSubscriber(modid = NewWorldSpawn.MODID)
public final class GameEvents
{
    private GameEvents()
    {
    }

    public static final ResourceKey<net.minecraft.world.level.Level> SPAWN_DIM =
        ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(NewWorldSpawn.MODID, "spawn"));

    /** gamemode sauvegarde avant l'entree dans la dimension (restaure a la sortie). */
    private static final Map<UUID, GameType> PREVIOUS_MODE = new HashMap<>();

    /** dernier message "verrou spawn" envoye au joueur (throttle 5 s). */
    private static final Map<UUID, Long> LAST_DENY_MSG = new HashMap<>();

    /** Y de spawn en (0,0), calcule UNE FOIS puis mis en cache (aucune regeneration a chaque /spawn). */
    private static volatile double cachedSpawnY = -1.0;

    /** File de pre-generation des chunks (remplie au boot, videe ? chunksParTick). */
    private static final java.util.ArrayDeque<Long> PRELOAD_QUEUE = new java.util.ArrayDeque<>();

    public static ServerLevel spawnLevel(final MinecraftServer server)
    {
        return server.getLevel(SPAWN_DIM);
    }

    public static boolean inSpawnDim(final ServerPlayer p)
    {
        return p.level().dimension() == SPAWN_DIM;
    }

    /**
     * Y de pied du joueur en (0,0) — LECTURE SEULE + CACHE :
     * le chunk central n'est genere qu'UNE fois (sinon chaque /spawn regenerait des chunks
     * FULL de maniere synchrone = gros lag), puis mis en cache jusqu'au prochain redemarrage.
     * AUCUNE construction/purge de blocs (le spawn est construit a la main, on n'y touche pas).
     */
    public static double spawnY(final ServerLevel level)
    {
        double y = cachedSpawnY;
        if (y < 0.0)
        {
            level.getChunk(0, 0, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 1.0;
            cachedSpawnY = y;
        }
        return y;
    }

    // ------------------------------------------------------------------ demarrage : bordure

    @SubscribeEvent
    public static void onServerStarted(final net.neoforged.neoforge.event.server.ServerStartedEvent event)
    {
        final ServerLevel level = spawnLevel(event.getServer());
        if (level == null)
        {
            NewWorldSpawn.LOG.error("Dimension newworldspawn:spawn introuvable (data JSON manquant ?) — bordure non posee.");
            return;
        }
        final int radiusBlocks = SpawnConfig.BORDER_RADIUS_CHUNKS.get() * 16;
        final var border = level.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(radiusBlocks * 2.0);
        border.setWarningBlocks(16);
        border.setWarningTime(5);
        NewWorldSpawn.LOG.info("Bordure posee : centre (0,0), rayon {} blocs ({} chunks).",
            radiusBlocks, SpawnConfig.BORDER_RADIUS_CHUNKS.get());

        // Pre-generation + CHUNK-MAINTENANCE (force load) : la zone centrale de la dimension
        // reste chargee en permanence -> /spawn et les logins ne regenerent plus rien
        // (plus de lag ni de chute dans des chunks vides). On etale la generation sur
        // plusieurs ticks (preloadChunksPerTick) pour ne pas bloquer le boot.
        loadSpawnArea(event.getServer(), level);
    }

    private static void loadSpawnArea(final MinecraftServer server, final ServerLevel level)
    {
        final int r = SpawnConfig.PRELOAD_RADIUS_CHUNKS.get();
        if (r <= 0)
        {
            NewWorldSpawn.LOG.info("Pre-generation desactivee (preloadRadiusChunks = 0).");
            return;
        }
        PRELOAD_QUEUE.clear();
        // tri par distance croissante : le centre (destination des TP) est pret le premier
        final java.util.List<long[]> coords = new java.util.ArrayList<>();
        for (int cx = -r; cx <= r; cx++)
        {
            for (int cz = -r; cz <= r; cz++)
            {
                coords.add(new long[] { cx, cz, (long) cx * cx + (long) cz * cz });
            }
        }
        coords.sort(java.util.Comparator.comparingLong(a -> a[2]));
        for (final long[] c : coords)
        {
            level.setChunkForced((int) c[0], (int) c[1], true); // survit aux restarts (level.dat)
            PRELOAD_QUEUE.add(net.minecraft.world.level.ChunkPos.asLong((int) c[0], (int) c[1]));
        }
        NewWorldSpawn.LOG.info("Pre-generation planifiee : {} chunks ({}/{}/tick) + force-load autour de (0,0).",
            PRELOAD_QUEUE.size(), SpawnConfig.PRELOAD_RADIUS_CHUNKS.get(), SpawnConfig.PRELOAD_CHUNKS_PER_TICK.get());
    }

    /** Vide la file de pre-generation, ? chunks par tick (jamais synchrone d'un coup). */
    private static void tickPreload(final MinecraftServer server, final ServerLevel level)
    {
        if (PRELOAD_QUEUE.isEmpty())
        {
            return;
        }
        final int perTick = Math.max(1, SpawnConfig.PRELOAD_CHUNKS_PER_TICK.get());
        for (int i = 0; i < perTick && !PRELOAD_QUEUE.isEmpty(); i++)
        {
            final long pos = PRELOAD_QUEUE.poll();
            final int cx = net.minecraft.world.level.ChunkPos.getX(pos);
            final int cz = net.minecraft.world.level.ChunkPos.getZ(pos);
            level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        }
        if (PRELOAD_QUEUE.isEmpty())
        {
            NewWorldSpawn.LOG.info("Pre-generation termine : zone {} chunks chargee (dim {}).",
                SpawnConfig.PRELOAD_RADIUS_CHUNKS.get(), SPAWN_DIM.location());
        }
    }

    // ------------------------------------------------------------------ aventure forcee

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event)
    {
        // TP /spawn differee : a CHAQUE tick
        SpawnTeleports.tick(event.getServer());
        TeleportGuard.tick(event.getServer());
        // pre-generation etalee de la dimension spawn
        final ServerLevel preLevel = spawnLevel(event.getServer());
        if (preLevel != null)
        {
            tickPreload(event.getServer(), preLevel);
        }
        if (!SpawnConfig.FORCE_ADVENTURE.get())
        {
            return;
        }
        final ServerLevel level = spawnLevel(event.getServer());
        if (level == null)
        {
            return;
        }
        for (final ServerPlayer p : level.players())
        {
            if (p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE)
            {
                if (SpawnConfig.OPS_EXEMPT.get() && p.hasPermissions(2))
                {
                    continue;
                }
                NewWorldSpawn.LOG.info("{} passe en AVENTURE (entree dans la dimension spawn, mode {})",
                    p.getName().getString(), p.gameMode.getGameModeForPlayer().getName());
                p.setGameMode(GameType.ADVENTURE);
            }
        }
    }

    /** Sortie de la dimension : on rend au joueur son gamemode d'origine. */
    @SubscribeEvent
    public static void onChangedDimension(final PlayerEvent.PlayerChangedDimensionEvent event)
    {
        if (!(event.getEntity() instanceof final ServerPlayer p))
        {
            return;
        }
        final boolean wasIn = event.getFrom().equals(SPAWN_DIM);
        final boolean nowIn = event.getTo().equals(SPAWN_DIM);
        if (wasIn && !nowIn)
        {
            final GameType previous = PREVIOUS_MODE.remove(p.getUUID());
            if (previous != null && !inSpawnDim(p))
            {
                p.setGameMode(previous);
                NewWorldSpawn.LOG.info("{} quitte la dimension spawn : gamemode restaure ({})",
                    p.getName().getString(), previous.getName());
            }
        }
        else if (nowIn && !wasIn)
        {
            if (p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE
                && !(SpawnConfig.OPS_EXEMPT.get() && p.hasPermissions(2)))
            {
                PREVIOUS_MODE.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
                p.setGameMode(GameType.ADVENTURE);
            }
        }
    }

    // ------------------------------------------------------------------ annulation /spawn sur interaction
    // + VERROU GRIEF : dans la dimension spawn, un non-OP ne peut RIEN faire au monde
    // (ni casser, ni poser, ni frapper, ni jeter) SAUF interagir avec les entites (PNJ)
    // et manger. Les OPs (opsExempt + permission 2) gardent la main pour construire.

    /** Verrou grief actif pour ce joueur (dimension spawn, hors ops exemptes) ? */
    private static boolean lockedInSpawn(final ServerPlayer p)
    {
        return inSpawnDim(p) && !(SpawnConfig.OPS_EXEMPT.get() && p.hasPermissions(2));
    }

    private static void deny(final net.neoforged.bus.api.ICancellableEvent event, final ServerPlayer p, final String what)
    {
        event.setCanceled(true);
        // message limite : 1 toutes les 5 s par joueur (l'action reste TOUJOURS annulee)
        final long now = System.currentTimeMillis();
        final Long last = LAST_DENY_MSG.get(p.getUUID());
        if (last == null || now - last >= 5000L)
        {
            LAST_DENY_MSG.put(p.getUUID(), now);
            p.sendSystemMessage(Component.literal("§7Ici, vous ne pouvez qu'interagir avec les PNJ."));
        }
        NewWorldSpawn.LOG.info("Verrou spawn : {} bloque ({}, dim {})", p.getName().getString(), what,
            p.level().dimension().location());
    }

    /** Toute interaction avec le monde annule une TP /spawn en attente. */
    @SubscribeEvent
    public static void onRightClickBlock(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event)
    {
        spawnCancel(event);
        if (event.getEntity() instanceof final ServerPlayer p && lockedInSpawn(p))
        {
            deny(event, p, "clic droit bloc");
        }
    }

    @SubscribeEvent
    public static void onRightClickItemPre(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event)
    {
        spawnCancel(event);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event)
    {
        spawnCancel(event);
        if (event.getEntity() instanceof final ServerPlayer p && lockedInSpawn(p))
        {
            deny(event, p, "casse bloc");
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event)
    {
        spawnCancel(event);
        // PERMIS : interagir avec les entites (PNJ, menus des gouvernements...)
    }

    @SubscribeEvent
    public static void onRightClickItem(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event)
    {
        spawnCancel(event);
        // PERMIS : manger (sinon impossible de se nourrir dans la dimension).
        if (event.getEntity() instanceof final ServerPlayer p
            && lockedInSpawn(p)
            && !event.getItemStack().has(net.minecraft.core.component.DataComponents.FOOD))
        {
            deny(event, p, "clic droit item");
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(final net.neoforged.neoforge.event.entity.player.AttackEntityEvent event)
    {
        if (event.getEntity() instanceof final ServerPlayer p)
        {
            SpawnTeleports.cancelIfPending(p, "interaction");
            if (lockedInSpawn(p) && !(SpawnConfig.NO_PVP.get()
                && event.getTarget() instanceof ServerPlayer))
            {
                deny(event, p, "frapper entite");
            }
        }
    }

    /** Pas de drop d'items dans la dimension spawn (anti-grief / anti-perte). */
    @SubscribeEvent
    public static void onItemToss(final net.neoforged.neoforge.event.entity.item.ItemTossEvent event)
    {
        if (event.getPlayer() instanceof final ServerPlayer p && lockedInSpawn(p))
        {
            event.setCanceled(true);
            p.sendSystemMessage(Component.literal("§7Impossible de jeter des objets ici."));
            NewWorldSpawn.LOG.info("Verrou spawn : {} bloque (toss)", p.getName().getString());
        }
    }

    private static void spawnCancel(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent event)
    {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof final ServerPlayer p))
        {
            return;
        }
        SpawnTeleports.cancelIfPending(p, "interaction");
    }

    /** Deconnexion/mort : purge. */
    @SubscribeEvent
    public static void onLogout(final PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof final ServerPlayer p)
        {
            SpawnTeleports.cancelServer(p);
            TeleportGuard.cancel(p.getUUID());
            PREVIOUS_MODE.remove(p.getUUID());
            LAST_DENY_MSG.remove(p.getUUID());
        }
    }

    // ------------------------------------------------------------------ /setspawn all : accueil des nouveaux arrivants

    @SubscribeEvent
    public static void onLogin(final PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof final ServerPlayer p))
        {
            return;
        }
        if (!SpawnState.get(p.getServer()).forceSpawnAll)
        {
            return;
        }
        if (inSpawnDim(p))
        {
            return; // deja dans la dimension (reconnexion) : rien a faire
        }
        final ServerLevel level = spawnLevel(p.getServer());
        if (level == null)
        {
            return;
        }
        // TP DIFFEREE (10 ticks) : teleporter directement DANS PlayerLoggedInEvent ne colle pas
        // fiablement (init client + ImmPtl). On passe par le meme chemin sur que /spawn
        // (generation du chunk destination + garde anti-chute), sans compte a rebours.
        SpawnTeleports.scheduleLogin(p, level);
        NewWorldSpawn.LOG.info("Accueil : {} sera teleporte dans la dimension spawn (setspawn all)",
            p.getName().getString());
    }

    // ------------------------------------------------------------------ pas de PvP

    @SubscribeEvent
    public static void onDamage(final LivingIncomingDamageEvent event)
    {
        // toute TP /spawn en attente est annulee par des degats (toute source)
        if (event.getEntity() instanceof final ServerPlayer victim)
        {
            SpawnTeleports.cancelIfPending(victim, "dégâts subis");
        }
        if (!SpawnConfig.NO_PVP.get())
        {
            return;
        }
        if (!(event.getEntity().level() instanceof final ServerLevel level) || level.dimension() != SPAWN_DIM)
        {
            return;
        }
        if (event.getSource().getEntity() instanceof ServerPlayer && event.getEntity() instanceof ServerPlayer)
        {
            event.setCanceled(true);
            event.getSource().getEntity().sendSystemMessage(Component.literal(
                "§cLe PvP est desactive dans la dimension d'accueil."));
        }
    }

    // ------------------------------------------------------------------ nage quasi impossible ici uniquement

    @SubscribeEvent
    public static void onPlayerTick(final PlayerTickEvent.Post event)
    {
        if (!SpawnConfig.HEAVY_WATER.get())
        {
            return;
        }
        if (!(event.getEntity() instanceof final ServerPlayer p) || p.level().isClientSide())
        {
            return;
        }
        if (!inSpawnDim(p) || !p.isInWater() || p.getAbilities().flying || p.isSpectator())
        {
            return;
        }
        // trop lourd : la vitesse de nage est ecrasee, le joueur coule doucement
        final var dm = p.getDeltaMovement();
        p.setDeltaMovement(dm.x * SpawnConfig.WATER_TRAVERSAL_RATE.get(),
            Math.min(dm.y, -SpawnConfig.WATER_SINK_SPEED.get()),
            dm.z * SpawnConfig.WATER_TRAVERSAL_RATE.get());
        p.hurtMarked = true;
    }
}
