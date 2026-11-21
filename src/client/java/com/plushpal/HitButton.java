package com.plushpal;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Invisible vanilla button used only for input (we draw the visuals ourselves). It replaces vanilla's click
 * sound with one of ours ("select", "back", "scroll"), or none when {@code sound} is null.
 */
final class HitButton extends Button {
    private final String sound;

    HitButton(int x, int y, int w, int h, String sound, Button.OnPress onPress) {
        super(x, y, w, h, Component.empty(), onPress, DEFAULT_NARRATION);
        this.sound = sound;
        setAlpha(0f);
    }

    @Override
    public void playDownSound(SoundManager manager) {
        UiDraw.markClick();
        if (sound != null) UiSounds.play(sound, 1f);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {

    }
}
