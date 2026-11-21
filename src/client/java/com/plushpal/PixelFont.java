package com.plushpal;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The chunky capital-letter font the Punchy settings use for titles and section headings. It is drawn straight from
 * pixel rows with filled rectangles (no font files needed). Only A-Z, digits and common punctuation exist here:
 * {@link #supports(String)} says whether a text can use it (anything else falls back to the game's bold font).
 */
final class PixelFont {
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();
    private static final int ROWS = 7, GAP = 1;

    private static void put(char c, String... rows) { GLYPHS.put(c, rows); }

    static {
        put('A', "01110", "10001", "10001", "11111", "10001", "10001", "10001");
        put('B', "11110", "10001", "10001", "11110", "10001", "10001", "11110");
        put('C', "01110", "10001", "10000", "10000", "10000", "10001", "01110");
        put('D', "11110", "10001", "10001", "10001", "10001", "10001", "11110");
        put('E', "11111", "10000", "10000", "11110", "10000", "10000", "11111");
        put('F', "11111", "10000", "10000", "11110", "10000", "10000", "10000");
        put('G', "01110", "10001", "10000", "10111", "10001", "10001", "01111");
        put('H', "10001", "10001", "10001", "11111", "10001", "10001", "10001");
        put('I', "01110", "00100", "00100", "00100", "00100", "00100", "01110");
        put('J', "00111", "00010", "00010", "00010", "00010", "10010", "01100");
        put('K', "10001", "10010", "10100", "11000", "10100", "10010", "10001");
        put('L', "10000", "10000", "10000", "10000", "10000", "10000", "11111");
        put('M', "10001", "11011", "10101", "10101", "10001", "10001", "10001");
        put('N', "10001", "11001", "10101", "10011", "10001", "10001", "10001");
        put('O', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        put('P', "11110", "10001", "10001", "11110", "10000", "10000", "10000");
        put('Q', "01110", "10001", "10001", "10001", "10101", "10010", "01101");
        put('R', "11110", "10001", "10001", "11110", "10100", "10010", "10001");
        put('S', "01111", "10000", "10000", "01110", "00001", "00001", "11110");
        put('T', "11111", "00100", "00100", "00100", "00100", "00100", "00100");
        put('U', "10001", "10001", "10001", "10001", "10001", "10001", "01110");
        put('V', "10001", "10001", "10001", "10001", "10001", "01010", "00100");
        put('W', "10001", "10001", "10001", "10101", "10101", "10101", "01010");
        put('X', "10001", "10001", "01010", "00100", "01010", "10001", "10001");
        put('Y', "10001", "10001", "01010", "00100", "00100", "00100", "00100");
        put('Z', "11111", "00001", "00010", "00100", "01000", "10000", "11111");
        put('0', "01110", "10001", "10011", "10101", "11001", "10001", "01110");
        put('1', "00100", "01100", "00100", "00100", "00100", "00100", "01110");
        put('2', "01110", "10001", "00001", "00010", "00100", "01000", "11111");
        put('3', "11110", "00001", "00001", "01110", "00001", "00001", "11110");
        put('4', "00010", "00110", "01010", "10010", "11111", "00010", "00010");
        put('5', "11111", "10000", "11110", "00001", "00001", "10001", "01110");
        put('6', "00110", "01000", "10000", "11110", "10001", "10001", "01110");
        put('7', "11111", "00001", "00010", "00100", "01000", "01000", "01000");
        put('8', "01110", "10001", "10001", "01110", "10001", "10001", "01110");
        put('9', "01110", "10001", "10001", "01111", "00001", "00010", "01100");
        put('.', "00000", "00000", "00000", "00000", "00000", "01100", "01100");
        put(',', "00000", "00000", "00000", "00000", "01100", "00100", "01000");
        put(':', "00000", "01100", "01100", "00000", "01100", "01100", "00000");
        put('!', "00100", "00100", "00100", "00100", "00100", "00000", "00100");
        put('?', "01110", "10001", "00001", "00010", "00100", "00000", "00100");
        put('-', "00000", "00000", "00000", "11111", "00000", "00000", "00000");
        put('+', "00000", "00100", "00100", "11111", "00100", "00100", "00000");
        put('&', "01100", "10010", "10100", "01000", "10101", "10010", "01101");
        put('(', "00010", "00100", "01000", "01000", "01000", "00100", "00010");
        put(')', "01000", "00100", "00010", "00010", "00010", "00100", "01000");
        put('/', "00001", "00001", "00010", "00100", "01000", "10000", "10000");
        put('%', "11001", "11010", "00010", "00100", "01000", "01011", "10011");
        put('\'', "00100", "00100", "01000", "00000", "00000", "00000", "00000");
        put('<', "00010", "00100", "01000", "10000", "01000", "00100", "00010");
        put('>', "01000", "00100", "00010", "00001", "00010", "00100", "01000");
        put('_', "00000", "00000", "00000", "00000", "00000", "00000", "11111");
    }

    private PixelFont() {}

    private static char norm(char c) { return Character.toUpperCase(c); }

    /** True when every character of the text exists in this font (spaces included). */
    static boolean supports(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = norm(s.charAt(i));
            if (c != ' ' && !GLYPHS.containsKey(c)) return false;
        }
        return true;
    }

    static int height() { return ROWS + 1; }

    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += advance(norm(s.charAt(i)));
        return Math.max(0, w - GAP);
    }

    private static int advance(char c) { return c == ' ' ? 4 : 6 + GAP; }

    /** Draws the text with its top-left corner at (x, y), each stroke two pixels wide, plus a dark one-pixel shadow. */
    static void draw(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        draw(g, s, x, y, color, 0xFF000000);
    }

    static void draw(GuiGraphicsExtractor g, String s, int x, int y, int color, int shadow) {
        if ((shadow >>> 24) != 0) paint(g, s, x + 1, y + 1, shadow);
        paint(g, s, x, y, color);
    }

    private static void paint(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            char c = norm(s.charAt(i));
            String[] rows = GLYPHS.get(c);
            if (rows != null) {
                for (int r = 0; r < ROWS; r++) {
                    String row = rows[r];
                    int start = -1;
                    for (int col = 0; col <= 6; col++) {
                        // every pixel is stretched one pixel to the right, which makes the strokes two pixels wide
                        boolean on = (col < 5 && row.charAt(col) == '1') || (col > 0 && col - 1 < 5 && row.charAt(col - 1) == '1');
                        if (on && start < 0) start = col;
                        if (!on && start >= 0) { g.fill(cx + start, y + r, cx + col, y + r + 1, color); start = -1; }
                    }
                }
            }
            cx += advance(c);
        }
    }

    static String upper(String s) { return s.toUpperCase(Locale.ROOT); }
}
