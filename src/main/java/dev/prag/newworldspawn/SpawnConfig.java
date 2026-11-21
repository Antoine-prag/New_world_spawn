package dev.prag.newworldspawn;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config serveur (newworldspawn_server.toml).
 */
public final class SpawnConfig
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SPAWN_DELAY_SECONDS;
    public static final ModConfigSpec.IntValue BORDER_RADIUS_CHUNKS;
    public static final ModConfigSpec.BooleanValue FORCE_ADVENTURE;
    public static final ModConfigSpec.BooleanValue OPS_EXEMPT;
    public static final ModConfigSpec.BooleanValue NO_PVP;
    public static final ModConfigSpec.BooleanValue HEAVY_WATER;
    public static final ModConfigSpec.DoubleValue WATER_SINK_SPEED;
    public static final ModConfigSpec.DoubleValue WATER_TRAVERSAL_RATE;
    public static final ModConfigSpec.IntValue RANDOM_TP_RANGE;
    public static final ModConfigSpec.BooleanValue SPAWN_ON_JOIN;
    public static final ModConfigSpec.IntValue PRELOAD_RADIUS_CHUNKS;
    public static final ModConfigSpec.IntValue PRELOAD_CHUNKS_PER_TICK;

    public static final ModConfigSpec SPEC;

    static
    {
        BUILDER.push("Spawn");
        SPAWN_DELAY_SECONDS = BUILDER
            .comment("Delai en secondes avant la teleportation de /spawn. Annulee si interaction avec le monde. 0 = instantane.")
            .defineInRange("spawnDelaySeconds", 5, 0, 120);
        SPAWN_ON_JOIN = BUILDER
            .comment("Tous les joueurs qui se connectent arrivent dans la dimension spawn (accueil, NE BYPASSE PAS les lits au respawn).")
            .define("spawnOnJoin", false);
        BORDER_RADIUS_CHUNKS = BUILDER
            .comment("Rayon de la dimension spawn, en chunks (bordure carree centree en 0,0).")
            .defineInRange("borderRadiusChunks", 20, 1, 512);
        BUILDER.pop();

        BUILDER.push("Dimension");
        FORCE_ADVENTURE = BUILDER
            .comment("Gamemode aventure force dans la dimension spawn (jamais survival), restaure a la sortie.")
            .define("forceAdventure", true);
        OPS_EXEMPT = BUILDER
            .comment("Les OPs (permission 2+) gardent leur gamemode dans la dimension spawn (ex : pour construire le spawn).")
            .define("opsExempt", true);
        NO_PVP = BUILDER
            .comment("PvP desactive dans la dimension spawn.")
            .define("noPvp", true);
        HEAVY_WATER = BUILDER
            .comment("Eau tres lourde / nage quasi impossible dans la dimension spawn uniquement.")
            .define("heavyWater", true);
        WATER_SINK_SPEED = BUILDER
            .comment("Vitesse de chute dans l'eau (negative = coule).")
            .defineInRange("waterSinkSpeed", 0.35, 0.0, 2.0);
        WATER_TRAVERSAL_RATE = BUILDER
            .comment("Multiplicateur de deplacement horizontal dans l'eau (0.1 = tres lourd).")
            .defineInRange("waterTraversalRate", 0.1, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("Preload");
        PRELOAD_RADIUS_CHUNKS = BUILDER
            .comment("Rayon (en chunks) pre-genere + force-load en permanence autour de (0,0) dans la dimension"
                + " spawn (anti-lag : /spawn et les logins ne generent plus de chunks). 0 = desactive.")
            .defineInRange("preloadRadiusChunks", 12, 0, 260);
        PRELOAD_CHUNKS_PER_TICK = BUILDER
            .comment("Chunks generes par tick pendant la pre-generation au demarrage (monte progressivement, sans bloquer).")
            .defineInRange("preloadChunksPerTick", 4, 1, 200);
        BUILDER.pop();

        BUILDER.push("RandomTP");
        RANDOM_TP_RANGE = BUILDER
            .comment("Portee maximale (en blocs) du /randomtp dans l'overworld.")
            .defineInRange("range", 10000, 100, 30000000);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private SpawnConfig()
    {
    }
}
