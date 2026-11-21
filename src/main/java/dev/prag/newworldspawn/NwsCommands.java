package dev.prag.newworldspawn;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.prag.newworldspawn.log.NwsLog;
import net.minecraft.commands.CommandSourceStack;

import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
 * - /setspawn all|here|off : (OP) tous les joueurs qui se connectent arrivent dans la dimension spawn ;
 *   all|here enregistrent AUSSI la position exacte de l'op comme point d'arrivee des TP.
 *   Ne change JAMAIS le respawn/lit : uniquement le point d'arrivee a la connexion.
 * - /randomtp [joueur] : (OP uniquement) sortie de la dimension spawn vers un point aleatoire de l'overworld.
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
                    builder.suggest("all").suggest("here").suggest("off").buildFuture())
                .executes(NwsCommands::setSpawnCmd)));
        dispatcher.register(Commands.literal("randomtp")
            .requires(src -> src.hasPermission(2)) // OP uniquement (joueur et <joueur>)
            .executes(NwsCommands::randomTpSelf)
            .then(Commands.argument("joueur", EntityArgument.player())
                .requires(src -> src.hasPermission(2))
                .executes(NwsCommands::randomTpPlayer)));
        NwsLog.info("Commandes enregistrees : /spawn, /setspawn all|here|off, /randomtp [joueur]");
    }

    // ------------------------------------------------------------------ /spawn

    /**
     * Destination du hub = le spot EXACT enregistre par /setspawn all|here (l'endroit ou
     * l'op a tape la commande, ex : dans un bateau), sinon fallback ancien (0.5, spawnY, 0.5).
     */
    private static SpawnState spotState(final MinecraftServer server)
    {
        return SpawnState.get(server);
    }

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
        final SpawnState st = spotState(p.getServer());
        final double x = st.destX(level);
        final double y = st.destY(level);
        final double z = st.destZ(level);
        final float yaw = st.destYaw(p.getYRot());
        final float pitch = st.destPitch(p.getXRot());
        final int delaySec = SpawnConfig.SPAWN_DELAY_SECONDS.get();
        if (delaySec <= 0)
        {
            p.teleportTo(level, x, y, z, yaw, pitch);
            p.fallDistance = 0.0f;
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            TeleportGuard.guard(p, level, x, y, z, y - 0.5);
            p.sendSystemMessage(Component.literal("§aBienvenue dans la dimension d'accueil !"));
        }
        else
        {
            SpawnTeleports.schedule(p, level, x, y, z, yaw, pitch, delaySec * 20);
            p.sendSystemMessage(Component.literal("§7Téléportation au spawn dans §e" + delaySec
                + "s§7 — n'interagissez pas, ne bougez pas et ne subissez pas de dégâts, sinon elle est annulée."));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ /setspawn all|here|off

    static int setSpawnCmd(final CommandContext<CommandSourceStack> ctx)
    {
        final String mode = StringArgumentType.getString(ctx, "mode").toLowerCase();
        final MinecraftServer server = ctx.getSource().getServer();
        final ServerPlayer self = ctx.getSource().getEntity() instanceof final ServerPlayer p ? p : null;
        switch (mode)
        {
            case "all" ->
            {
                // /setspawn all = flag + le spot devient l'endroit EXACT ou se trouve l'op
                // (on refait /setspawn all apres avoir (re)place le point d'accueil).
                if (self == null || !GameEvents.inSpawnDim(self))
                {
                    // pas dans la dimension : on bascule juste le flag, le spot reste l'ancien
                    SpawnState.set(server, true);
                    NwsLog.info("/setspawn all (hors dimension, spot inchangé) : tous les joueurs qui se connectent arrivent dans la dimension spawn.");
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§aOK : tous les joueurs qui se connectent arrivent dans la dimension d'accueil.§7 "
                            + "Position d'arrivée inchangée — mettez-vous à l'endroit voulu (dans la dimension) et tapez §e/setspawn here§7 pour la (re)définir."), false);
                }
                else
                {
                    SpawnState.setSpot(server, true, self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
                    NwsLog.info("/setspawn all : flag + spot = position exacte de l'op ({} {} {})", self.getX(), self.getY(), self.getZ());
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§aOK : tous les joueurs qui se connectent arrivent dans la dimension d'accueil, §fà l'endroit exact où vous êtes§a."
                            + " §7(les lits gardent le respawn en priorité après /c home)."), false);
                }
            }
            case "here" ->
            {
                if (self == null || !GameEvents.inSpawnDim(self))
                {
                    ctx.getSource().sendFailure(Component.literal(
                        "§cCommande joueur, à exécuter DANS la dimension d'accueil (à l'endroit voulu)."));
                    return 0;
                }
                final boolean cur = SpawnState.get(server).forceSpawnAll;
                SpawnState.setSpot(server, cur, self.getX(), self.getY(), self.getZ(), self.getYRot(), self.getXRot());
                NwsLog.info("/setspawn here : spot = position exacte de l'op ({} {} {}, yaw {})", self.getX(), self.getY(), self.getZ(), self.getYRot());
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aOK : point d'arrivée du spawn = votre position exacte (x" + String.format("%.2f", self.getX())
                        + " y" + String.format("%.2f", self.getY()) + " z" + String.format("%.2f", self.getZ()) + ")."), false);
            }
            case "off" ->
            {
                SpawnState.set(server, false); // ecrit IMMEDIATEMENT sur disque
                NwsLog.info("/setspawn off : retour au spawn vanilla a la connexion.");
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "§aOK : spawn à la connexion = vanilla."), true);
            }
            default -> ctx.getSource().sendFailure(Component.literal("§cUsage : /setspawn <all|here|off>"));
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
