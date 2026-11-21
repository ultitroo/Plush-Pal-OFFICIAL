package com.plushpal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/** Drawing + animation helpers shared by the Bedrock-style screens. */
public final class UiDraw {
    private static long lastNs = 0;
    /** Seconds since the last frame, scaled by the UI animation speed (huge when UI animations are off = snap). */
    public static float dt = 0.016f;
    private static final Map<Long, Float> HOVER = new HashMap<>();

    /** Call once at the start of every screen render. */
    public static void beginFrame() {
        long now = System.nanoTime();
        float d = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        boolean left = leftDown();
        if (!left) {
            armed = 0L;
            if (System.nanoTime() > clickUntil) { clickPending = false; clickKey = 0L; }
        }
        pressWindow = left && !leftPrev;      // this is the first frame of a new press: the control under the cursor owns it
        leftPrev = left;
        PlushConfig c = PlushConfig.get();
        dt = c.uiAnimations ? d * (float) c.uiAnimSpeed : 1000f;
    }

    public static void resetAnimations() { HOVER.clear(); PRESS.clear(); }

    private static final Map<Long, Float> PRESS = new HashMap<>();

    /** Eased 0..1 "pushed in" amount for a control: it follows the mouse button quickly but never snaps. */
    public static float press(long key, boolean down) {
        float cur = approach(PRESS.getOrDefault(key, 0f), down ? 1f : 0f, 32f);
        PRESS.put(key, cur);
        return cur;
    }

    // ---- "pressed" state: a control looks pushed in while the left mouse button is held down on it
    private static boolean leftPrev, pressWindow;
    private static boolean clickPending;
    private static long clickKey, clickUntil;

    /** Called by HitButton at the instant it is clicked. */
    public static void markClick() {
        clickPending = true;
        clickKey = 0L;
        clickUntil = System.nanoTime() + 110_000_000L;
    }

    private static long armed = 0L;           // key of the control the current press started on (0 = none)

    private static boolean leftDown() { return PlatformUtil.leftMouseDown(); }

    /** True while the mouse button is held down on this control (a press that began elsewhere never counts). */
    public static boolean pressed(long key, boolean hovered) {
        // a click that landed on one of our buttons: show it pushed in while held, and for at least ~110 ms so quick taps are visible
        if (hovered && clickPending && (leftPrev || System.nanoTime() < clickUntil)) {
            if (clickKey == 0L) clickKey = key | 1L;
            if (clickKey == (key | 1L)) return true;
        }
        if (hovered && pressWindow && armed == 0L) armed = key | 1L;
        return hovered && leftPrev && armed == (key | 1L);
    }

    /** Moves cur toward target, framerate independent. rate ~ 15-25 feels snappy. With animations off it snaps. */
    public static float approach(float cur, float target, float rate) {
        return cur + (target - cur) * (1f - (float) Math.exp(-rate * dt));
    }

    /** 0..1 progress of a timed animation that started at startNs (1 immediately if UI animations are off). */
    public static float progress(long startNs, double durationSec) {
        PlushConfig c = PlushConfig.get();
        if (!c.uiAnimations) return 1f;
        double elapsed = (System.nanoTime() - startNs) / 1e9 * c.uiAnimSpeed;
        return (float) Math.max(0, Math.min(1, elapsed / durationSec));
    }

    // ---- page fade: used when you switch tabs. One alpha fill over the page, so it costs next to nothing.
    /** Fades {@code color} over the rectangle: {@code cover} 0 = nothing, 1 = fully covered. */
    public static void dissolveOver(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float cover, int color) {
        if (x1 <= x0 || y1 <= y0 || cover <= 0.01f) return;
        g.fill(x0, y0, x1, y1, withAlpha(color, Math.min(1f, cover)));
    }

    /** A hollow rectangle {@code t} px thick, drawn inside the given bounds. */
    public static void ring(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int t, int color) {
        g.fill(x0, y0, x1, y0 + t, color);
        g.fill(x0, y1 - t, x1, y1, color);
        g.fill(x0, y0 + t, x0 + t, y1 - t, color);
        g.fill(x1 - t, y0 + t, x1, y1 - t, color);
    }

    public static float ease(float x) { float v = 1f - Math.max(0f, Math.min(1f, x)); return 1f - v * v * v; }

    public static long key(int x, int y, int w, int h) { return ((long) x << 48) ^ ((long) y << 32) ^ ((long) w << 16) ^ h; }

    /** 0..1 hover amount for a control, easing in and out. */
    public static float hover(long key, boolean hovered) {
        float cur = approach(HOVER.getOrDefault(key, 0f), hovered ? 1f : 0f, 22f);
        HOVER.put(key, cur);
        return cur;
    }

    public static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 18), g = Math.min(255, ((argb >> 8) & 0xFF) + 18), b = Math.min(255, (argb & 0xFF) + 18);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Bedrock-style hover: a real white outline (2 px) around the control, fading in and out with {@code alpha}. */
    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        if (alpha < 0.02f) return;
        int c = withAlpha(0xFFFFFFFF, alpha), t = 2;
        g.fill(x, y, x + w, y + t, c);
        g.fill(x, y + h - t, x + w, y + h, c);
        g.fill(x, y + t, x + t, y + h - t, c);
        g.fill(x + w - t, y + t, x + w, y + h - t, c);
    }

    /**
     * Bedrock button, drawn from textures/gui/widgets/button*.png (gray, green and red, each with a _hover version).
     * The art carries its own 1 px border, so it fills the button's rectangle exactly.
     */
    public static int button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String text,
                              int face, int hi, int shadow, int textColor, boolean bold, boolean hovered) {
        float a = hover(key(x, y, w, h), hovered);                       // hover: a soft brightening that fades in and out
        String variant = face == 0xFF3C8527 ? "_green" : face == 0xFFB04040 ? "_red" : "";
        float pr = press(key(x, y, w, h), pressed(key(x, y, w, h), hovered));   // press: eases in and out like the switch knob slides
        boolean down = pr > 0.5f;
        int sink = Math.round(pr * 2f);                  // pressed: the whole button shifts down 2 px (eased, so it animates)
        int ny = y + sink, nh = h;
        // three looks: plain (rest), "_hover" (mouse over), "_pressed" (slightly pushed in: thinner shadow, still raised)
        Icons.nineSlice(g, "widgets/button" + variant + (down ? "_pressed" : hovered ? "_hover" : ""), x, ny, w, nh, 3);   // rest: plain art, hover: _hover art, pressed: _pressed art
        if (pr > 0.01f) g.fill(x + 1, ny + 1, x + w - 1, ny + 3, withAlpha(0x30000000, pr));   // a soft shadow falls from the top edge
        Component label = Component.literal(text);
        if (bold) label = label.copy().withStyle(ChatFormatting.BOLD);
        g.text(font, label, x + (w - font.width(label)) / 2, ny + (nh - 3 - 8) / 2 + 1, textColor, false);
        return sink;
    }
}
