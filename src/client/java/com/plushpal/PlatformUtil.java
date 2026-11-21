package com.plushpal;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small helpers for keys, mouse, clipboard and opening folders. Input goes through Minecraft's own classes
 * (InputConstants, MouseHandler, KeyboardHandler), never LWJGL/GLFW directly, so it works whatever windowing
 * backend the game uses. Everything fails soft: if something is unavailable you just get "not pressed" / "".
 */
final class PlatformUtil {
    private static boolean inputFailed;

    private PlatformUtil() {}

    /** True while the key is held. Pass InputConstants.KEY_* codes (not GLFW.GLFW_KEY_*). */
    static boolean keyDown(int key) {
        if (inputFailed) return false;
        try {
            return InputConstants.isKeyDown(key);
        } catch (Throwable t) {
            inputFailed = true;
            PlushPalClient.LOGGER.warn("PlushPal: could not read the keyboard; keyboard shortcuts are off", t);
            return false;
        }
    }

    static boolean ctrlDown() {
        return keyDown(InputConstants.KEY_LCONTROL) || keyDown(InputConstants.KEY_RCONTROL);
    }

    static boolean shiftDown() {
        return keyDown(InputConstants.KEY_LSHIFT) || keyDown(InputConstants.KEY_RSHIFT);
    }

    /** True while the left mouse button is held (unlike {@link #mouseDown()}, which also counts the right one). */
    static boolean leftMouseDown() {
        try { return Minecraft.getInstance().mouseHandler.isLeftPressed(); } catch (Throwable t) { return false; }
    }

    static boolean mouseDown() {
        try {
            var mouse = Minecraft.getInstance().mouseHandler;
            return mouse.isLeftPressed() || mouse.isRightPressed();
        } catch (Throwable t) { return false; }
    }

    static String clipboard() {
        try {
            String s = Minecraft.getInstance().keyboardHandler.getClipboard();
            return s == null ? "" : s;
        } catch (Throwable t) { return ""; }
    }

    static boolean setClipboard(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Opens a folder in the system file manager (creates it first). Returns false if nothing could be launched. */
    static boolean openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd = os.contains("win") ? new String[]{"explorer", dir.toAbsolutePath().toString()}
                    : os.contains("mac") ? new String[]{"open", dir.toAbsolutePath().toString()}
                    : new String[]{"xdg-open", dir.toAbsolutePath().toString()};
            new ProcessBuilder(cmd).start();
            return true;
        } catch (IOException | RuntimeException e) {
            PlushPalClient.LOGGER.warn("PlushPal: could not open folder {}", dir, e);
            return false;
        }
    }
}