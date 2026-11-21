package com.plushpal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws PNG icons from assets/plushpal/textures/gui/. Edit the PNGs (any square size) or override them from a
 * resource pack; F3+T reloads them. Names: icons/general, icons/scale, icons/layout, icons/animations,
 * icons/emotes, icons/sound, icons/skin, emotes/hi, emotes/yay, emotes/clap.
 */
public final class Icons {
    private static final Map<String, int[]> SIZES = new HashMap<>();
    private static final Map<String, Boolean> EXISTS = new HashMap<>();

    public static void clearCache() { SIZES.clear(); EXISTS.clear(); }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/gui/" + name + ".png");
    }

    /** Is there a PNG at textures/gui/{name}.png (in the mod or any resource pack)? Cached until the next reload. */
    public static boolean exists(String name) {
        return EXISTS.computeIfAbsent(name, k -> Minecraft.getInstance().getResourceManager().getResource(id(k)).isPresent());
    }

    /** {width, height} in pixels of textures/gui/{name}.png. */
    public static int[] sizeOf(String name) {
        return SIZES.computeIfAbsent(name, k -> readSize(id(k)));
    }

    /** Draws {@code name} scaled to size x size pixels at (x, y). */
    public static void draw(GuiGraphicsExtractor g, String name, int x, int y, int size) {
        drawRect(g, name, x, y, size, size);
    }

    /** Draws {@code name} scaled to w x h pixels at (x, y) (for non-square textures like the wheel hexagons). */
    public static void drawRect(GuiGraphicsExtractor g, String name, int x, int y, int w, int h) {
        Identifier tex = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/gui/" + name + ".png");
        int[] dim = SIZES.computeIfAbsent(name, k -> readSize(tex));
        // texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, dim[0], dim[1], dim[0], dim[1]);
    }

    /** Draws the source region (u, v, rw x rh) of {@code name} into the dest rectangle (x, y, w x h); nearest-neighbour scaled. */
    public static void region(GuiGraphicsExtractor g, String name, int x, int y, int w, int h, int u, int v, int rw, int rh) {
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return;
        Identifier tex = id(name);
        int[] dim = SIZES.computeIfAbsent(name, k -> readSize(tex));
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, rw, rh, dim[0], dim[1]);
    }

    /** Stretches {@code name} over (x, y, w x h) keeping its {@code b}-pixel corners and edges unscaled (a 9-slice). */
    public static void nineSlice(GuiGraphicsExtractor g, String name, int x, int y, int w, int h, int b) {
        int[] d = sizeOf(name);
        int tw = d[0], th = d[1], mw = tw - 2 * b, mh = th - 2 * b, cw = w - 2 * b, ch = h - 2 * b;
        if (cw < 0 || ch < 0) { region(g, name, x, y, w, h, 0, 0, tw, th); return; }
        region(g, name, x, y, b, b, 0, 0, b, b);
        region(g, name, x + b, y, cw, b, b, 0, mw, b);
        region(g, name, x + b + cw, y, b, b, tw - b, 0, b, b);
        region(g, name, x, y + b, b, ch, 0, b, b, mh);
        region(g, name, x + b, y + b, cw, ch, b, b, mw, mh);
        region(g, name, x + b + cw, y + b, b, ch, tw - b, b, b, mh);
        region(g, name, x, y + b + ch, b, b, 0, th - b, b, b);
        region(g, name, x + b, y + b + ch, cw, b, b, th - b, mw, b);
        region(g, name, x + b + cw, y + b + ch, b, b, tw - b, th - b, b, b);
    }

    /** PNG width/height come straight from the IHDR chunk (bytes 16-23). Falls back to 16x16. */
    private static int[] readSize(Identifier tex) {
        try (InputStream in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(tex).open()) {
            byte[] b = in.readNBytes(24);
            int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
            int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
            if (w > 0 && h > 0) return new int[]{w, h};
        } catch (Throwable ignored) { }
        return new int[]{16, 16};
    }
}
