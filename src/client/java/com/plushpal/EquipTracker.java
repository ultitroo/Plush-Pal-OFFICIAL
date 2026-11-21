package com.plushpal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;

/** Watches the local player once per client tick: every real equip bumps {@link #serial}, every damage hit {@link #hurtSerial}. */
public final class EquipTracker {
    public static volatile int serial = 0;
    /** Last time (seconds, System.nanoTime clock) the local player held a totem in either hand. */
    public static volatile double lastHeld = -1e9;
    private static int prevSig = -1;

    /** Bumped whenever the local player's health drops (damage); {@link #hurtTime} is when (nanoTime clock, seconds). */
    public static volatile int hurtSerial = 0;
    public static volatile double hurtTime = -1e9;
    private static float lastHealth = -1;

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { prevSig = -1; lastHealth = -1; return; }
        boolean main = p.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        boolean off = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        if (main || off) lastHeld = System.nanoTime() / 1e9;
        int sig = (main ? 1 + p.getInventory().getSelectedSlot() : 0) * 2 + (off ? 1 : 0);
        if (prevSig != -1 && sig != 0 && sig != prevSig) serial++; // not on join, not when putting it away
        float hp = p.getHealth();
        if (lastHealth >= 0 && hp < lastHealth - 0.01f) { hurtSerial++; hurtTime = System.nanoTime() / 1e9; } // took damage
        lastHealth = hp;
        prevSig = sig;
    }
}
