package com.plushpal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Are you sure you don't want to save?" - shown when you leave the settings with unsaved changes. */
public final class UnsavedChangesScreen extends Screen {
    private static final int BODY = 0xFF313233, LINE = 0xFF5B5B5C, TITLE = 0xFFFFFFFF, DESC = 0xFFB8B8B8,
            FACE = 0xFFD0D1D4, FACE_HI = 0xFFEDEEF0, FACE_SHADOW = 0xFF58585A, DARK = 0xFF1E1E1F, WHITE = 0xFFFFFFFF,
            GREEN = 0xFF3C8527, GREEN_HI = 0xFF5FB04A, GREEN_SHADOW = 0xFF1D4D13,
            RED = 0xFFB04040, RED_HI = 0xFFD06060, RED_SHADOW = 0xFF6A2020;

    private final PlushConfigScreen config;
    private static final double T = 0.2;           // seconds for the dialog to dissolve in / out
    private static final float SHELL_END = 0.45f;   // panels first, then the contents (same as the settings window)
    private final long openNs = System.nanoTime();
    private long closeNs;                           // != 0 while the dialog dissolves away
    private boolean closeFired, holdDim;
    private Runnable after;
    private HitButton keep, discard, save;
    private int px, py, pw, ph;

    UnsavedChangesScreen(PlushConfigScreen config) {
        super(Component.literal("Unsaved changes"));
        this.config = config;
        UiDraw.resetAnimations();
    }

    @Override
    protected void init() {
        pw = Math.min(width - 20, 310);
        ph = 94;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int gap = 6, bw = (pw - 32 - 2 * gap) / 3, by = py + ph - 32, bx = px + 16;
        keep = new HitButton(bx, by, bw, 22, "select", b -> keepEditing());
        discard = new HitButton(bx + bw + gap, by, bw, 22, "press", b -> leave(() -> { PlushConfig.discardChanges(); config.exit(); }, false));
        save = new HitButton(bx + 2 * (bw + gap), by, bw, 22, "press", b -> leave(() -> { PlushConfig.save(); config.exit(); }, false));
        addRenderableWidget(keep);
        addRenderableWidget(discard);
        addRenderableWidget(save);
    }

    /** Back to the settings window (it dissolves in again; the dim behind stays as it is). */
    private void keepEditing() {
        leave(() -> { config.replayOpen(); ScreenUtil.open(config); }, true);
    }

    /** The dialog dissolves away (instantly when UI animations are off), then {@code action} runs. */
    private void leave(Runnable action, boolean keepDim) {
        if (closeNs != 0) return;
        if (!PlushConfig.get().uiAnimations) { action.run(); return; }
        after = action; holdDim = keepDim; closeNs = System.nanoTime();
    }

    /** ESC = keep editing. */
    @Override
    public void onClose() {
        if (closeNs != 0) return;
        UiSounds.play("back", 1f);
        keepEditing();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiDraw.beginFrame();
        float vis = closeNs != 0 ? 1f - UiDraw.progress(closeNs, T) : UiDraw.progress(openNs, T);   // 0 = gone, 1 = fully there
        float shell = UiDraw.ease(Math.min(1f, vis / SHELL_END));
        float cover = vis <= SHELL_END ? 1f : 1f - UiDraw.ease((vis - SHELL_END) / (1f - SHELL_END));
        g.fill(0, 0, width, height, UiDraw.withAlpha(0xAA000000, closeNs != 0 && !holdDim ? UiDraw.ease(vis) : 1f));
        UiDraw.ring(g, px - 2, py - 2, px + pw + 2, py + ph + 2, 2, UiDraw.withAlpha(0xFF000000, shell));   // the window's own thin outline
        g.fill(px, py, px + pw, py + ph, UiDraw.withAlpha(BODY, shell));
        if (vis < SHELL_END) { finishLeave(); return; }
        g.fill(px, py, px + pw, py + 2, LINE);

        Component title = Component.literal("Are you sure you don't want to save?").withStyle(ChatFormatting.BOLD);
        g.text(minecraft.font, title, px + (pw - minecraft.font.width(title)) / 2, py + 16, TITLE, false);
        String sub = "Your changes will be lost.";
        g.text(minecraft.font, sub, px + (pw - minecraft.font.width(sub)) / 2, py + 32, DESC, false);

        super.extractRenderState(g, mouseX, mouseY, delta); // invisible buttons (input only)

        UiDraw.button(g, minecraft.font, keep.getX(), keep.getY(), keep.getWidth(), keep.getHeight(), "Keep editing", FACE, FACE_HI, FACE_SHADOW, DARK, false, keep.isHovered());
        UiDraw.button(g, minecraft.font, discard.getX(), discard.getY(), discard.getWidth(), discard.getHeight(), "Don't save", RED, RED_HI, RED_SHADOW, WHITE, false, discard.isHovered());
        UiDraw.button(g, minecraft.font, save.getX(), save.getY(), save.getWidth(), save.getHeight(), "Save", GREEN, GREEN_HI, GREEN_SHADOW, WHITE, true, save.isHovered());

        if (cover > 0f) g.fill(px, py, px + pw, py + ph, UiDraw.withAlpha(BODY, cover));   // contents fade into the panel colour (no black cover)
        finishLeave();
    }

    private void finishLeave() {
        if (closeNs == 0 || closeFired || UiDraw.progress(closeNs, T) < 1f) return;
        closeFired = true;
        Runnable r = after;
        if (r != null) minecraft.execute(r);   // after this frame is drawn
    }
}
