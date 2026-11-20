package dev.prag.newworldspawn;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
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

    public static ServerLevel spawnLevel(final MinecraftServer server)
    {
        return server.getLevel(SPAWN_DIM);
    }

    public static boolean inSpawnDim(final ServerPlayer p)
    {
        return p.level().dimension() == SPAWN_DIM;
    }

    /** Y max au point de spawn (0,0). */
    public static double spawnY(final ServerLevel level)
    {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) + 1.0;
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
    }

    // ------------------------------------------------------------------ aventure forcee

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event)
    {
        // TP /spawn differee : a CHAQUE tick
        SpawnTeleports.tick(event.getServer());
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

    /** Toute interaction avec le monde annule une TP /spawn en attente. */
    @SubscribeEvent
    public static void onRightClickBlock(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event)
    {
        spawnCancel(event);
    }

    @SubscribeEvent
    public static void onRightClickItem(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event)
    {
        spawnCancel(event);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event)
    {
        spawnCancel(event);
    }

    @SubscribeEvent
    public static void onEntityInteract(final net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event)
    {
        spawnCancel(event);
    }

    @SubscribeEvent
    public static void onAttackEntity(final net.neoforged.neoforge.event.entity.player.AttackEntityEvent event)
    {
        if (event.getEntity() instanceof final ServerPlayer p)
        {
            SpawnTeleports.cancelIfPending(p, "interaction");
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
            PREVIOUS_MODE.remove(p.getUUID());
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
        p.teleportTo(level, 0.5, spawnY(level), 0.5, p.getYRot(), p.getXRot());
        p.sendSystemMessage(Component.literal(
            "§aBienvenue ! Vous arrivez dans la dimension d'accueil. Tapez §f/randomtp§a pour rejoindre le monde."));
        NewWorldSpawn.LOG.info("Accueil : {} teleporte dans la dimension spawn (setspawn all)",
            p.getName().getString());
    }

    // ------------------------------------------------------------------ pas de PvP

    @SubscribeEvent
    public static void onDamage(final LivingIncomingDamageEvent event)
    {
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
