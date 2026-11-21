package com.plushpal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One accessory, imported from accessories/binding/*.json (see accessories/README.md).
 * Its model hangs from {@code attach_to}, it may play its own looping animations, and its texture can come
 * from a file or from the player's own cape/skin.
 */
public final class Accessory {
    public final String id, name, description, skinTexture;
    public final boolean setSkinTexture, defaultEnabled;
    public final Identifier texture;              // may be null
    public final GeoModel attached;

    /** One animation plus the same attributes the plush's own animations have. */
    public record AccAnim(BedrockAnimation anim, double speed, double rotation, double position) {
        void apply(double t, Pose out) { anim.apply(anim.effectiveTime(t, false), out, rotation, position); }
    }

    /** Plays whatever the plush is doing (layered on top). */
    public final List<AccAnim> always;
    /** Plush state -> animation: idle, swim_idle, fall, equip, hi, yay, clap, attack, mine, on_fire, freeze. */
    public final Map<String, AccAnim> byState;

    private Accessory(String id, String name, String description, String skinTexture, boolean setSkinTexture,
                      boolean defaultEnabled, Identifier texture, GeoModel attached, List<AccAnim> always, Map<String, AccAnim> byState) {
        this.id = id; this.name = name; this.description = description; this.skinTexture = skinTexture;
        this.setSkinTexture = setSkinTexture; this.defaultEnabled = defaultEnabled;
        this.texture = texture; this.attached = attached; this.always = always; this.byState = byState;
    }

    /** Which texture to draw with: the player's cape/skin when requested and available, else the file, else the plush's. */
    public Identifier textureFor(Identifier ownSkin, Identifier cape, Identifier bundled) {
        if (setSkinTexture) {
            if ("cape".equals(skinTexture)) { if (cape != null) return cape; }
            else if (ownSkin != null) return ownSkin;
        }
        return texture != null ? texture : bundled;
    }

    /**
     * Writes this accessory's animations into {@code out} for the plush's current state and returns a short token
     * naming what is playing (used to crossfade when it changes).
     * <ul>
     *   <li>"always" animations play no matter what.</li>
     *   <li>The animation for the current state plays from the start of that state.</li>
     *   <li>If the accessory has none for this state (or it already finished), "idle" plays instead, running
     *       continuously - so an idle sway never stops.</li>
     * </ul>
     */
    public String applyFor(String state, double now, double globalSpeed, double stateStart, double stateSpeed, Pose out) {
        for (AccAnim a : always) a.apply(now * globalSpeed * a.speed(), out);
        AccAnim s = byState.get(state);
        double t = 0;
        if (s != null) {
            t = (now - stateStart) * stateSpeed * s.speed();
            if (s.anim().isFinished(t, false)) s = null;
        }
        if (s == null) {
            s = byState.get("idle");
            if (s != null) t = now * globalSpeed * s.speed();
        }
        if (s == null) return "-";
        s.apply(t, out);
        return s.anim().name;
    }

    static Accessory load(ResourceManager rm, Identifier bindingId, GeoModel main) throws Exception {
        JsonObject o = readJson(rm, bindingId);
        String ns = bindingId.getNamespace(), path = bindingId.getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        String base = file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;

        String modelFile = str(o, null, "model");
        if (modelFile == null) throw new IllegalArgumentException("binding has no \"model\"");
        GeoModel extra = GeoModel.parse(readJson(rm, id(ns, "accessories/models/" + modelFile)));
        GeoModel attached = GeoModel.attach(main, extra, str(o, "plush", "attach_to"));

        List<AccAnim> always = new ArrayList<>();
        Map<String, AccAnim> byState = new HashMap<>();
        String animFile = str(o, null, "anim", "animation");
        if (animFile != null) {
            Map<String, BedrockAnimation> all = BedrockAnimation.parseAll(readJson(rm, id(ns, "accessories/animations/" + animFile)));
            JsonElement an = o.get("animations");
            if (an != null && an.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : an.getAsJsonObject().entrySet()) {
                    String key = stateKey(e.getKey());
                    JsonElement v = e.getValue();
                    if (v.isJsonArray()) {
                        for (JsonElement item : v.getAsJsonArray()) addAnim(key, item, all, always, byState, base);
                    } else {
                        addAnim(key, v, all, always, byState, base);
                    }
                }
            } else if (an != null && an.isJsonArray()) { // older form: a plain list = "always"
                for (JsonElement item : an.getAsJsonArray()) addAnim("always", item, all, always, byState, base);
            } else { // nothing listed: every looping animation in the file plays all the time
                for (BedrockAnimation a : all.values()) if (a.loop == BedrockAnimation.Loop.LOOP) always.add(new AccAnim(a, 1, 1, 1));
            }
        }

        Identifier texture = null;
        String tex = str(o, null, "texture");
        if (tex != null) {
            Identifier t = id(ns, "accessories/texture/" + tex);
            if (rm.getResource(t).isPresent()) texture = t;
            else PlushPalClient.LOGGER.warn("PlushPal: accessory '{}' texture {} not found", base, t);
        }
        return new Accessory(ns + ":" + base, str(o, base, "name"), str(o, "", "description"),
                str(o, "skin", "skin_texture"), bool(o, false, "set_skin_texture", "setskintexture"),
                bool(o, false, "default_enabled"), texture, attached, always, byState);
    }

    /** Accepts friendly names for each plush state. */
    private static String stateKey(String raw) {
        String k = raw.toLowerCase().trim();
        return switch (k) {
            case "falling" -> "fall";
            case "freezing" -> "freeze";
            case "swim", "swimming", "idle_swim" -> "swim_idle";
            case "fire", "burning" -> "on_fire";
            case "ow", "damage", "damaged", "hit" -> "hurt";
            case "nervous", "low", "lowhealth" -> "low_health";
            case "hand_in", "handin", "pull_up", "equipping" -> "equip";
            default -> k;
        };
    }

    /** A value is either an animation name, or an object with the plush's animation attributes. */
    private static void addAnim(String key, JsonElement v, Map<String, BedrockAnimation> all, List<AccAnim> always,
                                Map<String, AccAnim> byState, String accessory) {
        String name; double speed = 1, rot = 1, pos = 1; boolean enabled = true;
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            name = str(o, null, "animation", "anim", "name");
            speed = num(o, 1, "speed");
            rot = num(o, 1, "rotation_intensity", "rotationIntensity", "rotation");
            pos = num(o, 1, "position_intensity", "positionIntensity", "position");
            enabled = bool(o, true, "enabled");
        } else {
            name = v.getAsString();
        }
        if (!enabled) return;
        BedrockAnimation a = name == null ? null : all.get(name);
        if (a == null) {
            PlushPalClient.LOGGER.warn("PlushPal: accessory '{}' state '{}' uses unknown animation '{}'", accessory, key, name);
            return;
        }
        AccAnim aa = new AccAnim(a, Math.max(0, speed), rot, pos);
        if (key.equals("always")) always.add(aa); else byState.put(key, aa);
    }

    private static double num(JsonObject o, double def, String... keys) {
        for (String k : keys) if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsDouble();
        return def;
    }

    private static Identifier id(String ns, String path) { return Identifier.fromNamespaceAndPath(ns, path); }

    private static JsonObject readJson(ResourceManager rm, Identifier id) throws Exception {
        try (BufferedReader r = rm.getResourceOrThrow(id).openAsReader()) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private static String str(JsonObject o, String def, String... keys) {
        for (String k : keys) if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsString();
        return def;
    }

    private static boolean bool(JsonObject o, boolean def, String... keys) {
        for (String k : keys) if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsBoolean();
        return def;
    }
}
