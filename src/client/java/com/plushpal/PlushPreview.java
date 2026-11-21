package com.plushpal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The settings screen's plush preview: a small software 3D renderer. It poses the real model with the real animation
 * pose (see {@link PlushRenderer#previewPose()}), turns it, projects it and rasterises the textured, lit faces into a
 * pixel buffer, which is then drawn as filled rectangles. Doing it ourselves (instead of asking the game to draw an
 * inventory item big) keeps it sharp, truly 3D, and instantly responsive when you drag it.
 */
final class PlushPreview {
    private static final Map<Identifier, BufferedImage> IMAGES = new HashMap<>();
    private static final Map<GeoModel, float[]> BOUNDS = new HashMap<>(); // minX,maxX,maxY,minZ,maxZ at rest
    private static boolean failed, loggedFail, particlesFailed;

    private static int bw, bh;
    private static int[] color = new int[0];
    private static float[] depth = new float[0];
    private static boolean[] used = new boolean[0];

    // per-frame view state
    private static float cosA, sinA, cx, cz, k, ox, baseY;

    private PlushPreview() {}

    /** Forget cached textures (call when the settings screen opens, so a changed skin is picked up). */
    static void reset() { IMAGES.clear(); BOUNDS.clear(); RETRY_AT.clear(); WARNED.clear(); }

    /** Renders the plush into the GUI rectangle (x, y, w, h), turned by {@code yawDeg}. Returns false if it can't. */
    static boolean draw(GuiGraphicsExtractor g, int x, int y, int w, int h, float yawDeg) {
        if (failed || w < 8 || h < 8) return false;
        try {
            PlushConfig cfg = PlushConfig.get();
            PlushRenderer.Skin skin = PlushRenderer.previewSkin();
            BufferedImage tex = image(skin.texture());
            boolean wantsOwn = cfg.usePlayerSkin && "own".equals(cfg.skinSource) && cfg.playerSkinContexts.contains(PlushRenderer.previewView());
            if (wantsOwn && (tex == null || skin.texture() == PlushRenderer.TEXTURE)) {
                PlushRenderer.Skin alt = ownFallback();   // no world, or the game's skin texture can't be read
                if (alt != null && image(alt.texture()) != null) { skin = alt; tex = image(alt.texture()); }
            }
            if (tex == null) tex = image(PlushRenderer.TEXTURE);
            if (tex == null) return false;
            GeoModel model = skin.slim() && PlushData.slimModel != null ? PlushData.slimModel : PlushData.model;
            if (model == null) return false;
            Pose pose = PlushRenderer.previewPose();

            float[] bounds = BOUNDS.computeIfAbsent(model, PlushPreview::measure);
            float radius = Math.max(bounds[1] - bounds[0], bounds[4] - bounds[3]) * 0.5f + 2f;
            // the scale of the chosen view; up to 3x (the Scale sliders' whole range) so the sliders show their real effect. Too big just runs off the stage.
            float user = (float) Math.max(0.25, Math.min(3.0, cfg.scaleFor(PlushRenderer.previewView())));
            k = Math.min((h - 6) / (bounds[2] + 3f), (w - 4) / (2f * radius)) * user * PlushRenderer.previewZoom;
            cx = (bounds[0] + bounds[1]) * 0.5f;
            cz = (bounds[3] + bounds[4]) * 0.5f;
            ox = w * 0.5f;
            baseY = h - 3f;
            double a = Math.toRadians(yawDeg);
            cosA = (float) Math.cos(a);
            sinA = (float) Math.sin(a);

            ensureBuffer(w, h);
            Matrix4f root = new Matrix4f().translate((float) cfg.modelOffset[0], (float) cfg.modelOffset[1], (float) cfg.modelOffset[2]);
            for (GeoModel.Bone b : model.roots) bone(b, root, pose, tex);
            Identifier own = skin.texture() != PlushRenderer.TEXTURE ? skin.texture() : null;
            for (Accessory acc : PlushData.accessories) {
                if (!cfg.accessoryOn(acc)) continue;
                BufferedImage at = image(acc.textureFor(own, skin.cape(), PlushRenderer.TEXTURE));
                if (at == null) at = image(acc.textureFor(null, null, PlushRenderer.TEXTURE)); // your cape isn't readable: use the accessory's own texture
                if (at == null) at = image(PlushRenderer.TEXTURE);
                if (at == null) continue;
                for (GeoModel.Bone b : acc.attached.roots) bone(b, root, pose, at);
            }
            if (cfg.particles.enabled && !particlesFailed) {
                try { particles(cfg, model, pose, root); }
                catch (Throwable t) { // a particle problem must never take the whole preview down
                    particlesFailed = true;
                    PlushPalClient.LOGGER.warn("PlushPal: preview particles disabled", t);
                }
            }
            flush(g, x, y);
            return true;
        } catch (Throwable t) {
            failed = true;
            if (!loggedFail) { loggedFail = true; PlushPalClient.LOGGER.warn("PlushPal: settings preview disabled", t); }
            return false;
        }
    }

    /**
     * Renders one accessory's own 3D model (alone, at rest) into the GUI rectangle (x, y, w, h), turned by yawDeg and
     * fitted to the box. Used for the little thumbnails on the Accessories page. Returns false if it can't.
     */
    static boolean drawAccessory(GuiGraphicsExtractor g, int x, int y, int w, int h, Accessory acc, Identifier own, Identifier cape, float yawDeg) {
        if (failed || w < 4 || h < 4 || acc.attached == null) return false;
        try {
            BufferedImage tex = image(acc.textureFor(own, cape, PlushRenderer.TEXTURE));
            if (tex == null) tex = image(acc.textureFor(null, null, PlushRenderer.TEXTURE));
            if (tex == null) tex = image(PlushRenderer.TEXTURE);
            if (tex == null) return false;
            float[] b = {1e9f, -1e9f, 1e9f, -1e9f, 1e9f, -1e9f};   // minX, maxX, minY, maxY, minZ, maxZ
            Vector3f v = new Vector3f();
            Pose rest = new Pose();
            for (GeoModel.Bone r : acc.attached.roots) fullBounds(r, new Matrix4f(), rest, b, v);
            if (b[0] > b[1]) return false;                           // no cubes at all
            float sx = b[1] - b[0], sy = Math.max(0.5f, b[3] - b[2]), sz = b[5] - b[4];
            float diag = Math.max(1f, (float) Math.sqrt(sx * sx + sz * sz));   // stays inside the box whatever the angle
            k = Math.min((h - 2) / sy, (w - 2) / diag);
            cx = (b[0] + b[1]) * 0.5f; cz = (b[4] + b[5]) * 0.5f;
            ox = w * 0.5f;
            baseY = h * 0.5f + (b[2] + b[3]) * 0.5f * k;
            double a = Math.toRadians(yawDeg);
            cosA = (float) Math.cos(a); sinA = (float) Math.sin(a);
            ensureBuffer(w, h);
            Matrix4f root = new Matrix4f();
            for (GeoModel.Bone bn : acc.attached.roots) bone(bn, root, rest, tex);
            flush(g, x, y);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fullBounds(GeoModel.Bone bn, Matrix4f parent, Pose pose, float[] b, Vector3f v) {
        Matrix4f m = boneMatrix(bn, parent, pose);
        for (GeoModel.Cube c : bn.cubes) {
            for (int i = 0; i < 8; i++) {
                m.transformPosition(v.set((i & 1) == 0 ? c.x0 : c.x1, (i & 2) == 0 ? c.y0 : c.y1, (i & 4) == 0 ? c.z0 : c.z1));
                b[0] = Math.min(b[0], v.x); b[1] = Math.max(b[1], v.x);
                b[2] = Math.min(b[2], v.y); b[3] = Math.max(b[3], v.y);
                b[4] = Math.min(b[4], v.z); b[5] = Math.max(b[5], v.z);
            }
        }
        for (GeoModel.Bone ch : bn.children) fullBounds(ch, m, pose, b, v);
    }

    // ---------------------------------------------------------------- scene

    private static void ensureBuffer(int w, int h) {
        if (w != bw || h != bh || color.length != w * h) {
            bw = w; bh = h;
            color = new int[w * h]; depth = new float[w * h]; used = new boolean[w * h];
        }
        Arrays.fill(color, 0);
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
    }

    /** Rest-pose extents of a model: minX, maxX, maxY, minZ, maxZ (model pixels). */
    private static float[] measure(GeoModel model) {
        float[] b = {1e9f, -1e9f, 0f, 1e9f, -1e9f};
        Vector3f v = new Vector3f();
        Pose none = new Pose();
        Matrix4f id = new Matrix4f();
        for (GeoModel.Bone r : model.roots) measureBone(r, id, none, b, v);
        if (b[0] > b[1]) { b[0] = -8; b[1] = 8; b[3] = -8; b[4] = 8; b[2] = 20; }
        b[2] = Math.max(b[2], 8f);
        return b;
    }

    private static void measureBone(GeoModel.Bone bn, Matrix4f parent, Pose pose, float[] b, Vector3f v) {
        Matrix4f m = boneMatrix(bn, parent, pose);
        for (GeoModel.Cube c : bn.cubes) {
            for (int i = 0; i < 8; i++) {
                m.transformPosition(v.set((i & 1) == 0 ? c.x0 : c.x1, (i & 2) == 0 ? c.y0 : c.y1, (i & 4) == 0 ? c.z0 : c.z1));
                b[0] = Math.min(b[0], v.x); b[1] = Math.max(b[1], v.x);
                b[2] = Math.max(b[2], v.y);
                b[3] = Math.min(b[3], v.z); b[4] = Math.max(b[4], v.z);
            }
        }
        for (GeoModel.Bone ch : bn.children) measureBone(ch, m, pose, b, v);
    }

    private static Matrix4f boneMatrix(GeoModel.Bone b, Matrix4f parent, Pose pose) {
        float[] a = pose.get(b.name);
        float dx = 0, dy = 0, dz = 0, rx = b.rx, ry = b.ry, rz = b.rz;
        if (a != null) { dx = a[0]; dy = a[1]; dz = a[2]; rx += a[3]; ry += a[4]; rz += a[5]; }
        return new Matrix4f(parent)
                .translate(b.px + dx, b.py + dy, b.pz + dz)
                .rotateZ((float) Math.toRadians(rz)).rotateY((float) Math.toRadians(ry)).rotateX((float) Math.toRadians(rx))
                .translate(-b.px, -b.py, -b.pz);
    }

    private static void bone(GeoModel.Bone b, Matrix4f parent, Pose pose, BufferedImage tex) {
        Matrix4f m = boneMatrix(b, parent, pose);
        for (GeoModel.Cube c : b.cubes) cube(c, m, tex);
        for (GeoModel.Bone ch : b.children) bone(ch, m, pose, tex);
    }

    private static final float LX = -0.35f, LY = 0.8f, LZ = -0.5f; // light comes from above, a bit left, in front of the plush

    private static void cube(GeoModel.Cube c, Matrix4f parent, BufferedImage tex) {
        Matrix4f m = parent;
        if (c.rotated) {
            m = new Matrix4f(parent).translate(c.px, c.py, c.pz)
                    .rotateZ((float) Math.toRadians(c.rz)).rotateY((float) Math.toRadians(c.ry)).rotateX((float) Math.toRadians(c.rx))
                    .translate(-c.px, -c.py, -c.pz);
        }
        Vector3f p = new Vector3f(), n = new Vector3f();
        float[] sx = new float[4], sy = new float[4], sz = new float[4], su = new float[4], sv = new float[4];
        float ll = (float) Math.sqrt(LX * LX + LY * LY + LZ * LZ);
        for (int f = 0; f < 6; f++) {
            GeoModel.Face face = c.faces[f];
            if (face == null) continue;
            // rotate the face normal into view space; cull faces turned away from the camera (camera is on the -Z side)
            m.transformDirection(n.set(PlushRenderer.NORMALS[f][0], PlushRenderer.NORMALS[f][1], PlushRenderer.NORMALS[f][2])).normalize();
            float nx = n.x * cosA + n.z * sinA, nz = -n.x * sinA + n.z * cosA, ny = n.y;
            if (nz >= 0f) continue;
            float shade = 0.55f + 0.45f * Math.max(0f, (nx * LX + ny * LY + nz * LZ) / ll);
            for (int i = 0; i < 4; i++) {
                int[] q = PlushRenderer.CORNERS[f][i];
                m.transformPosition(p.set(q[0] == 1 ? c.x1 : c.x0, q[1] == 1 ? c.y1 : c.y0, q[2] == 1 ? c.z1 : c.z0));
                float X = p.x - cx, Z = p.z - cz;
                float rx = X * cosA + Z * sinA, rz = -X * sinA + Z * cosA;
                sx[i] = ox - rx * k;          // camera looks along +Z, so screen-right is -X
                sy[i] = baseY - p.y * k;
                sz[i] = rz;
                su[i] = face.u0() + face.du() * q[3];
                sv[i] = face.v0() + face.dv() * q[4];
            }
            tri(sx, sy, sz, su, sv, 0, 1, 2, shade, tex);
            tri(sx, sy, sz, su, sv, 0, 2, 3, shade, tex);
        }
    }

    /** Rasterises one triangle with a z-buffer and nearest-texel sampling (orthographic, so interpolation is exact). */
    private static void tri(float[] x, float[] y, float[] z, float[] u, float[] v, int a, int b, int c, float shade, BufferedImage tex) {
        float area = (x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]);
        if (Math.abs(area) < 1e-5f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x[a], Math.min(x[b], x[c]))));
        int maxX = Math.min(bw - 1, (int) Math.ceil(Math.max(x[a], Math.max(x[b], x[c]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y[a], Math.min(y[b], y[c]))));
        int maxY = Math.min(bh - 1, (int) Math.ceil(Math.max(y[a], Math.max(y[b], y[c]))));
        int tw = tex.getWidth(), th = tex.getHeight();
        float inv = 1f / area, eps = -1e-3f;
        for (int py = minY; py <= maxY; py++) {
            float fy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float fx = px + 0.5f;
                float w0 = ((x[b] - fx) * (y[c] - fy) - (x[c] - fx) * (y[b] - fy)) * inv;
                float w1 = ((x[c] - fx) * (y[a] - fy) - (x[a] - fx) * (y[c] - fy)) * inv;
                float w2 = 1f - w0 - w1;
                if (w0 < eps || w1 < eps || w2 < eps) continue;
                float d = w0 * z[a] + w1 * z[b] + w2 * z[c];
                int idx = py * bw + px;
                if (d >= depth[idx]) continue;
                float uu = w0 * u[a] + w1 * u[b] + w2 * u[c], vv = w0 * v[a] + w1 * v[b] + w2 * v[c];
                int tx = Math.max(0, Math.min(tw - 1, (int) Math.floor(uu * tw))), ty = Math.max(0, Math.min(th - 1, (int) Math.floor(vv * th)));
                int argb = tex.getRGB(tx, ty);
                if ((argb >>> 24) < 40) continue; // transparent texel
                int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * shade));
                int gg = Math.min(255, Math.round(((argb >> 8) & 0xFF) * shade));
                int bl = Math.min(255, Math.round((argb & 0xFF) * shade));
                depth[idx] = d;
                color[idx] = 0xFF000000 | (r << 16) | (gg << 8) | bl;
            }
        }
    }

    /** Draws the buffer as merged rectangles of equal colour (a texel becomes one rectangle, so this stays cheap). */
    private static void flush(GuiGraphicsExtractor g, int x0, int y0) {
        Arrays.fill(used, false);
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int i = y * bw + x;
                int col = color[i];
                if (col == 0 || used[i]) continue;
                int xe = x + 1;
                while (xe < bw && !used[y * bw + xe] && color[y * bw + xe] == col) xe++;
                int ye = y + 1;
                outer:
                while (ye < bh) {
                    for (int xx = x; xx < xe; xx++) if (used[ye * bw + xx] || color[ye * bw + xx] != col) break outer;
                    ye++;
                }
                for (int yy = y; yy < ye; yy++) for (int xx = x; xx < xe; xx++) used[yy * bw + xx] = true;
                g.fill(x0 + x, y0 + y, x0 + xe, y0 + ye, col);
            }
        }
    }

    // ---------------------------------------------------------------- particles

    // the 4 vertices of the quad being collected, already projected like the model's faces
    private static final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
    private static final int[] qr = new int[4], qg = new int[4], qb = new int[4];
    private static int qn;
    private static BufferedImage qTex;   // null = vertex colours (flames), else a sprite texture (bubbles, snowflakes)

    /** Takes the vertices the particle meshes produce (the same ones the game gets) and rasterises each quad. */
    private static final PlushParticles.Sink SINK = (x, y, z, u, v, r, g, b, light, overlay) -> {
        float X = x - cx, Z = z - cz;
        float rx = X * cosA + Z * sinA, rz = -X * sinA + Z * cosA;
        qx[qn] = ox - rx * k; qy[qn] = baseY - y * k; qz[qn] = rz;
        qu[qn] = u; qv[qn] = v; qr[qn] = r; qg[qn] = g; qb[qn] = b;
        if (++qn == 4) {
            qn = 0;
            if (qTex == null) { triColor(0, 1, 2); triColor(0, 2, 3); }
            else { tri(qx, qy, qz, qu, qv, 0, 1, 2, 1f, qTex); tri(qx, qy, qz, qu, qv, 0, 2, 3, 1f, qTex); } // particles are fullbright
        }
    };

    /** Flames, bubbles and snowflakes for the forced preview state, simulated and meshed by the same code as in game. */
    private static void particles(PlushConfig cfg, GeoModel model, Pose pose, Matrix4f root) {
        PlushParticles.Set ps = PlushParticles.set("pv:" + PlushRenderer.previewView());
        ps.update(PlushRenderer.previewEffects(cfg.particles), cfg.particles, new float[]{0f, 0f, 0f});
        if (ps.isEmpty()) return;
        float[] loc = PlushRenderer.locator(model, "particle", pose); // they ride on the plush's "particle" locator, animation and all
        Matrix4f m = new Matrix4f(root).translate(loc[0], loc[1], loc[2]);
        float size = (float) cfg.particles.size;
        qn = 0;
        if (!ps.fire.isEmpty()) {
            if ((qTex = image(PlushRenderer.FLAME_TEX)) != null)
                PlushParticles.drawTo(ps.fire, m, SINK, 0xF000F0, 0, size, PlushParticles.KIND_FLAME);
        }
        if (!ps.bubbles.isEmpty() && (qTex = image(PlushRenderer.BUBBLE_TEX)) != null)
            PlushParticles.drawTo(ps.bubbles, m, SINK, 0xF000F0, 0, size, PlushParticles.KIND_BUBBLE);
        if (!ps.snow.isEmpty() && (qTex = image(PlushRenderer.SNOW_TEX)) != null)
            PlushParticles.drawTo(ps.snow, m, SINK, 0xF000F0, 0, size, PlushParticles.KIND_SNOW);
        qTex = null;
    }

    /** Like {@link #tri} but the colour comes from the vertices (Gouraud), for the vertex-coloured flames. */
    private static void triColor(int a, int b, int c) {
        float[] x = qx, y = qy, z = qz;
        float area = (x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]);
        if (Math.abs(area) < 1e-5f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x[a], Math.min(x[b], x[c]))));
        int maxX = Math.min(bw - 1, (int) Math.ceil(Math.max(x[a], Math.max(x[b], x[c]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y[a], Math.min(y[b], y[c]))));
        int maxY = Math.min(bh - 1, (int) Math.ceil(Math.max(y[a], Math.max(y[b], y[c]))));
        float inv = 1f / area, eps = -1e-3f;
        for (int py = minY; py <= maxY; py++) {
            float fy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float fx = px + 0.5f;
                float w0 = ((x[b] - fx) * (y[c] - fy) - (x[c] - fx) * (y[b] - fy)) * inv;
                float w1 = ((x[c] - fx) * (y[a] - fy) - (x[a] - fx) * (y[c] - fy)) * inv;
                float w2 = 1f - w0 - w1;
                if (w0 < eps || w1 < eps || w2 < eps) continue;
                float d = w0 * z[a] + w1 * z[b] + w2 * z[c];
                int idx = py * bw + px;
                if (d >= depth[idx]) continue;
                int r = Math.max(0, Math.min(255, Math.round(w0 * qr[a] + w1 * qr[b] + w2 * qr[c])));
                int gg = Math.max(0, Math.min(255, Math.round(w0 * qg[a] + w1 * qg[b] + w2 * qg[c])));
                int bl = Math.max(0, Math.min(255, Math.round(w0 * qb[a] + w1 * qb[b] + w2 * qb[c])));
                depth[idx] = d;
                color[idx] = 0xFF000000 | (r << 16) | (gg << 8) | bl;
            }
        }
    }

    // ---------------------------------------------------------------- textures

    private static final PlushConfig FALLBACK_CFG = new PlushConfig();
    static { FALLBACK_CFG.skinSource = "username"; }

    /** "My skin" when the game's own texture isn't readable: the skin (or saved copy) of this account's name. */
    private static PlushRenderer.Skin ownFallback() {
        try {
            String name = Minecraft.getInstance().getUser().getName();
            if (!SkinSource.validUsername(name)) return null;
            FALLBACK_CFG.skinUsername = name;
            return SkinSource.resolve(FALLBACK_CFG);   // null while loading; the preview retries every frame
        } catch (Throwable t) { return null; }
    }

    private static final Map<Identifier, Long> RETRY_AT = new HashMap<>(); // failed reads are retried, not cached forever
    private static final java.util.Set<Identifier> WARNED = new java.util.HashSet<>();

    /**
     * A texture's pixels. Tries, in order: the resource packs (bundled files), the live texture the game registered
     * (your downloaded skin / cape), and the skin cache files on disk. A failed read is NOT cached for good: skins
     * download asynchronously, so the texture may simply not exist yet; it is retried every half second.
     */
    private static BufferedImage image(Identifier id) {
        if (id == null) return null;
        BufferedImage custom = SkinSource.image(id); // downloaded / file skin: its pixels are already in memory
        if (custom != null) return custom;
        BufferedImage cached = IMAGES.get(id);
        if (cached != null) return cached;
        long now = System.nanoTime();
        Long next = RETRY_AT.get(id);
        if (next != null && now < next) return null;

        BufferedImage img = fromResource(id);
        if (img == null) img = fromLoadedTexture(id);
        if (img == null) img = fromSkinCache(id);
        if (img != null) {
            IMAGES.put(id, img);
            RETRY_AT.remove(id);
        } else {
            RETRY_AT.put(id, now + 500_000_000L);
            if (WARNED.add(id)) PlushPalClient.LOGGER.warn("PlushPal: the settings preview could not read texture {} (using a fallback, will keep retrying)", id);
        }
        return img;
    }

    private static BufferedImage fromResource(Identifier id) {
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(id);
            if (res.isPresent()) try (InputStream in = res.get().open()) { return ImageIO.read(in); }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Downloaded skins and capes are cached by the game as plain PNG files named by their hash in assets/skins/xx/hash
     * (the texture id ends with that hash). Looks in the game folder and a few folders above it (launchers keep the
     * assets folder in different places).
     */
    private static BufferedImage fromSkinCache(Identifier id) {
        try {
            String path = id.getPath();
            String hash = path.substring(path.lastIndexOf('/') + 1);
            if (hash.length() < 8) return null;
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath();
            for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
                java.nio.file.Path f = dir.resolve("assets").resolve("skins").resolve(hash.substring(0, 2)).resolve(hash);
                if (java.nio.file.Files.isRegularFile(f)) return ImageIO.read(f.toFile());
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Asks the texture manager for the registered texture and copies its pixels out. Uses reflection with several
     * method names, because the texture/NativeImage API differs between Minecraft versions.
     */
    private static BufferedImage fromLoadedTexture(Identifier id) {
        try {
            Object manager = Minecraft.getInstance().getTextureManager();
            Object tex = null;
            for (Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("getTexture") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Identifier.class)) {
                    tex = m.invoke(manager, id);
                    break;
                }
            }
            if (tex == null) return null;
            Object pixels = null;
            for (String name : new String[] {"getPixels", "getImage", "pixels", "getNativeImage"}) {
                Method m = findNoArg(tex.getClass(), name);
                if (m == null) continue;
                try { pixels = m.invoke(tex); } catch (Throwable ignored) { }
                if (pixels != null) break;
            }
            if (pixels == null) { // some texture types keep the image in a field
                for (Class<?> c = tex.getClass(); c != null && pixels == null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getType().getSimpleName().equals("NativeImage")) continue;
                        try { f.setAccessible(true); pixels = f.get(tex); } catch (Throwable ignored) { }
                        if (pixels != null) break;
                    }
                }
            }
            return pixels == null ? null : readNativeImage(pixels);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findNoArg(Class<?> c, String name) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) { m.setAccessible(true); return m; }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(name); m.setAccessible(true); return m; } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Copies a NativeImage into a BufferedImage: first by re-encoding as PNG, else pixel by pixel. */
    private static BufferedImage readNativeImage(Object image) {
        try {
            Method as = findNoArg(image.getClass(), "asByteArray");
            if (as != null) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream((byte[]) as.invoke(image)));
                if (img != null) return img;
            }
        } catch (Throwable ignored) { }
        try {
            int w = (int) findNoArg(image.getClass(), "getWidth").invoke(image);
            int h = (int) findNoArg(image.getClass(), "getHeight").invoke(image);
            // getPixel is ARGB in newer versions; an ABGR variant (getPixelABGR / getPixelRGBA) exists in others
            Method abgr = null, plain = null;
            for (Method m : image.getClass().getMethods()) {
                if (m.getParameterCount() != 2 || m.getReturnType() != int.class) continue;
                String n = m.getName();
                if (n.equals("getPixelABGR") || n.equals("getPixelRGBA")) abgr = m;
                else if (n.equals("getPixel")) plain = m;
            }
            if (w <= 0 || h <= 0 || (abgr == null && plain == null)) return null;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v;
                    if (abgr != null) {
                        int p = (int) abgr.invoke(image, x, y);
                        v = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
                    } else {
                        v = (int) plain.invoke(image, x, y);
                    }
                    out.setRGB(x, y, v);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
