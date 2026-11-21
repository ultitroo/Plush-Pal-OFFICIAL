package com.plushpal;

import net.fabricmc.loader.api.FabricLoader;

/** Detects other mods that change how held items are drawn. */
public final class Compat {
    /** Punchy (first-person animations). It draws the in-hand item itself, often with a different display context. */
    public static final boolean PUNCHY = FabricLoader.getInstance().isModLoaded("punchy");

    /** Always on whenever Punchy is loaded (there is no setting for it any more). */
    public static boolean punchyActive() { return PUNCHY; }
}
