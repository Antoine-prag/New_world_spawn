package dev.prag.newworldspawn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.prag.newworldspawn.log.NwsLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Garde anti-chute : juste apres une teleportation, le client peut ne pas avoir encore
 * recu le chunk destination et TOMBER A TRAVERS LE SOL (le serveur suit la chute -> joueur
 * decades dans les blocs, bloque en adventure). Pendant N ticks apres la TP, on ramene
 * immediatement le joueur a sa destination si il descend en dessous.
 */
public final class TeleportGuard
{
    private TeleportGuard()
    {
    }

    private record Guard(String dim, double x, double y, double z, double floorY, long untilTick)
    {
    }

    private static final Map<UUID, Guard> GUARDS = new ConcurrentHashMap<>();

    /** Duree par defaut de la garde (ticks) — largement assez pour l'arrivee des chunks. */
    public static final int DEFAULT_TICKS = 100;

    public static void guard(final ServerPlayer p, final ServerLevel level, final double x, final double y,
        final double z, final double floorY, final int ticks)
    {
        GUARDS.put(p.getUUID(), new Guard(level.dimension().location().toString(), x, y, z, floorY,
            p.getServer().getTickCount() + ticks));
    }

    public static void guard(final ServerPlayer p, final ServerLevel level, final double x, final double y,
        final double z, final double floorY)
    {
        guard(p, level, x, y, z, floorY, DEFAULT_TICKS);
    }

    /** @return true si une garde etait active (supprimee). */
    public static boolean cancel(final UUID uuid)
    {
        return GUARDS.remove(uuid) != null;
    }

    public static void tick(final MinecraftServer server)
    {
        if (GUARDS.isEmpty())
        {
            return;
        }
        final int now = server.getTickCount();
        for (final Map.Entry<UUID, Guard> e : GUARDS.entrySet())
        {
            final Guard g = e.getValue();
            final ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null)
            {
                GUARDS.remove(e.getKey());
                continue;
            }
            if (now > g.untilTick())
            {
                GUARDS.remove(e.getKey());
                continue;
            }
            if (!p.level().dimension().location().toString().equals(g.dim()))
            {
                continue; // sorti de la dimension entre-temps : la garde grimpe au timeout
            }
            if (p.getY() < g.floorY())
            {
                // chute a travers un chunk pas encore arrive client-side : on remet le joueur en place
                final ServerLevel lvl = (ServerLevel) p.level();
                p.teleportTo(lvl, g.x(), g.y(), g.z(), p.getYRot(), p.getXRot());
                p.setDeltaMovement(Vec3.ZERO);
                p.fallDistance = 0.0f;
                p.hurtMarked = true;
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§7Chunk en cours de chargement — stabilisation au spawn..."));
                NwsLog.info("Garde anti-chute : {} ramene a x{} y{} z{} (chute a travers chunk non recu)",
                    p.getName().getString(), (int) g.x(), (int) g.y(), (int) g.z());
            }
        }
    }
}
