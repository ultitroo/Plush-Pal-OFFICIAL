package com.plushpal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/** Opens a screen. Minecraft renamed setScreen -> setScreenAndShow around 26.2, so try both by name. */
public final class ScreenUtil {
    public static void open(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (tryOn(mc, mc, screen)) return;
            try {
                Object gui = Minecraft.class.getField("gui").get(mc); // some versions keep it on Gui
                if (tryOn(gui, mc, screen)) return;
            } catch (Throwable ignored) { }
            PlushPalClient.LOGGER.error("PlushPal: could not find a method to open a screen");
        });
    }

    private static boolean tryOn(Object target, Minecraft mc, Screen screen) {
        for (String name : new String[]{"setScreenAndShow", "setScreen"}) {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(screen.getClass())) {
                    try { m.invoke(target, screen); return true; }
                    catch (Throwable t) { PlushPalClient.LOGGER.error("PlushPal: {} failed", name, t); }
                }
            }
        }
        return false;
    }
}
