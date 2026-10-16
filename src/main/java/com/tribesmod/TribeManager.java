package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class TribeManager {
    static final String[] P1 = {"Stone", "Iron", "Red", "Black", "Wolf", "Raven", "Storm", "Ember", "Frost", "Gold", "Oak", "Shadow", "Sun", "Bone"};
    static final String[] P2 = {"fang", "clan", "hold", "born", "wood", "guard", "hawk", "blade", "hollow", "men", "river", "peak"};
    static final int ROLES = 5; // farmer, woodcutter, trader, warrior, miner
    static final double[] WEIGHTS = {4, 2, 2.5, 1.5, 2.5};

    // ================= main loop (every 5 seconds) =================
    public static void tick(ServerLevel level) {
        TribeData data = TribeData.get(level);
        RandomSource rnd = level.random;
        if (data.tribes.size() < TribeConfig.MAX_TRIBES) {
            for (ServerPlayer p : level.players()) {
                if (rnd.nextInt(2) == 0) tryFound(level, data, p.blockPosition(), rnd);
            }
        }
        for (Tribe t : new ArrayList<>(data.tribes.values())) tickTribe(level, data, t, rnd);
        data.setDirty();
    }

    static void tickTribe(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        if (!data.tribes.containsKey(t.id)) return;

        // vassals share the technology of their overlord
        if (t.parent != 0) {
            Tribe p = data.tribes.get(t.parent);
            if (p == null) t.parent = 0;
            else if (p.stage > t.stage) t.stage = p.stage;
        }

        boolean near = isNear(level, t);

        // a colony that has no buildings yet: settle it as soon as its land is loaded
        if (!t.founded) {
            trySettle(level, data, t, rnd);
            if (!data.tribes.containsKey(t.id)) return;
            if (!t.founded) near = false;
        }

        if (near) {
            List<TribesmanEntity> mem = members(level, t);
            if (mem.size() > t.pop) t.pop = mem.size();
            if (t.pop <= 0) { removeTribe(data, t); return; }
            int[] c = new int[ROLES];
            for (TribesmanEntity m : mem) c[m.getRole()]++;
            int want = Math.min(t.pop, t.entityCap()) - mem.size();
            int spawnMax = 3 + t.level / 4;
            for (int i = 0; i < Math.min(want, spawnMax); i++) spawnMember(level, t, pickRole(c));
            int ext = 18 + 7 * (t.slots / 6) + 6;
            if (t.wood >= 30 && t.slots < 4 + 2 * t.level
                    && level.hasChunksAt(t.center.offset(-ext, 0, -ext), t.center.offset(ext, 0, ext))) {
                t.wood -= 30;
                buildSlot(level, t);
            }
            specials(level, t);
        } else if (t.pop <= 0) {
            removeTribe(data, t);
            return;
        }

        // ---- economy ----
        double g = TribeConfig.GROWTH;
        double v = near ? 0.35 : 1.0;
        t.food += (int) Math.ceil((1 + t.pop * 0.5) * v * g);
        t.wood += (int) Math.ceil(Math.max(1, t.pop / 2.0) * v * g * 0.7);
        t.food = Math.max(0, t.food - t.pop / 3);
        int births = 1 + t.pop / 20;
        for (int i = 0; i < births; i++) {
            if (t.food >= 40 && t.pop < t.popCap()) { t.food -= 40; t.pop++; }
        }
        int tax = Math.max(1, t.pop / 6);
        if (t.trait == 1) tax = tax * 3 / 2;
        t.wealth += (int) Math.ceil(tax * g);
        if (t.food > 150) { t.food -= 100; t.wealth += 20; }
        if (t.wood > 200 && t.slots >= 4 + 2 * t.level) { t.wood -= 100; t.wealth += 10; }
        if (t.parent != 0) { // vassals pay tribute to the empire
            Tribe p = data.tribes.get(t.parent);
            if (p != null) {
                int tribute = t.wealth / 10;
                t.wealth -= tribute;
                p.wealth += tribute;
            }
        }
        if (t.level < t.maxLevel() && t.wealth >= 50 * t.level && t.pop >= 4 + 3 * t.level) {
            t.wealth -= 50 * t.level;
            t.level++;
            notifyNear(level, t.center, "§6[" + t.name + "] grew to level " + t.level + "!");
        }

        // ---- technology, dragon quest and expansion ----
        TribeProgress.tick(level, data, t, rnd, near);
        TribeProgress.expand(level, data, t, rnd);

        // ---- diplomacy ----
        if (rnd.nextInt(6) == 0) {
            Tribe o = nearestOther(data, t, 450 + 20 * t.level);
            if (o != null) {
                if (t.level >= 4 && t.strength() >= o.strength() * 2.5 && rnd.nextInt(3) == 0) {
                    conquer(level, data, t, o);
                } else if (t.enemies.contains(o.id)) {
                    if (rnd.nextInt(4) == 0) makePeace(level, t, o);
                } else if (rnd.nextInt(t.trait == 0 ? 4 : 8) == 0 && t.strength() >= o.strength() * 0.7) {
                    t.enemies.add(o.id);
                    o.enemies.add(t.id);
                    notifyNear(level, t.center, "§c[WAR] " + t.name + " declared war on " + o.name + "!");
                } else if (rnd.nextInt(t.trait == 1 ? 2 : 3) == 0) {
                    trade(t, o);
                }
            }
        }

        // ---- simulated war (only when no player is near either tribe) ----
        if (!near) {
            for (int eid : new ArrayList<>(t.enemies)) {
                Tribe o = data.tribes.get(eid);
                if (o == null) { t.enemies.remove(eid); continue; }
                if (t.id < o.id && !isNear(level, o) && rnd.nextInt(3) == 0) skirmish(level, data, t, o, rnd);
            }
        }
    }

    // ================= empires =================
    static int rootId(TribeData data, Tribe t) {
        Tribe c = t;
        for (int i = 0; i < 12 && c.parent != 0; i++) {
            Tribe p = data.tribes.get(c.parent);
            if (p == null) break;
            c = p;
        }
        return c.id;
    }

    static void conquer(ServerLevel level, TribeData data, Tribe w, Tribe l) {
        int root = rootId(data, w);
        if (root == rootId(data, l)) return;
        l.parent = root;
        for (int eid : new ArrayList<>(l.enemies)) {
            Tribe o = data.tribes.get(eid);
            if (o != null) o.enemies.remove(l.id);
        }
        l.enemies.clear();
        w.wealth += 40;
        notifyNear(level, w.center, "§6[EMPIRE] " + w.name + " conquered " + l.name + " - it is now part of the empire!");
    }

    static void trade(Tribe a, Tribe b) {
        a.wealth += 5;
        b.wealth += 5;
        if (a.food > b.food + 30) { a.food -= 10; b.food += 10; }
        else if (b.food > a.food + 30) { b.food -= 10; a.food += 10; }
    }

    static void skirmish(ServerLevel level, TribeData data, Tribe a, Tribe b, RandomSource rnd) {
        double sa = a.strength() * (0.5 + rnd.nextDouble());
        double sb = b.strength() * (0.5 + rnd.nextDouble());
        Tribe w = sa >= sb ? a : b;
        Tribe l = sa >= sb ? b : a;
        boolean overwhelming = w.strength() >= l.strength() * 1.8;
        if (l.pop <= 3 || (overwhelming && rnd.nextInt(2) == 0)) {
            conquer(level, data, w, l);
            makePeace(level, w, l);
            return;
        }
        int loss = Math.max(1, l.pop / 12);
        l.pop = Math.max(1, l.pop - loss);
        w.wealth += 10;
        w.food += 10;
        l.food = Math.max(0, l.food - 10);
    }

    static void makePeace(ServerLevel level, Tribe a, Tribe b) {
        a.enemies.remove(b.id);
        b.enemies.remove(a.id);
        notifyNear(level, a.center, "§a[PEACE] " + a.name + " and " + b.name + " made peace.");
    }

    static void removeTribe(TribeData data, Tribe t) {
        data.tribes.remove(t.id);
        for (Tribe o : data.tribes.values()) {
            o.enemies.remove(t.id);
            if (o.parent == t.id) o.parent = 0;
        }
    }

    static Tribe nearestOther(TribeData data, Tribe t, double maxDist) {
        Tribe best = null;
        double bd = maxDist * maxDist;
        int myRoot = rootId(data, t);
        for (Tribe o : data.tribes.values()) {
            if (o.id == t.id || rootId(data, o) == myRoot) continue;
            double d = o.center.distSqr(t.center);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    static void notifyNear(ServerLevel level, BlockPos pos, String msg) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(pos) < 400 * 400) p.sendSystemMessage(Component.literal(msg));
        }
    }

    static boolean isNear(ServerLevel level, Tribe t) {
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - t.center.getX();
            double dz = p.getZ() - t.center.getZ();
            if (dx * dx + dz * dz < 64.0 * 64.0) return true;
        }
        return false;
    }

    static List<TribesmanEntity> members(ServerLevel level, Tribe t) {
        double r = Math.max(100, t.territory() + 70);
        AABB box = new AABB(t.center.getX() - r, level.getMinBuildHeight(), t.center.getZ() - r,
                t.center.getX() + r, level.getMaxBuildHeight(), t.center.getZ() + r);
        return level.getEntitiesOfClass(TribesmanEntity.class, box, e -> e.getTribeId() == t.id && e.isAlive());
    }

    static int pickRole(int[] c) {
        int best = 0;
        double bv = Double.MAX_VALUE;
        for (int r = 0; r < ROLES; r++) {
            double v = c[r] / WEIGHTS[r];
            if (v < bv) { bv = v; best = r; }
        }
        c[best]++;
        return best;
    }

    // ================= founding =================
    static void tryFound(ServerLevel level, TribeData data, BlockPos origin, RandomSource rnd) {
        int dist = 40 + rnd.nextInt(45);
        double ang = rnd.nextDouble() * Math.PI * 2;
        int x = origin.getX() + (int) (Math.cos(ang) * dist);
        int z = origin.getZ() + (int) (Math.sin(ang) * dist);
        BlockPos probe = new BlockPos(x, 64, z);
        if (!level.hasChunksAt(probe.offset(-24, 0, -24), probe.offset(24, 0, 24))) return;
        BlockPos pos = new BlockPos(x, groundY(level, x, z), z);
        if (!suitable(level, pos)) return;
        for (Tribe t : data.tribes.values()) {
            if (t.center.distSqr(pos) < TribeConfig.MIN_DIST * TribeConfig.MIN_DIST) return;
        }
        found(level, data, pos);
    }

    static boolean suitable(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (pos.getY() <= level.getSeaLevel()) return false;
        if (!(below.is(BlockTags.DIRT) || below.is(BlockTags.SAND))) return false;
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) return false;
        int[][] corners = {{8, 8}, {-8, 8}, {8, -8}, {-8, -8}};
        for (int[] c : corners) {
            int h = groundY(level, pos.getX() + c[0], pos.getZ() + c[1]);
            if (Math.abs(h - pos.getY()) > 4) return false;
            if (!level.getFluidState(new BlockPos(pos.getX() + c[0], h - 1, pos.getZ() + c[1])).isEmpty()) return false;
        }
        return true;
    }

    public static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        while (y > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(new BlockPos(x, y - 1, z));
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) y--;
            else break;
        }
        return y;
    }

    public static Tribe found(ServerLevel level, TribeData data, BlockPos pos) {
        RandomSource rnd = level.random;
        Tribe t = new Tribe();
        t.id = data.nextId++;
        t.name = P1[rnd.nextInt(P1.length)] + P2[rnd.nextInt(P2.length)];
        t.center = pos;
        t.trait = rnd.nextInt(3);
        data.tribes.put(t.id, t);
        build(level, data, t);
        notifyNear(level, pos, "§e[Tribe] A new tribe appeared: " + t.name + " (" + Tribe.TRAITS[t.trait] + ")");
        return t;
    }

    /** Physically builds a tribe's starting village (campfire, 4 buildings, first members). */
    static void build(ServerLevel level, TribeData data, Tribe t) {
        BlockPos pos = t.center;
        flatten(level, pos.offset(-7, 0, -7), 15, 15);
        level.setBlock(pos, Blocks.CAMPFIRE.defaultBlockState(), 3);
        for (int i = 0; i < 4; i++) buildSlot(level, t);
        int[] c = new int[ROLES];
        for (int i = 0; i < Math.min(4, Math.max(1, t.pop)); i++) spawnMember(level, t, pickRole(c));
        t.founded = true;
        data.setDirty();
    }

    /** A colony founded "on paper": find real ground for it once its chunks are loaded. */
    static void trySettle(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        int cx = t.center.getX(), cz = t.center.getZ();
        if (!level.hasChunksAt(new BlockPos(cx - 40, 64, cz - 40), new BlockPos(cx + 40, 64, cz + 40))) return;
        for (int a = 0; a < 14; a++) {
            int x = cx + (a == 0 ? 0 : rnd.nextInt(61) - 30);
            int z = cz + (a == 0 ? 0 : rnd.nextInt(61) - 30);
            BlockPos pos = new BlockPos(x, groundY(level, x, z), z);
            if (suitable(level, pos)) {
                t.center = pos;
                build(level, data, t);
                notifyNear(level, pos, "§6[EMPIRE] Colony " + t.name + " has been built!");
                return;
            }
        }
        if (++t.tries >= 3) {
            Tribe p = data.tribes.get(t.parent);
            if (p != null) p.pop += Math.max(0, t.pop - 1);
            removeTribe(data, t);
        }
    }

    public static void spawnMember(ServerLevel level, Tribe t, int role) {
        RandomSource r = level.random;
        int x = t.center.getX() + r.nextInt(9) - 4;
        int z = t.center.getZ() + r.nextInt(9) - 4;
        int y = groundY(level, x, z);
        TribesmanEntity e = new TribesmanEntity(TribesMod.TRIBESMAN, level);
        e.moveTo(x + 0.5, y, z + 0.5, r.nextFloat() * 360f, 0f);
        e.setupTribe(t, role);
        level.addFreshEntity(e);
    }

    // ================= special buildings =================
    static void specials(ServerLevel level, Tribe t) {
        BlockPos lo = t.center.offset(-12, 0, -12), hi = t.center.offset(12, 0, 12);
        if (TribeConfig.BUILD_PORTAL && t.stage >= 3 && !t.portalBuilt && level.hasChunksAt(lo, hi)) {
            buildPortal(level, t);
            t.portalBuilt = true;
        }
        if (t.stage >= 6 && !t.monument && level.hasChunksAt(lo, hi)) {
            buildMonument(level, t);
            t.monument = true;
        }
    }

    static void buildPortal(ServerLevel level, Tribe t) {
        BlockPos o = t.center.offset(3, 0, 5);
        BlockState obs = Blocks.OBSIDIAN.defaultBlockState();
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X);
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                boolean edge = dx == 0 || dx == 3 || dy == 0 || dy == 4;
                if (edge) level.setBlock(o.offset(dx, dy, 0), obs, 3);
            }
        }
        for (int dx = 1; dx < 3; dx++) {
            for (int dy = 1; dy < 4; dy++) level.setBlock(o.offset(dx, dy, 0), portal, 18);
        }
    }

    static void buildMonument(ServerLevel level, Tribe t) {
        BlockPos b = t.center.offset(-5, 0, -5);
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) level.setBlock(b.offset(dx, 0, dz), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        }
        for (int dy = 1; dy <= 4; dy++) level.setBlock(b.offset(1, dy, 1), Blocks.OBSIDIAN.defaultBlockState(), 3);
        level.setBlock(b.offset(1, 5, 1), Blocks.DRAGON_HEAD.defaultBlockState(), 3);
    }

    // ================= building =================
    static void buildSlot(ServerLevel level, Tribe t) {
        int i = t.slots++;
        double ang = Math.toRadians(i * 60.0 + (i / 6) * 30.0);
        int r = 11 + 7 * (i / 6);
        int cx = t.center.getX() + (int) Math.round(Math.cos(ang) * r);
        int cz = t.center.getZ() + (int) Math.round(Math.sin(ang) * r);
        if (TribeMine.blocks(t, cx, cz)) return; // keep the mining quarter free
        int y = groundY(level, cx, cz);
        BlockPos o = new BlockPos(cx - 2, y, cz - 2);
        flatten(level, o, 5, 5);
        if (i % 6 == 5) buildTower(level, o);
        else if (i % 2 == 0) buildHut(level, o);
        else buildFarm(level, o);
    }

    static void flatten(ServerLevel level, BlockPos o, int w, int d) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    BlockPos p = o.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
                BlockPos g = o.offset(dx, -1, dz);
                BlockState cur = level.getBlockState(g);
                if (!(cur.is(BlockTags.DIRT) || cur.is(BlockTags.SAND))) {
                    level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                for (int dy = -2; dy >= -6; dy--) {
                    BlockPos q = o.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(q);
                    if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
                        level.setBlock(q, Blocks.DIRT.defaultBlockState(), 2);
                    } else break;
                }
            }
        }
    }

    static void buildHut(ServerLevel level, BlockPos o) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                level.setBlock(o.offset(dx, -1, dz), plank, 3);
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                if (edge) {
                    for (int dy = 0; dy < 3; dy++) {
                        BlockState s = corner ? cobble : plank;
                        if (dy == 1 && !corner && ((dz == 2 && (dx == 0 || dx == 4)) || (dx == 2 && dz == 4))) s = Blocks.GLASS_PANE.defaultBlockState();
                        if (dx == 2 && dz == 0 && dy < 2) s = Blocks.AIR.defaultBlockState();
                        level.setBlock(o.offset(dx, dy, dz), s, 3);
                    }
                }
                level.setBlock(o.offset(dx, 3, dz), plank, 3);
            }
        }
        level.setBlock(o.offset(2, 0, 2), Blocks.LANTERN.defaultBlockState(), 3);
        level.setBlock(o.offset(1, 0, 3), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        level.setBlock(o.offset(3, 0, 3), Blocks.BARREL.defaultBlockState(), 3);
    }

    /** Stone watchtower: every 6th building. */
    static void buildTower(ServerLevel level, BlockPos o) {
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                level.setBlock(o.offset(dx, -1, dz), brick, 3);
                if (edge) {
                    for (int dy = 0; dy < 8; dy++) {
                        BlockState s = brick;
                        if (dx == 2 && dz == 0 && dy < 2) s = air;
                        else if (dy == 4 && ((dz == 2 && (dx == 0 || dx == 4)) || (dx == 2 && dz == 4))) s = pane;
                        level.setBlock(o.offset(dx, dy, dz), s, 3);
                    }
                    if ((dx + dz) % 2 == 0) level.setBlock(o.offset(dx, 8, dz), brick, 3);
                } else {
                    level.setBlock(o.offset(dx, 3, dz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
                    level.setBlock(o.offset(dx, 7, dz), brick, 3);
                }
            }
        }
        level.setBlock(o.offset(2, 0, 2), Blocks.LANTERN.defaultBlockState(), 3);
        level.setBlock(o.offset(2, 8, 2), Blocks.LANTERN.defaultBlockState(), 3);
    }

    static void buildFarm(ServerLevel level, BlockPos o) {
        RandomSource rnd = level.random;
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                BlockPos g = o.offset(dx, -1, dz);
                if (dx == 2 && dz == 2) {
                    level.setBlock(g, Blocks.WATER.defaultBlockState(), 3);
                } else {
                    level.setBlock(g, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 3);
                    level.setBlock(g.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, rnd.nextInt(8)), 3);
                }
            }
        }
    }
}
