package com.plushpal;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Menu sounds: hover, select, back, scroll (assets/plushpal/sounds/ui). Registered in sounds.json as ui.<name>. */
public final class UiSounds {
    public static void play(String name, float pitch) {
        PlushConfig c = PlushConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (!c.uiSounds) return;
        SoundEvent ev = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(PlushPalClient.ID, "ui." + name));
        if (mc.level == null || mc.player == null) { // main menu (e.g. opened from Mod Menu): no world to play in
            playWithoutWorld(mc, ev, pitch, (float) c.uiVolume);
            return;
        }
        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), ev, SoundSource.MASTER,
                (float) c.uiVolume, pitch, false);
    }

    /** Settings-page test: the plush equip sound at the current volume and pitch (plays even if the plush sound is switched off). */
    public static void testPlush() {
        PlushConfig c = PlushConfig.get();
        playTest(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(PlushPalClient.ID, "plush")), (float) c.sound.pitch, (float) c.sound.volume, SoundSource.PLAYERS);
    }

    /** Settings-page test: one menu sound at the current menu volume (plays even if menu sounds are switched off). */
    public static void testMenu(String name) {
        PlushConfig c = PlushConfig.get();
        playTest(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(PlushPalClient.ID, "ui." + name)), 1f, (float) c.uiVolume, SoundSource.MASTER);
    }

    private static void playTest(SoundEvent ev, float pitch, float volume, SoundSource source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { playWithoutWorld(mc, ev, pitch, volume); return; }
        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), ev, source, volume, pitch, false);
    }

    /** SimpleSoundInstance.forUI(event, pitch, volume) -> SoundManager.play(...), looked up by name. */
    private static void playWithoutWorld(Minecraft mc, SoundEvent ev, float pitch, float volume) {
        try {
            Class<?> simple = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
            Object instance = simple.getMethod("forUI", SoundEvent.class, float.class, float.class).invoke(null, ev, pitch, volume);
            Object manager = mc.getSoundManager();
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("play") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(instance)) {
                    m.invoke(manager, instance);
                    return;
                }
            }
        } catch (Throwable ignored) { /* silent: menu sounds are optional */ }
    }
}
