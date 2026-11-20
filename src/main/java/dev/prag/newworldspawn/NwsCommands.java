package dev.prag.newworldspawn;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.prag.newworldspawn.log.NwsLog;
import net.minecraft.commands.CommandSourceStack;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;


/**
 * Commandes :
 * - /spawn : teleporte dans la dimension d'accueil (delai 5 s, annule si interaction).
 * - /setspawn all|off : (OP) tous les joueurs qui se connectent arrivent dans la dimension spawn.
 *   Ne change JAMAIS le respawn/lit : uniquement le point d'arrivee a la connexion.
 * - /randomtp [joueur] : sortie de la dimension spawn vers un point aleatoire de l'overworld.
 *   /randomtp seul : n'importe quel joueur. /randomtp <joueur> : OP uniquement.
 */
@EventBusSubscriber(modid = NewWorldSpawn.MODID)
public final class NwsCommands
{
    private NwsCommands()
    {
    }

    private static final net.minecraft.util.RandomSource RANDOM = net.minecraft.util.RandomSource.create();

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event)
    {
        final var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("spawn").executes(NwsCommands::spawnCmd));
        dispatcher.register(Commands.literal("setspawn")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("mode", StringArgumentType.word())
                .suggests((throwContext, builder) ->
                    builder.suggest("all").suggest("off").buildFuture())
                .executes(NwsCommands::setSpawnCmd)));
        dispatcher.register(Commands.literal("randomtp")
            .executes(NwsCommands::randomTpSelf)
            .then(Commands.argument("joueur", EntityArgument.player())
                .requires(src -> src.hasPermission(2))
                .executes(NwsCommands::randomTpPlayer)));
        NwsLog.info("Commandes enregistrees : /spawn, /setspawn all|off, /randomtp [joueur]");
    }

    // ------------------------------------------------------------------ /spawn

    static int spawnCmd(final CommandContext<CommandSourceStack> ctx)
    {
        if (!(ctx.getSource().getEntity() instanceof final ServerPlayer p))
        {
            ctx.getSource().sendFailure(Component.literal("Commande joueur uniquement."));
            return 0;
        }
        final ServerLevel level = GameEvents.spawnLevel(p.getServer());
        if (level == null)
        {
            p.sendSystemMessage(Component.literal("§cDimension d'accueil indisponible (voir logs/newworldspawn.log)."));
            return 0;
        }
        if (GameEvents.inSpawnDim(p))
        {
            p.sendSystemMessage(Component.literal("§7Vous êtes déjà dans la dimension d'accueil."));
            return 0;
        }
        final int delaySec = SpawnConfig.SPAWN_DELAY_SECONDS.get();
        if (delaySec <= 0)
        {
            p.teleportTo(level, 0.5, GameEvents.spawnY(level), 0.5, p.getYRot(), p.getXRot());
            p.fallDistance = 0.0f;
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            TeleportGuard.guard(p, level, 0.5, GameEvents.spawnY(level), 0.5, GameEvents.spawnY(level) - 0.5);
            p.sendSystemMessage(Component.literal("§aBienvenue dans la dimension d'accueil ! §7(/randomtp pour sortir)"));
        }
        else
        {
            SpawnTeleports.schedule(p, level, 0.5, GameEvents.spawnY(level), 0.5,
                p.getYRot(), p.getXRot(), delaySec * 20);
            p.sendSystemMessage(Component.literal("§7Téléportation au spawn dans §e" + delaySec
                + "s§7 — n'interagissez pas, ne bougez pas et ne subissez pas de dégâts, sinon elle est annulée."));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /setspawn all|off

    static int setSpawnCmd(final CommandContext<CommandSourceStack> ctx)
    {
        final String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
        final SpawnState state = SpawnState.get(ctx.getSource().getServer());
        switch (mode)
        {
            case "all" ->
            {
                state.forceSpawnAll = true;
                state.setDirty();
                NwsLog.info("/setspawn all : tous les joueurs qui se connectent arrivent dans la dimension spawn.");
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aOK : tous les joueurs qui se connectent arrivent dans la dimension d'accueil."
                        + " §7(les lits gardent le respawn de mort)."), true);
            }
            case "off" ->
            {
                state.forceSpawnAll = false;
                state.setDirty();
                NwsLog.info("/setspawn off : retour au spawn vanilla a la connexion.");
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aOK : spawn à la connexion = vanilla."), true);
            }
            default -> ctx.getSource().sendFailure(Component.literal("§cUsage : /setspawn <all|off>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /randomtp [joueur]

    static int randomTpSelf(final CommandContext<CommandSourceStack> ctx)
    {
        if (!(ctx.getSource().getEntity() instanceof final ServerPlayer p))
        {
            ctx.getSource().sendFailure(Component.literal("Commande joueur uniquement."));
            return 0;
        }
        randomTp(p);
        return Command.SINGLE_SUCCESS;
    }

    static int randomTpPlayer(final CommandContext<CommandSourceStack> ctx)
    {
        try
        {
            final ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
            randomTp(target);
            ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + target.getName().getString() + " téléporté aléatoirement."), true);
        }
        catch (final com.mojang.brigadier.exceptions.CommandSyntaxException e)
        {
            ctx.getSource().sendFailure(Component.literal("§cJoueur introuvable."));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Surface GARANTIE : on force la generation COMPLETE du chunk (ChunkStatus.FULL) avant de lire
     * la heightmap — getHeight sur un chunk incomplet renvoie des valeurs fausses, ce qui posait
     * les joueurs a y~64 A L'INTERIEUR des montagnes. Occean : surface de l'eau.
     */
    private static int surfaceY(final ServerLevel over, final int x, final int z)
    {
        over.getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        int y = over.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        final int min = over.getSeaLevel() + 1;      // ocean : jamais sous la surface de l'eau
        final int max = over.getMaxBuildHeight() - 2; // jamais au-dessus du plafond
        if (y < min)
        {
            y = min;
        }
        if (y > max)
        {
            y = max;
        }
        return y;
    }

    private static void randomTp(final ServerPlayer p)
    {
        final ServerLevel over = p.getServer().overworld();
        final int range = SpawnConfig.RANDOM_TP_RANGE.get();
        int x = 0, z = 0, y = 0;
        boolean land = false;
        for (int attempt = 0; attempt < 25; attempt++)
        {
            x = Mth.randomBetweenInclusive(RANDOM, -range, range);
            z = Mth.randomBetweenInclusive(RANDOM, -range, range);
            y = surfaceY(over, x, z);
            if (y > over.getSeaLevel() + 2)
            {
                land = true; // terre ferme (au-dessus de la vague/ice)
                break;
            }
        }
        if (!land)
        {
            x = Mth.randomBetweenInclusive(RANDOM, -range, range);
            z = Mth.randomBetweenInclusive(RANDOM, -range, range);
            y = surfaceY(over, x, z);
        }
        final String from = p.level().dimension().location().toString();
        p.teleportTo(over, x + 0.5, y, z + 0.5, p.getYRot(), p.getXRot());
        p.fallDistance = 0.0f;
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        TeleportGuard.guard(p, over, x + 0.5, y, z + 0.5, y - 0.5);
        final String ou = land ? "terre ferme" : "surface de l'eau";
        p.sendSystemMessage(Component.literal("§aTéléporté vers §fx " + x + ", z " + z
            + " §7(" + ou + ")."));
        NwsLog.info("/randomtp : {} (depuis {}) -> x{} z{} y{} ({})", p.getName().getString(), from,
            x, z, y, ou);
    }
}
