package dev.prag.newworldspawn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.prag.newworldspawn.log.NwsLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Teleportation diferee pour /spawn (5 s par defaut), annulee si le joueur
 * interagit avec le monde (clics, pose, casse, frappe d'entite), change de niveau,
 * se DEPLACE ou SUBIT DES DEGATS. 100 % serveur. Compte a rebours annonce chaque seconde.
 */
public final class SpawnTeleports
{
    private SpawnTeleports()
    {
    }

    private record Pending(ServerLevel level, double x, double y, double z, float yaw, float pitch,
        double ox, double oy, double oz, long dueTick)
    {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** Deplacement au-dela de ce seuil (blocs) = TP annulee. Petite marge pour le bruit des positions client. */
    private static final double MOVE_CANCEL_SQ = 0.01;

    public static void schedule(final ServerPlayer player, final ServerLevel level, final double x, final double y,
        final double z, final float yaw, final float pitch, final int delayTicks)
    {
        PENDING.put(player.getUUID(), new Pending(level, x, y, z, yaw, pitch,
            player.getX(), player.getY(), player.getZ(),
            player.getServer().getTickCount() + delayTicks));
        dev.prag.newworldspawn.log.NwsLog.info("TP /spawn planifiee pour {} dans {} ticks", player.getName().getString(), delayTicks);
    }

    /** @return true si une TP en attente a ete annulee (interaction). */
    public static boolean cancelIfPending(final ServerPlayer player, final String reason)
    {
        final Pending pd = PENDING.remove(player.getUUID());
        if (pd != null)
        {
            player.sendSystemMessage(Component.literal("§cTéléportation annulée (" + reason + ")."));
            dev.prag.newworldspawn.log.NwsLog.info("TP /spawn annulee pour {} ({})", player.getName().getString(), reason);
            return true;
        }
        return false;
    }

    public static void cancelServer(final ServerPlayer player)
    {
        PENDING.remove(player.getUUID());
    }

    public static void tick(final MinecraftServer server)
    {
        if (PENDING.isEmpty())
        {
            return;
        }
        final int now = server.getTickCount();
        for (final Map.Entry<UUID, Pending> e : PENDING.entrySet())
        {
            final Pending pd = e.getValue();
            final ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null)
            {
                continue;
            }
            // DEPLACEMENT = annulation (position au moment du /spawn comparee a la position actuelle)
            final double dx = p.getX() - pd.ox();
            final double dy = p.getY() - pd.oy();
            final double dz = p.getZ() - pd.oz();
            if (dx * dx + dy * dy + dz * dz > MOVE_CANCEL_SQ)
            {
                PENDING.remove(e.getKey());
                p.sendSystemMessage(Component.literal("§cTéléportation annulée : vous vous êtes déplacé."));
                dev.prag.newworldspawn.log.NwsLog.info("TP /spawn annulee pour {} (deplacement detecte)",
                    p.getName().getString());
                continue;
            }
            if (!p.isAlive())
            {
                PENDING.remove(e.getKey());
                p.sendSystemMessage(Component.literal("§cTéléportation annulée : vous êtes mort."));
                dev.prag.newworldspawn.log.NwsLog.info("TP /spawn annulee pour {} (mort)", p.getName().getString());
                continue;
            }
            final long remaining = pd.dueTick() - now;
            if (remaining > 0)
            {
                if (remaining % 20L == 0L)
                {
                    p.sendSystemMessage(Component.literal("§7Téléportation au spawn dans §e"
                        + (remaining / 20L) + "s§7..."));
                }
                continue;
            }
            PENDING.remove(e.getKey());
            // generation COMPLETE du chunk destination AVANT teleportation
            pd.level().getChunk(((int) pd.x()) >> 4, ((int) pd.z()) >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            p.teleportTo(pd.level(), pd.x(), pd.y(), pd.z(), pd.yaw(), pd.pitch());
            p.fallDistance = 0.0f;
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            // garde anti-chute : si le client n'a pas encore le chunk, on le rattrape
            TeleportGuard.guard(p, pd.level(), pd.x(), pd.y(), pd.z(), pd.y() - 0.5);
            p.sendSystemMessage(Component.literal("§aBienvenue dans la dimension d'accueil ! §7(/randomtp pour sortir)"));
            dev.prag.newworldspawn.log.NwsLog.info("TP /spawn executee : {}", p.getName().getString());
        }
    }

    /** Mini-logger du mod : fichier logs/newworldspawn.log (pas la console). */
}
