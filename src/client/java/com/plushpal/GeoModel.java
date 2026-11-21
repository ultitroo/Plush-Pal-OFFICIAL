package com.plushpal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * Bedrock geo (format 1.12+) parsed into "Blockbench space": X is negated on load, and
 * rotations become (-x, -y, z). That space matches the Java item model in the original pack
 * once the model is shifted by (8, 0, 5) pixels.
 */
public final class GeoModel {
    public static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    public record Face(float u0, float v0, float du, float dv) {}

    public static final class Cube {
        public float x0, y0, z0, x1, y1, z1;
        public boolean rotated;
        public float px, py, pz, rx, ry, rz;
        public final Face[] faces = new Face[6];
    }

    public static final class Bone {
        public final String name;
        public String parent;
        public float px, py, pz, rx, ry, rz;
        public final List<Cube> cubes = new ArrayList<>();
        public final List<Bone> children = new ArrayList<>();
        /** Named points on this bone (Bedrock "locators"), in the same space as the pivot (X already negated). */
        public final Map<String, float[]> locators = new LinkedHashMap<>();
        Bone(String name) { this.name = name; }
    }

    public final List<Bone> roots = new ArrayList<>();

    /**
     * A model that reproduces the chain of bones from the root down to {@code target} (pivots only, no cubes) and
     * hangs {@code extra}'s bones under it. Drawn with the same animation pose, the extra bones follow the
     * target bone. Falls back to the "plush" root bone if {@code target} doesn't exist.
     */
    public static GeoModel attach(GeoModel main, GeoModel extra, String target) {
        List<Bone> path = new ArrayList<>();
        for (Bone r : main.roots) if (findPath(r, target, path)) break;
        if (path.isEmpty()) for (Bone r : main.roots) if (r.name.equals("plush")) path.add(r);
        if (path.isEmpty() && !main.roots.isEmpty()) path.add(main.roots.get(0));

        GeoModel out = new GeoModel();
        if (path.isEmpty()) { out.roots.addAll(extra.roots); return out; }
        Bone parentCopy = null;
        for (Bone b : path) {
            Bone c = new Bone(b.name);
            c.px = b.px; c.py = b.py; c.pz = b.pz; c.rx = b.rx; c.ry = b.ry; c.rz = b.rz;
            if (parentCopy == null) out.roots.add(c); else parentCopy.children.add(c);
            parentCopy = c;
        }
        parentCopy.children.addAll(extra.roots);
        return out;
    }

    private static boolean findPath(Bone b, String target, List<Bone> path) {
        path.add(b);
        if (b.name.equals(target)) return true;
        for (Bone child : b.children) if (findPath(child, target, path)) return true;
        path.remove(path.size() - 1);
        return false;
    }

    public static GeoModel parse(JsonObject root) { return parse(root, false); }

