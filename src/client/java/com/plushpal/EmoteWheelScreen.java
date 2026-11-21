package com.plushpal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock-style emote wheel: a centre hexagon with your face and six hexagons around it. Which emote sits in
 * which slot comes from the config (Emotes tab). The hexagons are PNG textures in
 * assets/plushpal/textures/gui/wheel/ (hex, hex_hover, hex_empty, hex_center) so you can restyle them.
 * Hover turns a slot green, click plays it. Bind the wheel key in Controls > PlushPal (it starts unbound).
 */
public final class EmoteWheelScreen extends Screen {
    private static final String[] ICONS = {"emotes/hi", "emotes/yay", "emotes/clap"};
    /** Slot directions, clockwise from upper-left: upper-left, upper-right, right, lower-right, lower-left, left. */
    private static final double[] SLOT_ANGLE = {240, 300, 0, 60, 120, 180};
    private static final int CHANGE = 100; // hover id of the bottom button

    private static final int FACE = 0xFFD0D1D4, FACE_HI = 0xFFEDEEF0, FACE_SHADOW = 0xFF58585A, DARK = 0xFF1E1E1F, WHITE = 0xFFFFFFFF;

    private record Slot(int cx, int cy, int emote, List<HitButton> hits) {
        boolean hovered() { return hits.stream().anyMatch(HitButton::isHovered); }
    }

    private static int lastHover = -2;

    private final List<Slot> slots = new ArrayList<>();
    private HitButton changeBtn;
    private int centerX, centerY, hexR, hexW, hexH;
    private boolean quiet;
    private final long openNs = System.nanoTime();

    public EmoteWheelScreen() {
        super(Component.literal("Emotes"));
        UiDraw.resetAnimations();
    }

    @Override
    protected void init() {
        slots.clear();
        PlushConfig cfg = PlushConfig.get();
        hexR = Mth.clamp(height / 7, 24, 40);
        hexW = (int) Math.round(Math.sqrt(3) * hexR);
        hexH = hexR * 2;
        double dist = hexW + 5;
        centerX = width / 2;
        centerY = height / 2 - 10;

        for (int i = 0; i < 6; i++) {
            int sx = centerX + (int) Math.round(Math.cos(Math.toRadians(SLOT_ANGLE[i])) * dist);
            int sy = centerY + (int) Math.round(Math.sin(Math.toRadians(SLOT_ANGLE[i])) * dist);
            List<HitButton> hits = new ArrayList<>();
            int emote = EmoteTrigger.fromId(cfg.wheelSlots.get(i));
            if (emote >= 0) {
                Button.OnPress press = b -> {
                    EmoteTrigger.fire(emote);
                    quiet = true; // the press sound already played
                    onClose();
                };
                // two rectangles that fit inside the hexagon: the wide middle band, and a narrow tall column for the tips
                hits.add(new HitButton(sx - hexW / 2, sy - hexR / 2, hexW, hexR, "press", press));
                int tw = Math.max(8, hexR / 2);
                hits.add(new HitButton(sx - tw / 2, sy - (int) (hexR * 0.85), tw, (int) (hexR * 1.7), "press", press));
                hits.forEach(this::addRenderableWidget);
            }
            slots.add(new Slot(sx, sy, emote, hits));
        }

        int by = Math.min(height - 32, centerY + (int) (dist * 0.866) + hexR + 10);
        changeBtn = new HitButton(centerX - 70, by, 140, 22, "press", b -> {
            PlushConfigScreen.showEmotesTab();
            quiet = true;
            ScreenUtil.open(new PlushConfigScreen());
        });
        addRenderableWidget(changeBtn);
    }

