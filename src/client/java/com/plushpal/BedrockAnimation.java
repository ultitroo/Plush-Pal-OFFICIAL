package com.plushpal;

import com.google.gson.*;

import java.util.*;

/** Bedrock .animation.json: rotation/position keyframes, linear + catmullrom, sound_effects. */
public final class BedrockAnimation {
    public enum Loop { LOOP, ONCE, HOLD }
    public record Cue(double time, String effect) {}

    public final String name;
    public final double length;
    public final Loop loop;
    public final List<Cue> cues = new ArrayList<>();
    private final Map<String, Channel[]> tracks = new LinkedHashMap<>(); // [0]=rotation [1]=position

    private BedrockAnimation(String name, double length, Loop loop) { this.name = name; this.length = length; this.loop = loop; }

    public static Map<String, BedrockAnimation> parseAll(JsonObject root) {
        Map<String, BedrockAnimation> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("animations").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Loop loop = Loop.ONCE;
            if (o.has("loop")) {
                JsonElement l = o.get("loop");
                if (l.isJsonPrimitive() && l.getAsJsonPrimitive().isBoolean()) loop = l.getAsBoolean() ? Loop.LOOP : Loop.ONCE;
                else if ("hold_on_last_frame".equals(l.getAsString())) loop = Loop.HOLD;
            }
            double declared = o.has("animation_length") ? o.get("animation_length").getAsDouble() : 0;
            Map<String, Channel[]> parsed = new LinkedHashMap<>();
            double maxTime = 0;
            if (o.has("bones")) for (Map.Entry<String, JsonElement> be : o.getAsJsonObject("bones").entrySet()) {
                JsonObject b = be.getValue().getAsJsonObject();
                Channel rot = b.has("rotation") ? Channel.parse(b.get("rotation")) : null;
                Channel pos = b.has("position") ? Channel.parse(b.get("position")) : null;
                if (rot != null) maxTime = Math.max(maxTime, rot.times[rot.times.length - 1]);
                if (pos != null) maxTime = Math.max(maxTime, pos.times[pos.times.length - 1]);
                parsed.put(be.getKey(), new Channel[]{rot, pos});
            }
            // No animation_length (e.g. a loop authored without one)? Use the last keyframe time.
            BedrockAnimation a = new BedrockAnimation(e.getKey(), declared > 0 ? declared : maxTime, loop);
            a.tracks.putAll(parsed);
            if (o.has("sound_effects")) for (Map.Entry<String, JsonElement> se : o.getAsJsonObject("sound_effects").entrySet()) {
                JsonElement v = se.getValue();
                JsonObject so = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
                a.cues.add(new Cue(Double.parseDouble(se.getKey()), so.get("effect").getAsString()));
            }
            a.cues.sort(Comparator.comparingDouble(Cue::time));
            out.put(a.name, a);
        }
        return out;
    }

    public boolean isFinished(double t, boolean forceHold) { return loop == Loop.ONCE && !forceHold && t >= length; }

    public double effectiveTime(double t, boolean forceHold) {
        if (loop == Loop.LOOP) return length > 0 ? t % length : 0;
        if (loop == Loop.HOLD || forceHold) return Math.min(t, length);
        return t;
    }

    /** Writes this animation's bone offsets into pose, converted to Blockbench space. */
    public void apply(double t, Pose pose, double rotI, double posI) {
        double[] v = new double[3];
        for (Map.Entry<String, Channel[]> e : tracks.entrySet()) {
            float[] p = pose.getOrCreate(e.getKey());
            Channel rot = e.getValue()[0], pos = e.getValue()[1];
            if (pos != null) { pos.sample(t, v); p[0] = (float) (-v[0] * posI); p[1] = (float) (v[1] * posI); p[2] = (float) (v[2] * posI); }
            if (rot != null) { rot.sample(t, v); p[3] = (float) (-v[0] * rotI); p[4] = (float) (-v[1] * rotI); p[5] = (float) (v[2] * rotI); }
        }
    }

    private static final class Channel {
        double[] times; double[][] pre, post; boolean[] smooth;

        static Channel parse(JsonElement el) {
            List<double[]> pr = new ArrayList<>(), po = new ArrayList<>();
            List<Double> ts = new ArrayList<>(); List<Boolean> sm = new ArrayList<>();
            if (el.isJsonObject() && !isKeyframeObject(el.getAsJsonObject())) {
                TreeMap<Double, JsonElement> sorted = new TreeMap<>();
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    try { sorted.put(Double.parseDouble(e.getKey()), e.getValue()); } catch (NumberFormatException ignored) {}
                }
                for (Map.Entry<Double, JsonElement> e : sorted.entrySet()) add(e.getKey(), e.getValue(), ts, pr, po, sm);
            } else add(0.0, el, ts, pr, po, sm);
            Channel c = new Channel();
            int n = ts.size();
            c.times = new double[n]; c.pre = pr.toArray(new double[0][]); c.post = po.toArray(new double[0][]); c.smooth = new boolean[n];
            for (int i = 0; i < n; i++) { c.times[i] = ts.get(i); c.smooth[i] = sm.get(i); }
            return c;
        }

        private static boolean isKeyframeObject(JsonObject o) { return o.has("pre") || o.has("post") || o.has("vector"); }

        private static void add(double t, JsonElement k, List<Double> ts, List<double[]> pr, List<double[]> po, List<Boolean> sm) {
            double[] pre, post; boolean smooth = false;
            if (k.isJsonObject()) {
                JsonObject o = k.getAsJsonObject();
                if (o.has("vector")) { pre = post = vec(o.get("vector")); }
                else {
                    post = o.has("post") ? vec(o.get("post")) : vec(o.get("pre"));
                    pre = o.has("pre") ? vec(o.get("pre")) : post;
                }
                smooth = o.has("lerp_mode") && "catmullrom".equals(o.get("lerp_mode").getAsString());
            } else pre = post = vec(k);
            ts.add(t); pr.add(pre); po.add(post); sm.add(smooth);
        }

        private static double[] vec(JsonElement e) {
            double[] v = new double[3];
            if (e.isJsonArray()) { JsonArray a = e.getAsJsonArray(); for (int i = 0; i < 3 && i < a.size(); i++) v[i] = num(a.get(i)); }
            else Arrays.fill(v, num(e));
            return v;
        }

        private static double num(JsonElement e) {
            try { return e.getAsDouble(); } catch (Exception ex) { return 0; } // molang expressions unsupported
        }

        void sample(double t, double[] out) {
            int n = times.length;
            if (t <= times[0]) { System.arraycopy(pre[0], 0, out, 0, 3); return; }
            if (t >= times[n - 1]) { System.arraycopy(post[n - 1], 0, out, 0, 3); return; }
            int i = 0;
            while (i + 1 < n && times[i + 1] <= t) i++;
            double a = (t - times[i]) / (times[i + 1] - times[i]);
            double[] p1 = post[i], p2 = pre[i + 1];
            if (smooth[i] || smooth[i + 1]) {
                double[] p0 = i > 0 ? post[i - 1] : p1, p3 = i + 2 < n ? pre[i + 2] : p2;
                for (int c = 0; c < 3; c++) {
                    out[c] = 0.5 * (2 * p1[c] + (-p0[c] + p2[c]) * a
                            + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * a * a
                            + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * a * a * a);
                }
            } else for (int c = 0; c < 3; c++) out[c] = p1[c] + (p2[c] - p1[c]) * a;
        }
    }
}