    /** With {@code slim} true, arm bones get the 3-pixel-wide Alex arm layout instead of Steve's 4-pixel one. */
    public static GeoModel parse(JsonObject root, boolean slim) {
        JsonObject geo = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject desc = geo.getAsJsonObject("description");
        float tw = desc.get("texture_width").getAsFloat(), th = desc.get("texture_height").getAsFloat();
        GeoModel model = new GeoModel();
        Map<String, Bone> byName = new LinkedHashMap<>();
        for (JsonElement be : geo.getAsJsonArray("bones")) {
            JsonObject b = be.getAsJsonObject();
            Bone bone = new Bone(b.get("name").getAsString());
            if (b.has("parent")) bone.parent = b.get("parent").getAsString();
            if (b.has("pivot")) { float[] p = vec(b.getAsJsonArray("pivot")); bone.px = -p[0]; bone.py = p[1]; bone.pz = p[2]; }
            if (b.has("rotation")) { float[] r = vec(b.getAsJsonArray("rotation")); bone.rx = -r[0]; bone.ry = -r[1]; bone.rz = r[2]; }
            if (b.has("locators") && b.get("locators").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("locators").entrySet()) {
                    JsonElement v = e.getValue();
                    if (v.isJsonObject() && v.getAsJsonObject().has("offset")) v = v.getAsJsonObject().get("offset"); // {"offset": [x,y,z]} form
                    if (v.isJsonArray() && v.getAsJsonArray().size() >= 3) { float[] l = vec(v.getAsJsonArray()); bone.locators.put(e.getKey(), new float[]{-l[0], l[1], l[2]}); }
                }
            }
            if (b.has("cubes")) for (JsonElement ce : b.getAsJsonArray("cubes")) bone.cubes.add(cube(ce.getAsJsonObject(), tw, th, slim && bone.name.toLowerCase().contains("arm")));
            byName.put(bone.name, bone);
        }
        for (Bone b : byName.values()) {
            Bone parent = b.parent == null ? null : byName.get(b.parent);
            if (parent != null) parent.children.add(b); else model.roots.add(b);
        }
        return model;
    }

    private static Cube cube(JsonObject c, float tw, float th, boolean slimArm) {
        Cube cube = new Cube();
        float[] o = vec(c.getAsJsonArray("origin")), s = vec(c.getAsJsonArray("size"));
        float inf = c.has("inflate") ? c.get("inflate").getAsFloat() : 0f;
        cube.x0 = -(o[0] + s[0]) - inf; cube.x1 = -o[0] + inf;
        cube.y0 = o[1] - inf;           cube.y1 = o[1] + s[1] + inf;
        cube.z0 = o[2] - inf;           cube.z1 = o[2] + s[2] + inf;
        if (c.has("rotation")) {
            float[] r = vec(c.getAsJsonArray("rotation"));
            float[] p = c.has("pivot") ? vec(c.getAsJsonArray("pivot")) : new float[]{0, 0, 0};
            cube.rotated = true;
            cube.px = -p[0]; cube.py = p[1]; cube.pz = p[2];
            cube.rx = -r[0]; cube.ry = -r[1]; cube.rz = r[2];
        }
        JsonElement uv = c.get("uv");
        if (uv != null && uv.isJsonObject()) {
            JsonObject uo = uv.getAsJsonObject();
            for (int i = 0; i < 6; i++) {
                if (!uo.has(FACES[i])) continue;
                JsonObject f = uo.getAsJsonObject(FACES[i]);
                float[] a = vec2(f.getAsJsonArray("uv")), sz = vec2(f.getAsJsonArray("uv_size"));
                if (slimArm) { float[] r = slimUv(a[0], a[1], sz[0], sz[1]); a[0] = r[0]; a[1] = r[1]; sz[0] = r[2]; sz[1] = r[3]; }
                cube.faces[i] = new Face(a[0] / tw, a[1] / th, sz[0] / tw, sz[1] / th);
            }
        } else if (uv != null && uv.isJsonArray()) { // box UV
            float[] a = vec2(uv.getAsJsonArray());
            float u = a[0], v = a[1], w = s[0], h = s[1], d = s[2];
            float[][] r = {
                    {u + d, v + d, w, h}, {u + 2 * d + w, v + d, w, h},
                    {u + d + w, v + d, d, h}, {u, v + d, d, h},
                    {u + d + w, v + d, -w, -d}, {u + d + 2 * w, v + d, -w, -d}};
            for (int i = 0; i < 6; i++) cube.faces[i] = new Face(r[i][0] / tw, r[i][1] / th, r[i][2] / tw, r[i][3] / th);
        }
        return cube;
    }

    // The four 16x16 arm tiles of a 64x64 skin: right arm, right sleeve, left arm, left sleeve.
    private static final int[][] ARM_TILES = {{40, 16}, {40, 32}, {32, 48}, {48, 48}};

    /**
     * Steve arm tile columns:  [0-4) side, [4-8) front/top, [8-12) side/bottom, [12-16) back.
     * Alex (slim) arm tile:    [0-4) side, [4-7) front/top, [7-11) side (bottom is [7-10)), [11-14) back.
     */
    private static float[] slimUv(float u, float v, float du, float dv) {
        float umin = Math.min(u, u + du), umax = Math.max(u, u + du), vmin = Math.min(v, v + dv);
        for (int[] t : ARM_TILES) {
            if (umin < t[0] - 0.01f || umax > t[0] + 16.01f || vmin < t[1] - 0.01f || vmin >= t[1] + 16) continue;
            float rel = umin - t[0];
            boolean topRow = vmin - t[1] < 3.99f; // top/bottom faces use the first four rows
            float nmin, nmax;
            if (rel < 3.99f) { nmin = 0; nmax = 4; }
            else if (rel < 7.99f) { nmin = 4; nmax = 7; }
            else if (rel < 11.99f) { nmin = 7; nmax = topRow ? 10 : 11; }
            else { nmin = 11; nmax = 14; }
            nmin += t[0]; nmax += t[0];
            return du >= 0 ? new float[]{nmin, v, nmax - nmin, dv} : new float[]{nmax, v, nmin - nmax, dv};
        }
        return new float[]{u, v, du, dv};
    }

    private static float[] vec(JsonArray a) { return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()}; }
    private static float[] vec2(JsonArray a) { return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat()}; }
}
