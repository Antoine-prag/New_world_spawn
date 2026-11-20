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
        ensureSpawnPlatform(level);
    }

    /**
     * Le monde est 100% aquatique (preset plat : fond/ardoise/roche/dirt/gravel + 90 d'eau) :
     * on construit une plateforme fixe au point d'arrivee (0,0), sinon les joueurs tombent
     * dans l'eau (lourde) et coulent. Idempotent. Y' = premier Y libre au-dessus du plus haut
     * bloc (l'eau compte), oui comme ca le preset reste aux commandes.
     */
    private static void ensureSpawnPlatform(final ServerLevel level)
    {
        // generation COMPLETE du chunk central avant lecture heightmap
        level.getChunk(0, 0, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        final int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
        final var center = new net.minecraft.core.BlockPos(0, y, 0);
        if (level.getBlockState(center).getBlock() == net.minecraft.world.level.block.Blocks.SANDSTONE)
        {
            return; // deja construite
        }
        final int half = 5; // plateforme 11x11
        for (int x = -half; x <= half; x++)
        {
            for (int z = -half; z <= half; z++)
            {
                level.setBlock(new net.minecraft.core.BlockPos(x, y, z),
                    net.minecraft.world.level.block.Blocks.SANDSTONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 2; dy++)
                {
                    final var above = new net.minecraft.core.BlockPos(x, y + dy, z);
                    if (!level.getBlockState(above).isAir())
                    {
                        level.setBlock(above, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
        NewWorldSpawn.LOG.info("Plateforme de spawn construite en (0,{}) : 11x11 sandstone (monde aquatique).", y);
        auditColumn(level, 10, 10);
    }

    /** Audit demarrage : une colonne de blocs dans logs/newworldspawn.log pour valider le preset sans client. */
    private static void auditColumn(final ServerLevel level, final int x, final int z)
    {
        final int minY = level.getMinBuildHeight();
        final int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        final StringBuilder sb = new StringBuilder("Blocs (").append(x).append(',').append(z).append(") : ");
        final int[] ys = { minY, minY + 1, minY + 65, minY + 70, minY + 75, top - 1, top, top + 1 };
        for (int i = 0; i < ys.length; i++)
        {
            final var st = level.getBlockState(new net.minecraft.core.BlockPos(x, ys[i], z));
            sb.append("y").append(ys[i]).append('=').append(st.getBlock().getName().getString());
            if (i < ys.length - 1)
            {
                sb.append(", ");
            }
        }
        NewWorldSpawn.LOG.info("AUDIT {}", sb);
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
