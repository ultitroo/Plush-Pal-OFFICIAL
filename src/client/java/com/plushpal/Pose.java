package com.plushpal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-bone animation offsets in model space: [dx,dy,dz, rxDeg,ryDeg,rzDeg]. */
public final class Pose {
    private static final float[] ZERO = new float[6];
    private final Map<String, float[]> map = new HashMap<>();

    public void clear() { map.clear(); }
    public float[] get(String bone) { return map.get(bone); }
    public float[] getOrCreate(String bone) { return map.computeIfAbsent(bone, k -> new float[6]); }

    public void copyFrom(Pose other) {
        map.clear();
        for (Map.Entry<String, float[]> e : other.map.entrySet()) map.put(e.getKey(), e.getValue().clone());
    }

    /** Copies every bone in {@code other} over this pose (bones only in this pose stay as they are). */
    public void overwriteFrom(Pose other) {
        for (Map.Entry<String, float[]> e : other.map.entrySet()) map.put(e.getKey(), e.getValue().clone());
    }

    /** out = a + (b - a) * t, per bone (a missing bone counts as rest pose). Linear. */
    public static void lerp(Pose a, Pose b, float t, Pose out) {
        out.map.clear();
        Set<String> keys = new HashSet<>(a.map.keySet());
        keys.addAll(b.map.keySet());
        for (String k : keys) {
            float[] av = a.map.getOrDefault(k, ZERO), bv = b.map.getOrDefault(k, ZERO), r = new float[6];
            for (int i = 0; i < 6; i++) r[i] = av[i] + (bv[i] - av[i]) * t;
            out.map.put(k, r);
        }
    }
}
