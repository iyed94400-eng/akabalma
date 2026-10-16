package com.susano;

/** Une couleur de Susanoo par Mangekyo. */
public enum SusanooColor {
    //            id  objet               coeur (sombre)       lueur (vive)
    MADARA(0, "mangekyo",         new int[]{15, 25, 70},  new int[]{60, 150, 255}),
    ITACHI(1, "mangekyo_itachi",  new int[]{95, 10, 10},  new int[]{255, 70, 40}),
    SASUKE(2, "mangekyo_sasuke",  new int[]{50, 15, 90},  new int[]{170, 90, 255}),
    SHISUI(3, "mangekyo_shisui",  new int[]{8, 70, 40},   new int[]{70, 255, 140}),
    OBITO(4, "mangekyo_obito",    new int[]{110, 55, 5},  new int[]{255, 170, 40});

    public final int id;
    public final String itemName;
    public final int[] core;
    public final int[] glow;

    SusanooColor(int id, String itemName, int[] core, int[] glow) {
        this.id = id;
        this.itemName = itemName;
        this.core = core;
        this.glow = glow;
    }

    public static SusanooColor byId(int id) {
        SusanooColor[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
