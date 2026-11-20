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
 * interagit avec le monde (clics, pose, casse, frappe d'entite) ou change de niveau.
 * 100 % serveur. Compte a rebours annonce chaque seconde.
 */
public final class SpawnTeleports
{
    private SpawnTeleports()
    {
    }

    private record Pending(ServerLevel level, double x, double y, double z, float yaw, float pitch, long dueTick)
    {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    public static void schedule(final ServerPlayer player, final ServerLevel level, final double x, final double y,
        final double z, final float yaw, final float pitch, final int delayTicks)
    {
        PENDING.put(player.getUUID(), new Pending(level, x, y, z, yaw, pitch,
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
            final long remaining = pd.dueTick() - now;
            if (remaining > 0)
            {
                if (remaining % 20L == 0L)
                {
                    final ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                    if (p != null)
                    {
                        p.sendSystemMessage(Component.literal("§7Téléportation au spawn dans §e"
                            + (remaining / 20L) + "s§7..."));
                    }
                }
                continue;
            }
            PENDING.remove(e.getKey());
            final ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.isAlive())
            {
                continue;
            }
            p.teleportTo(pd.level(), pd.x(), pd.y(), pd.z(), pd.yaw(), pd.pitch());
            p.sendSystemMessage(Component.literal("§aBienvenue dans la dimension d'accueil ! §7(/randomtp pour sortir)"));
            dev.prag.newworldspawn.log.NwsLog.info("TP /spawn executee : {}", p.getName().getString());
        }
    }

    /** Mini-logger du mod : fichier logs/newworldspawn.log (pas la console). */
}
