package com.tribesmod;

/** All the knobs in one place. Change a value, rebuild, done. */
public final class TribeConfig {
    private TribeConfig() {}

    /** Global growth speed. 1.0 = normal, 2.0 = twice as fast, 0.5 = half. */
    public static final double GROWTH = 1.0;

    /** Maximum number of tribes (colonies and vassals included). */
    public static final int MAX_TRIBES = 100;

    /** Minimum distance between naturally founded tribes. */
    public static final int MIN_DIST = 150;

    /** Maximum number of physical tribesmen per tribe (the rest of the population is simulated). */
    public static final int MAX_ENTITIES = 40;

    /** When a tribe slays the dragon for the first time, really remove the Ender Dragon from the End. */
    public static final boolean REAL_DRAGON_KILL = true;

    /** Tribes build a real Nether portal once they reach the Nether stage. */
    public static final boolean BUILD_PORTAL = true;

    public static final String[] STAGES = {
            "Stone Age",
            "Iron Age",
            "Diamond Age",
            "Nether Campaign",
            "Stronghold Quest",
            "End Siege",
            "EMPIRE (Dragon Slayers)"
    };
}
