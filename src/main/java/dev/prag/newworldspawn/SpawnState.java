package dev.prag.newworldspawn;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import dev.prag.newworldspawn.log.NwsLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Etat persistant du mod :
 * - forceSpawnAll : le flag /setspawn all (tous les arrivants arrivent dans la dimension spawn) ;
 * - spawnSpot     : la position EXACTE enregistree par /setspawn all|here (destination des /spawn
 *   et des TP de login). NOTE v1.0.11 : plus aucun calcul de heightmap pour la destination —
 *   on teleporte a l'endroit OU L'OP A TAPE LA COMMANDE (ex : dans un bateau), pas "tout en haut".
 * NOTE v1.0.5 : sauvegarde PROPERTIES ecrite IMMEDIATEMENT a chaque /setspawn (aucune dependance
 * a l'autosave). Fichier : <world>/data/newworldspawn_state.properties.
 */
public final class SpawnState
{
    private static final LevelResource FILE = new LevelResource("data/newworldspawn_state.properties");
    private static final String KEY_FORCE_ALL = "forceSpawnAll";
    private static final String KEY_SPOT_SET = "spawnSpotSet";
    private static final String KEY_SPOT_X = "spawnX";
    private static final String KEY_SPOT_Y = "spawnY";
    private static final String KEY_SPOT_Z = "spawnZ";
    private static final String KEY_SPOT_YAW = "spawnYaw";
    private static final String KEY_SPOT_PITCH = "spawnPitch";

    /** Flag courant (mis a jour par get() avant chaque lecture). */
    public boolean forceSpawnAll = false;

    /** Spot exact enregistre (false = pas encore de /setspawn here|all avec un joueur : fallback ancien). */
    public boolean spotSet = false;
    public double spotX = 0.5, spotY = 64.0, spotZ = 0.5;
    public float spotYaw = 0.0f, spotPitch = 0.0f;

    /** Lit le fichier d'etat (toujours frais : 1 lecture par appel, login inclus). */
    public static SpawnState get(final MinecraftServer server)
    {
        final SpawnState s = new SpawnState();
        load(server, s);
        return s;
    }

    /** Destination du hub : spot enregistre, sinon fallback ancien (0.5, spawnY, 0.5). */
    public double destX(final ServerLevel level)
    {
        return spotSet ? spotX : 0.5;
    }

    public double destY(final ServerLevel level)
    {
        return spotSet ? spotY : GameEvents.spawnY(level);
    }

    public double destZ(final ServerLevel level)
    {
        return spotSet ? spotZ : 0.5;
    }

    public float destYaw(final float ifAbsent)
    {
        return spotSet ? spotYaw : ifAbsent;
    }

    public float destPitch(final float ifAbsent)
    {
        return spotSet ? spotPitch : ifAbsent;
    }

    /** Ecrit le flag IMMEDIATEMENT sur disque (appelle par /setspawn all|off). */
    public static void set(final MinecraftServer server, final boolean forceAll)
    {
        final SpawnState cur = get(server);
        cur.forceSpawnAll = forceAll;
        write(server, cur);
    }

    /**
     * Ecrit le flag + le spot EXACT OU SE TROUVE LE JOUEUR (pieds + rotation) : c'est la
     * destination de tous les /spawn et TP de login. Appelle par /setspawn all|here.
     */
    public static void setSpot(final MinecraftServer server, final boolean forceAll,
        final double x, final double y, final double z, final float yaw, final float pitch)
    {
        final SpawnState s = new SpawnState();
        s.forceSpawnAll = forceAll;
        s.spotSet = true;
        s.spotX = x;
        s.spotY = y;
        s.spotZ = z;
        s.spotYaw = yaw;
        s.spotPitch = pitch;
        write(server, s);
        NwsLog.info("Spot spawn enregistre EXACTEMENT a x={}, y={}, z={}, yaw={}, pitch={}", x, y, z, yaw, pitch);
    }

    private static void write(final MinecraftServer server, final SpawnState s)
    {
        try
        {
            final Path path = file(server);
            Files.createDirectories(path.getParent());
            final Properties props = new Properties();
            props.setProperty(KEY_FORCE_ALL, String.valueOf(s.forceSpawnAll));
            props.setProperty(KEY_SPOT_SET, String.valueOf(s.spotSet));
            if (s.spotSet)
            {
                props.setProperty(KEY_SPOT_X, String.valueOf(s.spotX));
                props.setProperty(KEY_SPOT_Y, String.valueOf(s.spotY));
                props.setProperty(KEY_SPOT_Z, String.valueOf(s.spotZ));
                props.setProperty(KEY_SPOT_YAW, String.valueOf(s.spotYaw));
                props.setProperty(KEY_SPOT_PITCH, String.valueOf(s.spotPitch));
            }
            try (OutputStream out = Files.newOutputStream(path))
            {
                props.store(out, "New World Spawn : /setspawn all|here persiste immediatement (pas de perte au redemarrage)");
            }
            NwsLog.info("Etat persiste : forceSpawnAll={}, spotSet={} ({})", s.forceSpawnAll, s.spotSet, path);
        }
        catch (final Throwable t)
        {
            NwsLog.info("ERREUR ecriture etat forceSpawnAll={} : {}", s.forceSpawnAll, t);
        }
    }

    private static Path file(final MinecraftServer server)
    {
        return server.getWorldPath(FILE);
    }

    private static void load(final MinecraftServer server, final SpawnState s)
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
                s.forceSpawnAll = Boolean.parseBoolean(props.getProperty(KEY_FORCE_ALL, "false"));
                s.spotSet = Boolean.parseBoolean(props.getProperty(KEY_SPOT_SET, "false"));
                if (s.spotSet)
                {
                    try
                    {
                        s.spotX = Double.parseDouble(props.getProperty(KEY_SPOT_X, "0.5"));
                        s.spotY = Double.parseDouble(props.getProperty(KEY_SPOT_Y, "64"));
                        s.spotZ = Double.parseDouble(props.getProperty(KEY_SPOT_Z, "0.5"));
                        s.spotYaw = Float.parseFloat(props.getProperty(KEY_SPOT_YAW, "0"));
                        s.spotPitch = Float.parseFloat(props.getProperty(KEY_SPOT_PITCH, "0"));
                    }
                    catch (final NumberFormatException nfe)
                    {
                        NwsLog.info("Spot spawn corrompu dans {} : fallback (0.5, spawnY, 0.5)", path.getFileName());
                        s.spotSet = false;
                    }
                }
                NwsLog.info("Etat lu : forceSpawnAll={}, spotSet={}", s.forceSpawnAll, s.spotSet);
                return;
            }
            NwsLog.info("Pas encore de fichier d'etat ({}) : forceSpawnAll=false, spotSet=false", path.getFileName());
        }
        catch (final Throwable t)
        {
            NwsLog.info("ERREUR lecture etat : {}", t);
        }
    }

    private SpawnState()
    {
    }
}
