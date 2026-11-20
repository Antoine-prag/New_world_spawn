package dev.prag.newworldspawn;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import dev.prag.newworldspawn.log.NwsLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Etat persistant du mod : le flag /setspawn all (tous les arrivants arrivent dans la dimension spawn).
 * NOTE v1.0.5 : l'ancienne sauvegarde SavedData (world/data/newworldspawn_state.dat) n'a JAMAIS ete
 * ecrite sur le serveur (fichier absent, flag perdu a chaque redemarrage). Remplacee par un fichier
 * PROPERTIES ecrit IMMEDIATEMENT a chaque /setspawn (plus aucune dependance a l'autosave).
 */
public final class SpawnState
{
    private static final LevelResource FILE = new LevelResource("data/newworldspawn_state.properties");
    private static final String KEY_FORCE_ALL = "forceSpawnAll";

    /** Flag courant (mis a jour par get() avant chaque lecture). */
    public boolean forceSpawnAll = false;

    /** Lit le fichier d'etat (toujours frais : 1 lecture par appel, login inclus). */
    public static SpawnState get(final MinecraftServer server)
    {
        final SpawnState s = new SpawnState();
        s.forceSpawnAll = load(server);
        return s;
    }

    /** Ecrit l'etat IMMEDIATEMENT sur disque (appelle par /setspawn all|off). */
    public static void set(final MinecraftServer server, final boolean forceAll)
    {
        try
        {
            final Path path = file(server);
            Files.createDirectories(path.getParent());
            final Properties props = new Properties();
            props.setProperty(KEY_FORCE_ALL, String.valueOf(forceAll));
            try (OutputStream out = Files.newOutputStream(path))
            {
                props.store(out, "New World Spawn : /setspawn all persiste immediatement (pas de perte au redemarrage)");
            }
            NwsLog.info("Etat persiste : forceSpawnAll={} ({})", forceAll, path);
        }
        catch (final Throwable t)
        {
            NwsLog.info("ERREUR ecriture etat forceSpawnAll={} : {}", forceAll, t);
        }
    }

    private static Path file(final MinecraftServer server)
    {
        return server.getWorldPath(FILE);
    }

    private static boolean load(final MinecraftServer server)
    {
        try
        {
            final Path path = file(server);
            if (Files.exists(path))
            {
                final Properties props = new Properties();
                try (InputStream in = Files.newInputStream(path))
                {
                    props.load(in);
                }
                final boolean v = Boolean.parseBoolean(props.getProperty(KEY_FORCE_ALL, "false"));
                NwsLog.info("Etat lu : forceSpawnAll={}", v);
                return v;
            }
            NwsLog.info("Pas encore de fichier d'etat ({}) : forceSpawnAll=false", path.getFileName());
        }
        catch (final Throwable t)
        {
            NwsLog.info("ERREUR lecture etat : {}", t);
        }
        return false;
    }

    private SpawnState()
    {
    }
}
