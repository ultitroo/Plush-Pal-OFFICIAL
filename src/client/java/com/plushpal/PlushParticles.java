package com.plushpal;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Interaction particles that are part of the plush itself: flames while you're on fire, bubbles rising while
 * you're underwater, snowflakes while you're freezing. They are simulated and drawn in the plush's own space
 * (the same space as the model, in pixels), so they move with the item and your hand and never get left behind
 * in the world like normal game particles. They are real 3D shapes, not flat sprites:
 * flames are small tapered, flickering meshes coloured per vertex (textures/particle/solid.png is just a white texel),
 * bubbles are three crossed rings that read as a sphere, and snowflakes are single flakes that tumble in 3D.
 * Everything is lit from above and the flames are fullbright, so nothing turns dark when it faces away from the light.
 */
public final class PlushParticles {
    public static final int FIRE = 1, BUBBLES = 2, SNOW = 4;
    private static final int MAX = 100;
    private static final double FIRE_PS = 26, BUBBLE_PS = 14, SNOW_PS = 12; // spawned per second at amount 1.0

    public static final class P { float x, y, z, vx, vy, vz, age, life, size, phase; }

    /** All particles for one display context (first person, third person, ...). */
    public static final class Set {
        public final List<P> fire = new ArrayList<>(), bubbles = new ArrayList<>(), snow = new ArrayList<>();
        private double last;
        private double accFire, accBubble, accSnow;

        public boolean isEmpty() { return fire.isEmpty() && bubbles.isEmpty() && snow.isEmpty(); }

        /** Spawns for the active effects and moves everything. Call once per frame this context is drawn. */
        public void update(int effects, PlushConfig.Particles cfg, float[] origin) {
            float ox = origin[0], oy = origin[1], oz = origin[2];
            double now = System.nanoTime() / 1e9;
            float dt = (float) Math.min(0.1, Math.max(0, now - last));
            last = now;
            ThreadLocalRandom r = ThreadLocalRandom.current();

            if ((effects & FIRE) != 0) {
                accFire += FIRE_PS * cfg.amount * dt;
                for (; accFire >= 1; accFire--) if (fire.size() < MAX) fire.add(make(r, ox, oy + jitter(r, 1.5f), oz, 1.5f, 8 + r.nextFloat() * 6, 0.45f + r.nextFloat() * 0.35f, 4.5f + r.nextFloat() * 1.5f, 3f));
            } else accFire = 0;
            if ((effects & BUBBLES) != 0) {
                accBubble += BUBBLE_PS * cfg.amount * dt;
                for (; accBubble >= 1; accBubble--) if (bubbles.size() < MAX) bubbles.add(make(r, ox, oy + jitter(r, 1.5f), oz, 0.5f, 10 + r.nextFloat() * 8, 1.0f + r.nextFloat() * 0.6f, 3f + r.nextFloat() * 1.2f, 3f));
            } else accBubble = 0;
            if ((effects & SNOW) != 0) {
                accSnow += SNOW_PS * cfg.amount * dt;
                for (; accSnow >= 1; accSnow--) if (snow.size() < MAX) snow.add(make(r, ox, oy + jitter(r, 2f), oz, 0.5f, -(4 + r.nextFloat() * 3), 1.4f + r.nextFloat() * 0.6f, 3f + r.nextFloat(), 3.5f));
            } else accSnow = 0;

            step(fire, dt, false);
            step(bubbles, dt, true);
            step(snow, dt, true);
        }

        private static float jitter(ThreadLocalRandom r, float amount) { return (r.nextFloat() * 2 - 1) * amount; }

        /** A new particle around (x, y, z) - the plush's "particle" locator - spread sideways by {@code spread}. */
        private static P make(ThreadLocalRandom r, float x, float y, float z, float drift, float vy, float life, float size, float spread) {
            P p = new P();
            p.x = x + (r.nextFloat() * 2 - 1) * spread;
            p.z = z + (r.nextFloat() * 2 - 1) * spread;
            p.y = y;
            p.vx = (r.nextFloat() * 2 - 1) * drift;
            p.vz = (r.nextFloat() * 2 - 1) * drift;
            p.vy = vy;
            p.life = life;
            p.size = size;
            p.phase = r.nextFloat() * 6.28f;
            return p;
        }

        private static void step(List<P> list, float dt, boolean sway) {
            for (int i = list.size() - 1; i >= 0; i--) {
                P p = list.get(i);
                p.age += dt;
                if (p.age >= p.life) { list.remove(i); continue; }
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.z += p.vz * dt;
                if (sway) { // bubbles and snowflakes wobble from side to side
                    p.x += (float) Math.cos(p.age * 4 + p.phase) * 6 * dt;
                    p.z += (float) Math.sin(p.age * 3 + p.phase) * 4 * dt;
                }
            }
        }
    }

