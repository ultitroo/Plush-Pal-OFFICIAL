package com.plushpal;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shown once, the first time you are in a world with the mod installed: pick the Emote wheel and Quick emote keys.
 * Same window style as the settings (see UnsavedChangesScreen). Keys are captured from the screen's own key event
 * (letters, digits, F-keys, ...), with PlatformUtil.keyDown polling kept as a fallback.
 * Skip / Done / ESC all count as "seen", so it never comes back.
 */
public final class KeybindSetupScreen extends Screen {
    private static final int BODY = 0xFF313233, LINE = 0xFF5B5B5C, TITLE = 0xFFFFFFFF, DESC = 0xFFB8B8B8,
            FACE = 0xFFD0D1D4, FACE_HI = 0xFFEDEEF0, FACE_SHADOW = 0xFF58585A, DARK = 0xFF1E1E1F, WHITE = 0xFFFFFFFF,
            GREEN = 0xFF3C8527, GREEN_HI = 0xFF5FB04A, GREEN_SHADOW = 0xFF1D4D13;

    private static final double T = 0.2;           // seconds for the dialog to dissolve in / out
    private static final float SHELL_END = 0.45f;   // panels first, then the contents (same as the settings window)
    private static final int FIRST_KEY = 32, LAST_KEY = 348, KEY_ESCAPE = 256, KEY_BACKSPACE = 259, KEY_DELETE = 261;

    private static boolean checkedThisSession;

    private final long openNs = System.nanoTime();
    private long closeNs;                           // != 0 while the dialog dissolves away
    private boolean closeFired;
    private HitButton wheelBtn, quickBtn, skipBtn, doneBtn;
    private int px, py, pw, ph;
    private int listening = -1;                     // -1 = none, 0 = emote wheel, 1 = quick emote
    private final boolean[] prevDown = new boolean[LAST_KEY + 1];
    private String note = "";

    KeybindSetupScreen() {
        super(Component.literal("Choose your keybinds"));
        UiDraw.resetAnimations();
    }

    // ---- first-run tracking

    private static Path marker() {
        return FabricLoader.getInstance().getConfigDir().resolve("plushpal_keybinds_setup");
    }

    private static void markDone() {
        try { Files.writeString(marker(), "done"); } catch (Throwable ignored) { }
    }

    /** Called every client tick; opens the screen once, the first time the player is in a world with no menu open. */
    static void tick(Minecraft mc) {
        if (checkedThisSession || mc.player == null || mc.level == null) return;
        try { if (!mc.mouseHandler.isMouseGrabbed()) return; } catch (Throwable t) { return; }   // a menu is open: wait
        checkedThisSession = true;
        if (!Files.exists(marker())) ScreenUtil.open(new KeybindSetupScreen());
    }

    // ---- layout

    @Override
    protected void init() {
        pw = Math.min(width - 20, 310);
        ph = 164;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int keyW = Math.min(120, pw - 32 - 90), keyX = px + pw - 16 - keyW;
        wheelBtn = new HitButton(keyX, py + 56, keyW, 22, "select", b -> toggleListen(0));
        quickBtn = new HitButton(keyX, py + 82, keyW, 22, "select", b -> toggleListen(1));
        int gap = 6, bw = (pw - 32 - gap) / 2, by = py + ph - 32;
        skipBtn = new HitButton(px + 16, by, bw, 22, "press", b -> leave());
        doneBtn = new HitButton(px + 16 + bw + gap, by, bw, 22, "press", b -> leave());
        addRenderableWidget(wheelBtn);
        addRenderableWidget(quickBtn);
        addRenderableWidget(skipBtn);
        addRenderableWidget(doneBtn);
    }

    private KeyMapping mapping(int which) {
        return which == 0 ? PlushPalClient.wheelKey : PlushPalClient.emoteKey;
    }

    private String keyName(int which) {
        KeyMapping m = mapping(which);
        if (m == null || m.isUnbound()) return "Not bound";
        return m.getTranslatedKeyMessage().getString();
    }

    // ---- key capture

    private void toggleListen(int which) {
        note = "";
        if (listening == which) { listening = -1; return; }
        listening = which;
        for (int k = FIRST_KEY; k <= LAST_KEY; k++) prevDown[k] = PlatformUtil.keyDown(k);   // ignore keys already held
    }

    /** The screen's key event: while waiting for a key, whatever is pressed (other than ESC) becomes the binding. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening < 0) return super.keyPressed(event);
        int key = event.key();
        if (key == KEY_ESCAPE) return super.keyPressed(event);   // onClose() cancels the capture
        capture(key);
        return true;
    }

    private void capture(int key) {
        assign(listening, key == KEY_BACKSPACE || key == KEY_DELETE ? -1 : key);
        listening = -1;
        UiSounds.play("press", 1f);
    }

    /** Fallback while waiting for a key: the first key that goes down (other than ESC) is the new binding. */
    private void pollKeys() {
        if (listening < 0) return;
        for (int k = FIRST_KEY; k <= LAST_KEY; k++) {
            boolean down = PlatformUtil.keyDown(k);
            boolean rising = down && !prevDown[k];
            prevDown[k] = down;
            if (!rising || k == KEY_ESCAPE) continue;
            capture(k);
            return;
        }
    }

