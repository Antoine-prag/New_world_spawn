package dev.prag.newworldspawn;

import com.mojang.logging.LogUtils;
import dev.prag.newworldspawn.log.NwsLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * New World Spawn : petite dimension d'accueil (aquatique, rayon 20 chunks),
 * aventure forcee, /spawn (delai 5 s), /setspawn all, /randomtp.
 */
@Mod(NewWorldSpawn.MODID)
public final class NewWorldSpawn
{
    public static final String MODID = "newworldspawn";
    public static final Logger LOG = LogUtils.getLogger();

    public NewWorldSpawn(final IEventBus modEventBus, final ModContainer modContainer)
    {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, SpawnConfig.SPEC,
            "newworldspawn_server.toml");
        NwsLog.init(FMLPaths.GAMEDIR.get());
        NwsLog.info("NewWorldSpawn charge (logs dedies : logs/newworldspawn.log).");
    }
}
