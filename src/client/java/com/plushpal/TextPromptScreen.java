package com.plushpal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** A small "type something" window (preset name, skin username). OK hands the text back and returns to the settings. */
final class TextPromptScreen extends Screen {
    private static final int BODY = 0xFF313233, LINE = 0xFF5B5B5C, TITLE = 0xFFFFFFFF, DESC = 0xFFB8B8B8,
            FACE = 0xFFD0D1D4, FACE_HI = 0xFFEDEEF0, FACE_SHADOW = 0xFF58585A, DARK = 0xFF1E1E1F, WHITE = 0xFFFFFFFF,
            GREEN = 0xFF3C8527, GREEN_HI = 0xFF5FB04A, GREEN_SHADOW = 0xFF1D4D13;

    private final Screen parent;
    private final String title, hint, initial;
    private final int maxLen;
    private final Consumer<String> onOk;
    private static final double T = 0.2;           // seconds for the dialog to dissolve in / out
    private static final float SHELL_END = 0.45f;   // panels first, then the contents (same as the settings window)
    private final long openNs = System.nanoTime();
    private long closeNs;                           // != 0 while the dialog dissolves away
    private boolean closeFired;
    private Runnable after;
    private EditBox box;
    private HitButton ok, cancel, paste;
    private int px, py, pw, ph;

    TextPromptScreen(Screen parent, String title, String hint, String initial, int maxLen, Consumer<String> onOk) {
        super(Component.literal(title));
        this.parent = parent; this.title = title; this.hint = hint; this.initial = initial; this.maxLen = maxLen; this.onOk = onOk;
        UiDraw.resetAnimations();
    }

    @Override
    protected void init() {
        pw = Math.min(width - 20, 320);
        ph = 112;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        box = new EditBox(minecraft.font, px + 16, py + 40, pw - 32, 20, Component.literal(title));
        box.setMaxLength(maxLen);
        box.setValue(initial == null ? "" : initial);
        addRenderableWidget(box);
        setInitialFocus(box);
        int gap = 6, bw = (pw - 32 - 2 * gap) / 3, by = py + ph - 30, bx = px + 16;
        paste = new HitButton(bx, by, bw, 22, "select", b -> box.setValue(PlatformUtil.clipboard().trim()));
        cancel = new HitButton(bx + bw + gap, by, bw, 22, "back", b -> onClose());
        ok = new HitButton(bx + 2 * (bw + gap), by, bw, 22, "press", b -> {
            String v = box.getValue().trim();
            if (v.isEmpty()) return;
            onOk.accept(v);
            backToParent();
        });
        addRenderableWidget(paste);
        addRenderableWidget(cancel);
        addRenderableWidget(ok);
    }

    /** The dialog dissolves away (instantly when UI animations are off), then the settings window dissolves back in. */
    private void backToParent() {
        if (closeNs != 0) return;
        Runnable action = () -> {
            if (parent instanceof PlushConfigScreen pc) pc.replayOpen();
            ScreenUtil.open(parent);
        };
        if (!PlushConfig.get().uiAnimations) { action.run(); return; }
        after = action; closeNs = System.nanoTime();
    }

    /** ESC / Cancel: back to the settings without doing anything. */
    @Override
    public void onClose() {
        backToParent();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiDraw.beginFrame();
        float vis = closeNs != 0 ? 1f - UiDraw.progress(closeNs, T) : UiDraw.progress(openNs, T);   // 0 = gone, 1 = fully there
        float shell = UiDraw.ease(Math.min(1f, vis / SHELL_END));
        float cover = vis <= SHELL_END ? 1f : 1f - UiDraw.ease((vis - SHELL_END) / (1f - SHELL_END));
        g.fill(0, 0, width, height, 0xAA000000);   // the dim behind stays steady: the settings window dissolves in over it again
        UiDraw.ring(g, px - 2, py - 2, px + pw + 2, py + ph + 2, 2, UiDraw.withAlpha(0xFF000000, shell));   // the window's own thin outline
        g.fill(px, py, px + pw, py + ph, UiDraw.withAlpha(BODY, shell));
        if (vis < SHELL_END) { finishLeave(); return; }
        g.fill(px, py, px + pw, py + 2, LINE);
        Component t = Component.literal(title).withStyle(ChatFormatting.BOLD);
        g.text(minecraft.font, t, px + 16, py + 12, TITLE, false);
        if (hint != null && !hint.isEmpty()) g.text(minecraft.font, hint, px + 16, py + 26, DESC, false);

        super.extractRenderState(g, mouseX, mouseY, delta); // the text box and the invisible buttons

        UiDraw.button(g, minecraft.font, paste.getX(), paste.getY(), paste.getWidth(), paste.getHeight(), "Paste", FACE, FACE_HI, FACE_SHADOW, DARK, false, paste.isHovered());
        UiDraw.button(g, minecraft.font, cancel.getX(), cancel.getY(), cancel.getWidth(), cancel.getHeight(), "Cancel", FACE, FACE_HI, FACE_SHADOW, DARK, false, cancel.isHovered());
        UiDraw.button(g, minecraft.font, ok.getX(), ok.getY(), ok.getWidth(), ok.getHeight(), "OK", GREEN, GREEN_HI, GREEN_SHADOW, WHITE, true, ok.isHovered());
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