    @Override
    public void onClose() {
        if (!quiet) UiSounds.play("back", 1f);
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiDraw.beginFrame();
        g.fill(0, 0, width, height, UiDraw.withAlpha(0x99000000, UiDraw.progress(openNs, 0.15)));
        super.extractRenderState(g, mouseX, mouseY, delta); // invisible hit buttons (input only)

        // title with the same shaded groove lines as the settings screen
        double dist = hexW + 5;
        int titleY = centerY - (int) (dist * 0.866) - hexR - 22;
        String title = "EMOTES";
        int tw = PixelFont.supports(title) ? PixelFont.width(title) : minecraft.font.width(Component.literal(title).withStyle(ChatFormatting.BOLD));
        int tx = centerX - tw / 2;
        float tIn = UiDraw.ease(UiDraw.progress(openNs, 0.2));
        int tc = UiDraw.withAlpha(WHITE, tIn);
        if (PixelFont.supports(title)) PixelFont.draw(g, title, tx, titleY, tc);
        else g.text(minecraft.font, Component.literal(title).withStyle(ChatFormatting.BOLD), tx, titleY, tc, false);
        int lineLen = Math.max(20, (int) (dist * 0.9)), ly = titleY + 3;
        for (int side = -1; side <= 1; side += 2) {
            int x0 = side < 0 ? tx - 8 - lineLen : tx + tw + 8, x1 = x0 + lineLen;
            g.fill(x0, ly, x1, ly + 1, UiDraw.withAlpha(0xFF343537, tIn));
            g.fill(x0, ly + 1, x1, ly + 2, UiDraw.withAlpha(0xFF535457, tIn));
        }

        int hoverId = -1;
        String label = "Emotes", hint = "";
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            boolean hover = s.emote() >= 0 && s.hovered();
            long key = UiDraw.key(s.cx(), s.cy(), hexW, hexH);
            float hv = UiDraw.hover(key, hover);                                     // eases the hexagon up under the cursor
            float pr = UiDraw.press(key, UiDraw.pressed(key, hover));                // and pushes it in while the mouse is held
            if (hover) { hoverId = i; label = EmoteTrigger.NAMES[s.emote()]; hint = "Click to play"; }
            String tex = s.emote() < 0 ? "wheel/hex_empty" : hover ? "wheel/hex_hover" : "wheel/hex";
            float pop = UiDraw.ease(UiDraw.progress(openNs + (long) (i * 35_000_000L), 0.2));
            float sc = 1f + 0.08f * hv - 0.06f * pr;
            int sink = Math.round(pr * 2f);
            int cx = centerX + Math.round((s.cx() - centerX) * pop), cy = centerY + Math.round((s.cy() - centerY) * pop) + sink;
            int w = Math.round(hexW * sc), h = Math.round(hexH * sc);
            Icons.drawRect(g, tex, cx - w / 2, cy - h / 2, w, h);
            if (s.emote() < 0) Icons.draw(g, "emotes/empty", cx - 12, cy - 12, 24);
            else { int is = Math.round(32 * (1f + 0.08f * hv - 0.06f * pr)); Icons.draw(g, ICONS[s.emote()], cx - is / 2, cy - is / 2, is); }
        }

        // centre hexagon: your face + the name of the hovered emote
        Icons.drawRect(g, "wheel/hex_center", centerX - hexW / 2, centerY - hexR, hexW, hexH);
        LocalPlayer p = minecraft.player;
        if (p != null) {
            int face = Math.max(20, hexR - 8), fx = centerX - face / 2, fy = centerY - face / 2 + 6;
            var skin = p.getSkin().body().texturePath();
            g.blit(RenderPipelines.GUI_TEXTURED, skin, fx, fy, 8, 8, face, face, 8, 8, 64, 64);   // face
            g.blit(RenderPipelines.GUI_TEXTURED, skin, fx, fy, 40, 8, face, face, 8, 8, 64, 64);  // hat layer
        }
        Component name = Component.literal(label).withStyle(ChatFormatting.BOLD);
        g.text(minecraft.font, name, centerX - minecraft.font.width(name) / 2, centerY - hexR + 12, WHITE, false);

        if (!hint.isEmpty()) g.text(minecraft.font, hint, centerX - minecraft.font.width(hint) / 2, changeBtn.getY() - 12, 0xFFA8E68F, false);

        // bottom "CHANGE EMOTES" button
        UiDraw.button(g, minecraft.font, changeBtn.getX(), changeBtn.getY(), changeBtn.getWidth(), changeBtn.getHeight(),
                "CHANGE EMOTES", FACE, FACE_HI, FACE_SHADOW, DARK, true, changeBtn.isHovered());
        if (changeBtn.isHovered()) hoverId = CHANGE;

        if (hoverId != lastHover) { // soft tick when the cursor moves onto a different slot
            if (hoverId != -1) UiSounds.play("hover", 1f);
            lastHover = hoverId;
        }
    }
}