    private static final Map<String, Set> SETS = new HashMap<>();

    public static Set set(String context) { return SETS.computeIfAbsent(context, k -> new Set()); }

    /** Which effects are active for the local player right now. */
    public static int effects(LocalPlayer p, PlushConfig.Particles cfg) {
        int e = 0;
        if (cfg.fire && p.isOnFire()) e |= FIRE;
        if (cfg.bubbles && p.isUnderWater()) e |= BUBBLES;
        if (cfg.snow && p.getTicksFrozen() > 0) e |= SNOW;
        return e;
    }

    public static final int KIND_FLAME = 0, KIND_BUBBLE = 1, KIND_SNOW = 2;

    // flame mesh: ring height (0 = bottom, 1 = tip), ring radius (relative), ring colour
    private static final float[] RING_T = {0f, 0.2f, 0.5f, 0.78f, 1f};
    private static final float[] RING_R = {0.5f, 1f, 0.82f, 0.42f, 0f};
    private static final int[][] OUTER = {{225, 70, 10}, {255, 120, 20}, {255, 170, 40}, {255, 215, 90}, {255, 240, 170}};
    private static final int[][] CORE = {{255, 170, 40}, {255, 215, 80}, {255, 240, 140}, {255, 250, 200}, {255, 255, 235}};
    private static final int SEG = 6;

    /**
     * Draws one kind of particle. {@code m} maps the plush's model pixels to the screen. Flames shrink steadily;
     * bubbles and snow pop/shrink at the end. Faces are emitted from both sides and every normal points up, so a
     * particle looks the same from any angle and never goes dark.
     */
    public static void draw(List<P> list, Matrix4f m, VertexConsumer vc, int light, int overlay, float sizeMul, int kind) {
        drawTo(list, m, (x, y, z, u, v, r, g, b, l, o) ->
                vc.addVertex(x, y, z).setColor(r, g, b, 255).setUv(u, v).setOverlay(o).setLight(l).setNormal(0f, 1f, 0f),
                light, overlay, sizeMul, kind);
    }

    /** Receives finished vertices (already moved by the caller's matrix). The game feeds a VertexConsumer; the settings preview rasterises them itself. */
    public interface Sink {
        void vertex(float x, float y, float z, float u, float v, int r, int g, int b, int light, int overlay);
    }

    /** Same as {@link #draw} but into any {@link Sink}. Quads arrive as 4 consecutive vertices; every quad is emitted double-sided. */
    public static void drawTo(List<P> list, Matrix4f m, Sink vc, int light, int overlay, float sizeMul, int kind) {
        Vector3f tmp = new Vector3f();
        for (P p : list) {
            float t = p.age / p.life;
            float f = kind == KIND_FLAME ? 1f - t * 0.75f : (t < 0.8f ? 1f : (1f - t) / 0.2f);
            float h = p.size * sizeMul * f * 0.5f;
            if (h <= 0.05f) continue;
            switch (kind) {
                case KIND_FLAME -> flame(p, h, m, vc, tmp, light, overlay);
                case KIND_BUBBLE -> bubble(p, h, m, vc, tmp, light, overlay);
                default -> flake(p, h, m, vc, tmp, light, overlay);
            }
        }
    }

    /** Teardrop flame: a lathe of SEG sides, flickering and leaning, with a smaller brighter core inside. */
    private static void flame(P p, float h, Matrix4f m, Sink vc, Vector3f tmp, int light, int overlay) {
        // textured flame (textures/particle/flame.png): four crossed upright quads, so it reads from every angle
        float flick = 1f + 0.12f * (float) Math.sin(p.age * 18 + p.phase);
        float lean = (float) Math.sin(p.age * 6 + p.phase) * h * 0.5f;
        float cx = p.x + (float) Math.cos(p.phase) * lean, cz = p.z + (float) Math.sin(p.phase) * lean;
        float hw = h * flick, hh = h * 1.35f * (1f + 0.08f * (float) Math.sin(p.age * 23 + p.phase * 2));
        float cy = p.y + hh;                                  // the flame's base sits on the particle's position
        float d = hw * 0.7071f;
        sprite(vc, m, tmp, cx, cy, cz, hw, 0, 0, 0, hh, 0, light, overlay);
        sprite(vc, m, tmp, cx, cy, cz, 0, 0, hw, 0, hh, 0, light, overlay);
        sprite(vc, m, tmp, cx, cy, cz, d, 0, d, 0, hh, 0, light, overlay);
        sprite(vc, m, tmp, cx, cy, cz, d, 0, -d, 0, hh, 0, light, overlay);
    }

