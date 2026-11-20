package dev.prag.newworldspawn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Etat persistant du mod : le flag /setspawn all (tous les arrivants arrivent dans la dimension spawn).
 * Sauvegarde dans le data de l'overworld (survit aux redemarrages).
 */
public final class SpawnState extends SavedData
{
    private static final String KEY_FORCE_ALL = "forceSpawnAll";

    public boolean forceSpawnAll = false;

    public static SpawnState get(final MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(SpawnState::new, (tag, registries) -> SpawnState.load(tag)), NewWorldSpawn.MODID + "_state");
    }

    private static SpawnState load(final CompoundTag tag)
    {
        final SpawnState s = new SpawnState();
        s.forceSpawnAll = tag.getBoolean(KEY_FORCE_ALL);
        return s;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final net.minecraft.core.HolderLookup.Provider registries)
    {
        tag.putBoolean(KEY_FORCE_ALL, this.forceSpawnAll);
        return tag;
    }
}
