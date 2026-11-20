package dev.prag.newworldspawn.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger dedie de New World Spawn : ecrit dans logs/newworldspawn.log (PAS dans la console serveur),
 * avec rotation (5 Mo -> .1, .2, .3). Permet de troubleshooter create/disband/economie sans polluer la console.
 */
public final class NwsLog
{
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long MAX_BYTES = 5L * 1024L * 1024L;
    private static final int KEEP = 3;
    private static final Object LOCK = new Object();
    private static Path file = null;

    private NwsLog()
    {
    }

    /** Resout le chemin une fois (gamedir/logs/newworldspawn.log). Sans effet avant le premier log. */
    public static void init(final Path gameDir)
    {
        synchronized (LOCK)
        {
            file = gameDir.resolve("logs").resolve("newworldspawn.log");
            try
            {
                Files.createDirectories(file.getParent());
            }
            catch (final IOException ignored)
            {
            }
        }
    }

    public static void info(final String message, final Object... args)
    {
        write("INFO ", format(message, args));
    }

    public static void warn(final String message, final Object... args)
    {
        write("WARN ", format(message, args));
    }

    public static void error(final String message, final Object... args)
    {
        write("ERROR", format(message, args));
    }

    private static String format(final String message, final Object[] args)
    {
        String s = message;
        for (final Object a : args)
        {
            final int i = s.indexOf("{}");
            s = s.substring(0, i) + (a == null ? "null" : a.toString()) + s.substring(i + 2);
        }
        return s;
    }

    private static void write(final String level, final String line)
    {
        synchronized (LOCK)
        {
            if (file == null)
            {
                // fallback : repertoire courant / logs
                file = Path.of("logs", "newworldspawn.log");
            }
            try
            {
                if (Files.exists(file) && Files.size(file) > MAX_BYTES)
                {
                    rotate();
                }
                final String out = LocalDateTime.now().format(TS) + " [" + level + "] " + line + System.lineSeparator();
                Files.writeString(file, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            catch (final IOException ignored)
            {
                // jamais d'impact jeu : probleme de disque on laisse tomber silencieusement
            }
        }
    }

    private static void rotate()
    {
        try
        {
            Files.deleteIfExists(file.resolveSibling("newworldspawn.log." + KEEP));
            for (int i = KEEP - 1; i >= 1; i--)
            {
                final Path from = file.resolveSibling("newworldspawn.log." + i);
                if (Files.exists(from))
                {
                    Files.move(from, file.resolveSibling("newworldspawn.log." + (i + 1)));
                }
            }
            Files.move(file, file.resolveSibling("newworldspawn.log.1"));
        }
        catch (final IOException ignored)
        {
        }
    }
}
