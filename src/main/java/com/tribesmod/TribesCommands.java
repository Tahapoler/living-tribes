package com.tribesmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TribesCommands {
    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx, Commands.CommandSelection env) {
        d.register(Commands.literal("tribes")
                .executes(c -> list(c.getSource()))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("top").executes(c -> top(c.getSource())))
                .then(Commands.literal("found").requires(s -> s.hasPermission(2)).executes(c -> found(c.getSource())))
                .then(Commands.literal("boost").requires(s -> s.hasPermission(2)).executes(c -> boost(c.getSource())))
                .then(Commands.literal("stage").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 6))
                                .executes(c -> setStage(c.getSource(), IntegerArgumentType.getInteger(c, "n"))))));
    }

    private static String stageName(Tribe t) {
        return TribeConfig.STAGES[Math.max(0, Math.min(6, t.stage))];
    }

    private static Tribe nearest(CommandSourceStack src, TribeData data) {
        BlockPos me = BlockPos.containing(src.getPosition());
        Tribe best = null;
        double bd = Double.MAX_VALUE;
        for (Tribe t : data.tribes.values()) {
            double dx = t.center.getX() - me.getX(), dz = t.center.getZ() - me.getZ();
            double dist = dx * dx + dz * dz;
            if (dist < bd) { bd = dist; best = t; }
        }
        return best;
    }

    private static void say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }

    private static int list(CommandSourceStack src) {
        ServerLevel ow = src.getServer().overworld();
        TribeData data = TribeData.get(ow);
        if (data.tribes.isEmpty()) {
            say(src, "No tribes yet. Explore the world!");
            return 0;
        }
        BlockPos me = BlockPos.containing(src.getPosition());
        List<Tribe> l = new ArrayList<>(data.tribes.values());
        l.sort(Comparator.comparingDouble(t -> t.center.distSqr(me)));
        int n = 0;
        for (Tribe t : l) {
            if (n++ >= 15) break;
            StringBuilder en = new StringBuilder();
            for (int id : t.enemies) {
                Tribe o = data.tribes.get(id);
                if (o != null) en.append(o.name).append(' ');
            }
            Tribe p = t.parent == 0 ? null : data.tribes.get(t.parent);
            String line = String.format("%s [Lv%d] %s pop:%d @ %d %d %d%s  war: %s",
                    t.name, t.level, stageName(t), t.pop,
                    t.center.getX(), t.center.getY(), t.center.getZ(),
                    p == null ? "" : " (vassal of " + p.name + ")", en.length() == 0 ? "-" : en);
            say(src, line);
        }
        say(src, "Dragons slain by tribes: " + data.dragonsSlain + "   |   /tribes info, /tribes top");
        return l.size();
    }

    private static int info(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        Tribe p = t.parent == 0 ? null : data.tribes.get(t.parent);
        int vassals = 0;
        for (Tribe o : data.tribes.values()) if (o.parent == t.id) vassals++;
        StringBuilder en = new StringBuilder();
        for (int id : t.enemies) {
            Tribe o = data.tribes.get(id);
            if (o != null) en.append(o.name).append(' ');
        }
        say(src, "§e=== " + t.name + " === §7(" + Tribe.TRAITS[Math.floorMod(t.trait, 3)] + ")"
                + (p == null ? "" : " §6vassal of " + p.name));
        say(src, "Level " + t.level + "/" + t.maxLevel() + "   Stage: §b" + stageName(t));
        say(src, "Pop " + t.pop + "/" + t.popCap() + "   Food " + t.food + "   Wood " + t.wood + "   Wealth " + t.wealth);
        say(src, "Stone " + t.stone + "   Iron " + t.iron + "   Gold " + t.gold + "   Diamond " + t.diamond
                + "   Blaze " + t.blaze + "   Pearls " + t.pearls);
        say(src, "Mines started: " + t.mine[TribeMine.MINES] + "/" + TribeMine.maxMines(t)
                + "   Tunnel cells dug: " + t.mine[TribeMine.COUNT] + "   Buildings: " + t.slots);
        say(src, "Vassals/colonies: " + vassals + "   Strength: " + (int) t.strength() + "   Wars: " + (en.length() == 0 ? "-" : en));
        return 1;
    }

    private static int top(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Map<Integer, double[]> agg = new HashMap<>(); // root -> {strength, members}
        for (Tribe t : data.tribes.values()) {
            int r = TribeManager.rootId(data, t);
            double[] a = agg.computeIfAbsent(r, k -> new double[2]);
            a[0] += t.strength();
            a[1] += 1;
        }
        List<Map.Entry<Integer, double[]>> es = new ArrayList<>(agg.entrySet());
        es.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
        say(src, "§6=== Mightiest empires ===");
        int n = 0;
        for (Map.Entry<Integer, double[]> e : es) {
            if (n++ >= 8) break;
            Tribe r = data.tribes.get(e.getKey());
            if (r == null) continue;
            say(src, n + ". " + r.name + " - " + stageName(r) + " - power " + (int) e.getValue()[0]
                    + " - " + (int) e.getValue()[1] + " tribe(s)");
        }
        return es.size();
    }

    private static int boost(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        t.pop = Math.min(t.popCap(), t.pop + 20);
        t.food += 500;
        t.wood += 500;
        t.wealth += 1500;
        t.stone += 500;
        t.iron += 300;
        t.gold += 150;
        t.diamond += 100;
        t.blaze += 30;
        t.pearls += 30;
        data.setDirty();
        src.sendSuccess(() -> Component.literal("Boosted tribe " + t.name), true);
        return 1;
    }

    private static int setStage(CommandSourceStack src, int n) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        t.stage = n;
        t.cool = 0;
        t.level = Math.max(t.level, Math.min(t.maxLevel(), 2 * n));
        data.setDirty();
        src.sendSuccess(() -> Component.literal(t.name + " is now at stage: " + stageName(t)), true);
        return 1;
    }

    private static int found(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel lv = src.getLevel();
        if (lv.dimension() != Level.OVERWORLD) {
            src.sendFailure(Component.literal("Overworld only."));
            return 0;
        }
        BlockPos p = src.getPlayerOrException().blockPosition();
        int x = p.getX() + 14, z = p.getZ();
        BlockPos pos = new BlockPos(x, TribeManager.groundY(lv, x, z), z);
        if (!lv.hasChunksAt(pos.offset(-24, 0, -24), pos.offset(24, 0, 24))) {
            src.sendFailure(Component.literal("Chunks not loaded here."));
            return 0;
        }
        Tribe t = TribeManager.found(lv, TribeData.get(lv), pos);
        src.sendSuccess(() -> Component.literal("Founded tribe " + t.name), true);
        return 1;
    }
}