    private static void lathe(P p, float radius, float height, float lx, float lz, int[][] colors,
                              Matrix4f m, Sink vc, Vector3f tmp, int light, int overlay) {
        float spin = p.phase + p.age * 3f;
        for (int i = 0; i < RING_T.length - 1; i++) {
            float t0 = RING_T[i], t1 = RING_T[i + 1];
            float y0 = p.y + t0 * height, y1 = p.y + t1 * height;
            float r0 = RING_R[i] * radius, r1 = RING_R[i + 1] * radius;
            float x0 = p.x + lx * t0 * t0, z0 = p.z + lz * t0 * t0, x1 = p.x + lx * t1 * t1, z1 = p.z + lz * t1 * t1;
            for (int s = 0; s < SEG; s++) {
                float a0 = spin + s * 6.2832f / SEG, a1 = spin + (s + 1) * 6.2832f / SEG;
                float[] q = {
                        x0 + (float) Math.cos(a0) * r0, y0, z0 + (float) Math.sin(a0) * r0,
                        x0 + (float) Math.cos(a1) * r0, y0, z0 + (float) Math.sin(a1) * r0,
                        x1 + (float) Math.cos(a1) * r1, y1, z1 + (float) Math.sin(a1) * r1,
                        x1 + (float) Math.cos(a0) * r1, y1, z1 + (float) Math.sin(a0) * r1};
                int[] c0 = colors[i], c1 = colors[i + 1];
                int[][] col = {c0, c0, c1, c1};
                solid(vc, m, tmp, q, col, 0xF000F0, overlay); // flames are fullbright
            }
        }
    }

    /** Three perpendicular rings = a hollow sphere from any direction. */
    private static void bubble(P p, float h, Matrix4f m, Sink vc, Vector3f tmp, int light, int overlay) {
        float wob = 1f + 0.06f * (float) Math.sin(p.age * 9 + p.phase);
        float hx = h * wob, hy = h / wob;
        sprite(vc, m, tmp, p.x, p.y, p.z, hx, 0, 0, 0, hy, 0, light, overlay);   // XY
        sprite(vc, m, tmp, p.x, p.y, p.z, 0, 0, hx, 0, hy, 0, light, overlay);   // ZY
        sprite(vc, m, tmp, p.x, p.y, p.z, hx, 0, 0, 0, 0, hy, light, overlay);   // XZ
    }

    /** One flake that spins and tilts, so it flashes and turns like a real one. */
    private static void flake(P p, float h, Matrix4f m, Sink vc, Vector3f tmp, int light, int overlay) {
        float a = p.phase + p.age * 2.5f, tilt = 0.9f + (float) Math.sin(p.age * 2 + p.phase) * 0.5f;
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a), ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        sprite(vc, m, tmp, p.x, p.y, p.z, ca * h, 0, sa * h, -sa * st * h, ct * h, ca * st * h, light, overlay);
    }

    /** A textured quad centred on (cx,cy,cz) spanned by half-vectors u and v, emitted double-sided. */
    private static void sprite(Sink vc, Matrix4f m, Vector3f tmp, float cx, float cy, float cz,
                               float ux, float uy, float uz, float vx, float vy, float vz, int light, int overlay) {
        float[][] corner = {
                {cx - ux + vx, cy - uy + vy, cz - uz + vz, 0, 0}, {cx + ux + vx, cy + uy + vy, cz + uz + vz, 1, 0},
                {cx + ux - vx, cy + uy - vy, cz + uz - vz, 1, 1}, {cx - ux - vx, cy - uy - vy, cz - uz - vz, 0, 1}};
        for (int side = 0; side < 2; side++) {
            for (int k = 0; k < 4; k++) {
                float[] c = corner[side == 0 ? k : 3 - k];
                vertex(vc, m, tmp, c[0], c[1], c[2], c[3], c[4], 255, 255, 255, light, overlay);
            }
        }
    }

    /** A vertex-coloured quad (4 xyz corners + 4 rgb), sampling the white texel of solid.png, emitted double-sided. */
    private static void solid(Sink vc, Matrix4f m, Vector3f tmp, float[] q, int[][] col, int light, int overlay) {
        for (int side = 0; side < 2; side++) {
            for (int k = 0; k < 4; k++) {
                int i = side == 0 ? k : 3 - k;
                vertex(vc, m, tmp, q[i * 3], q[i * 3 + 1], q[i * 3 + 2], 0.5f, 0.5f, col[i][0], col[i][1], col[i][2], light, overlay);
            }
        }
    }

    private static void vertex(Sink vc, Matrix4f m, Vector3f tmp, float x, float y, float z, float u, float v,
                               int r, int g, int b, int light, int overlay) {
        m.transformPosition(tmp.set(x, y, z));
        vc.vertex(tmp.x, tmp.y, tmp.z, u, v, r, g, b, light, overlay);
    }
}