    private void assign(int which, int code) {
        KeyMapping target = mapping(which);
        if (target == null) return;
        note = "";
        InputConstants.Key key = code < 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYBOARD.getOrCreate(code);
        KeyMapping other = mapping(1 - which);
        if (code >= 0 && other != null && !other.isUnbound() && other.saveString().equals(key.getName())) {
            other.setKey(InputConstants.UNKNOWN);
            note = (which == 0 ? "Quick emote" : "Emote wheel") + " was using that key, so it's now unbound.";
        }
        target.setKey(key);
        KeyMapping.resetMapping();
    }

    // ---- closing

    /** Saves the keys, marks the screen as seen, and dissolves away (instantly when UI animations are off). */
    private void leave() {
        if (closeNs != 0) return;
        markDone();
        try { minecraft.options.save(); } catch (Throwable ignored) { }
        if (!PlushConfig.get().uiAnimations) { super.onClose(); return; }
        closeNs = System.nanoTime();
    }

    /** ESC = cancel the key you were choosing, otherwise skip. */
    @Override
    public void onClose() {
        if (closeNs != 0) return;
        UiSounds.play("back", 1f);
        if (listening >= 0) { listening = -1; note = ""; return; }
        leave();
    }

    // ---- render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiDraw.beginFrame();
        pollKeys();
        float vis = closeNs != 0 ? 1f - UiDraw.progress(closeNs, T) : UiDraw.progress(openNs, T);   // 0 = gone, 1 = fully there
        float shell = UiDraw.ease(Math.min(1f, vis / SHELL_END));
        float cover = vis <= SHELL_END ? 1f : 1f - UiDraw.ease((vis - SHELL_END) / (1f - SHELL_END));
        g.fill(0, 0, width, height, UiDraw.withAlpha(0xAA000000, closeNs != 0 ? UiDraw.ease(vis) : 1f));
        UiDraw.ring(g, px - 2, py - 2, px + pw + 2, py + ph + 2, 2, UiDraw.withAlpha(0xFF000000, shell));
        g.fill(px, py, px + pw, py + ph, UiDraw.withAlpha(BODY, shell));
        if (vis < SHELL_END) { finishLeave(); return; }
        g.fill(px, py, px + pw, py + 2, LINE);

        Component title = Component.literal("Choose your keybinds").withStyle(ChatFormatting.BOLD);
        g.text(minecraft.font, title, px + (pw - minecraft.font.width(title)) / 2, py + 14, TITLE, false);
        String sub = "You can change these later in Controls.";
        g.text(minecraft.font, sub, px + (pw - minecraft.font.width(sub)) / 2, py + 30, DESC, false);

        g.text(minecraft.font, "Emote wheel", px + 16, py + 56 + 7, WHITE, false);
        g.text(minecraft.font, "Quick emote", px + 16, py + 82 + 7, WHITE, false);

        String hint = !note.isEmpty() ? note
                : listening >= 0 ? "Press a key  -  Backspace to unbind  -  Esc to cancel"
                : "Click a key to change it.";
        g.text(minecraft.font, hint, px + (pw - minecraft.font.width(hint)) / 2, py + 112, DESC, false);

        super.extractRenderState(g, mouseX, mouseY, delta); // invisible buttons (input only)

        keyButton(g, wheelBtn, 0);
        keyButton(g, quickBtn, 1);
        UiDraw.button(g, minecraft.font, skipBtn.getX(), skipBtn.getY(), skipBtn.getWidth(), skipBtn.getHeight(), "Skip", FACE, FACE_HI, FACE_SHADOW, DARK, false, skipBtn.isHovered());
        UiDraw.button(g, minecraft.font, doneBtn.getX(), doneBtn.getY(), doneBtn.getWidth(), doneBtn.getHeight(), "Done", GREEN, GREEN_HI, GREEN_SHADOW, WHITE, true, doneBtn.isHovered());

        if (cover > 0f) g.fill(px, py, px + pw, py + ph, UiDraw.withAlpha(BODY, cover));   // contents fade into the panel colour
        finishLeave();
    }

    private void keyButton(GuiGraphicsExtractor g, HitButton b, int which) {
        boolean active = listening == which;
        String text = active ? "> Press a key <" : keyName(which);
        if (active) UiDraw.button(g, minecraft.font, b.getX(), b.getY(), b.getWidth(), b.getHeight(), text, GREEN, GREEN_HI, GREEN_SHADOW, WHITE, true, b.isHovered());
        else UiDraw.button(g, minecraft.font, b.getX(), b.getY(), b.getWidth(), b.getHeight(), text, FACE, FACE_HI, FACE_SHADOW, DARK, false, b.isHovered());
    }

    private void finishLeave() {
        if (closeNs == 0 || closeFired || UiDraw.progress(closeNs, T) < 1f) return;
        closeFired = true;
        minecraft.execute(super::onClose);   // after this frame is drawn
    }
}
