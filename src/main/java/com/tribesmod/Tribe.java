package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class Tribe {
    public static final String[] TRAITS = {"Warlike", "Merchant", "Industrious"};

    public int id;
    public String name = "Tribe";
    public BlockPos center = BlockPos.ZERO;
    public int food = 20, wood = 0, wealth = 0, level = 1, pop = 4, slots = 0;
    public int stone = 0, iron = 0, gold = 0, diamond = 0, blaze = 0, pearls = 0;
    /** stage: 0 stone, 1 iron, 2 diamond, 3 nether, 4 stronghold, 5 end siege, 6 empire */
    public int stage = 0, parent = 0, trait = 0, cool = 0, tries = 0;
    public boolean founded = true, portalBuilt = false, monument = false;
    public int[] mine = new int[TribeMine.SIZE];
    public Set<Integer> enemies = new HashSet<>();

    public int maxLevel() { return 8 + 2 * stage; }
    public int popCap() { return 8 + 6 * level; }
    public int entityCap() { return Math.min(TribeConfig.MAX_ENTITIES, 12 + 2 * level); }
    public int territory() { return 30 + 3 * level; }
    public int tier() { return stage >= 3 ? 3 : stage >= 2 ? 2 : stage >= 1 ? 1 : 0; }

    public double strength() {
        double s = pop + level * 3 + wealth / 40.0 + stage * 10;
        if (trait == 0) s *= 1.15;
        return s;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("name", name);
        t.putInt("x", center.getX());
        t.putInt("y", center.getY());
        t.putInt("z", center.getZ());
        t.putInt("food", food);
        t.putInt("wood", wood);
        t.putInt("wealth", wealth);
        t.putInt("level", level);
        t.putInt("pop", pop);
        t.putInt("slots", slots);
        t.putInt("stone", stone);
        t.putInt("iron", iron);
        t.putInt("gold", gold);
        t.putInt("diamond", diamond);
        t.putInt("blaze", blaze);
        t.putInt("pearls", pearls);
        t.putInt("stage", stage);
        t.putInt("parent", parent);
        t.putInt("trait", trait);
        t.putInt("cool", cool);
        t.putInt("tries", tries);
        t.putBoolean("founded", founded);
        t.putBoolean("portal", portalBuilt);
        t.putBoolean("monument", monument);
        t.putIntArray("mine", mine);
        t.putIntArray("enemies", new ArrayList<>(enemies));
        return t;
    }

    public static Tribe load(CompoundTag t) {
        Tribe r = new Tribe();
        r.id = t.getInt("id");
        r.name = t.getString("name");
        r.center = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
        r.food = t.getInt("food");
        r.wood = t.getInt("wood");
        r.wealth = t.getInt("wealth");
        r.level = Math.max(1, t.getInt("level"));
        r.pop = t.getInt("pop");
        r.slots = t.getInt("slots");
        r.stone = t.getInt("stone");
        r.iron = t.getInt("iron");
        r.gold = t.getInt("gold");
        r.diamond = t.getInt("diamond");
        r.blaze = t.getInt("blaze");
        r.pearls = t.getInt("pearls");
        r.stage = t.getInt("stage");
        r.parent = t.getInt("parent");
        r.trait = t.contains("trait") ? t.getInt("trait") : Math.floorMod(r.id, 3);
        r.cool = t.getInt("cool");
        r.tries = t.getInt("tries");
        r.founded = !t.contains("founded") || t.getBoolean("founded");
        r.portalBuilt = t.getBoolean("portal");
        r.monument = t.getBoolean("monument");
        int[] m = t.getIntArray("mine");
        if (m.length == TribeMine.SIZE) r.mine = m;
        for (int e : t.getIntArray("enemies")) r.enemies.add(e);
        return r;
    }
}
