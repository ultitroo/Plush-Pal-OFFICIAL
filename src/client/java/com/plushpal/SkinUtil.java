package com.plushpal;

import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;

/** Reads "is this skin slim (Alex)?" from a PlayerSkin without depending on the model enum's exact name. */
public final class SkinUtil {
    private static Method modelMethod;
    private static boolean failed;

    public static boolean isSlim(Object playerSkin) {
        if (failed || playerSkin == null) return false;
        try {
            if (modelMethod == null) modelMethod = playerSkin.getClass().getMethod("model");
            Object model = modelMethod.invoke(playerSkin);
            return model != null && model.toString().toUpperCase().contains("SLIM");
        } catch (Throwable t) {
            failed = true;
            PlushPalClient.LOGGER.warn("PlushPal: could not read the skin model type; slim skins will use the normal arms");
            return false;
        }
    }

    /** The player's cape texture (a PlayerSkin's cape().texturePath()), or null if they have no cape. */
    public static Identifier cape(Object playerSkin) {
        try {
            Object asset = playerSkin.getClass().getMethod("cape").invoke(playerSkin);
            if (asset == null) return null;
            for (Method m : asset.getClass().getMethods()) {
                if (m.getName().equals("texturePath") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m.invoke(asset) instanceof Identifier id ? id : null;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
