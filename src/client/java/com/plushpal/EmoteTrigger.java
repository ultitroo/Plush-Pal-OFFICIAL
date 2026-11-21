package com.plushpal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Emote requests (G key / wheel). The renderer notices serial changes and starts the emote at {@link #time}. */
public final class EmoteTrigger {
    public static final int HI = 0, YAY = 1, CLAP = 2;
    public static final String[] NAMES = {"Hi", "Yay", "Clap"};

    public static volatile int serial = 0, which = HI;
    public static volatile double time = -1e9;

    public static void fire(int emote) {
        which = emote;
        time = System.nanoTime() / 1e9;
        serial++;
    }

    /** Emote id ("hi", "yay", "clap") -> constant, or -1 for an empty/unknown slot. */
    public static int fromId(String id) {
        return switch (id == null ? "" : id) { case "hi" -> HI; case "yay" -> YAY; case "clap" -> CLAP; default -> -1; };
    }

    /** The G key: whatever "quickEmote" is set to; "random" picks any enabled emote (hi, yay or clap). */
    public static void fireQuick() {
        PlushConfig cfg = PlushConfig.get();
        int w = fromId(cfg.quickEmote);
        if (w < 0) {
            List<Integer> pool = new ArrayList<>();
            for (int i = HI; i <= CLAP; i++) if (cfg.emote(i).enabled) pool.add(i);
            if (pool.isEmpty()) return;
            w = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        fire(w);
    }
}
