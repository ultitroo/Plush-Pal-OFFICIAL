package com.plushpal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.server.packs.resources.Resource;
import java.util.Map;

/** Loads geo, animations and accessories from resource packs on every reload, so other packs can override them. */
public final class PlushData {
    public static volatile GeoModel model;
    /** Used for slim (Alex) skins: geo/plush_slim.geo.json if you provide one, else the main model with slim arm UVs. */
    public static volatile GeoModel slimModel;
    /** Everything found in accessories/binding/*.json (any namespace). */
    public static volatile List<Accessory> accessories = List.of();
    /** The animations in use right now: every pack's animations merged, then the player's mix applied on top (see {@link #remix()}). */
    public static volatile Map<String, BedrockAnimation> animations = Map.of();
    /** Every pack that supplies plush.animation.json, lowest priority first (the mod's own animations come first). */
    public static volatile List<String> animationPackIds = List.of();
    private static volatile Map<String, Map<String, BedrockAnimation>> packAnimations = Map.of();

    public static void reload(ResourceManager rm) {
        Icons.clearCache();
        try {
            JsonObject geo = read(rm, "geo/plush.geo.json");
            model = GeoModel.parse(geo);
            Identifier slimId = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "geo/plush_slim.geo.json");
            slimModel = rm.getResource(slimId).isPresent() ? GeoModel.parse(read(rm, "geo/plush_slim.geo.json")) : GeoModel.parse(geo, true);
            loadAnimationPacks(rm);
        } catch (Exception e) {
            PlushPalClient.LOGGER.error("Failed to load plush geo/animations", e);
            return;
        }

        // accessories: one bad file only skips that accessory
        List<Accessory> found = new ArrayList<>();
        var bindings = rm.listResources("accessories/binding", id -> id.getPath().endsWith(".json"));
        for (Identifier bindingId : bindings.keySet()) {
            try {
                found.add(Accessory.load(rm, bindingId, model));
            } catch (Exception e) {
                PlushPalClient.LOGGER.warn("PlushPal: accessory {} not loaded ({})", bindingId, e.toString());
            }
        }
        found.sort(Comparator.comparing(a -> a.name.toLowerCase()));
        accessories = found;
        PlushPalClient.LOGGER.info("PlushPal: loaded {} accessory(ies)", found.size());
    }

    /** Reads plush.animation.json from every pack that has one, so animations can be mixed between packs. */
    private static void loadAnimationPacks(ResourceManager rm) throws Exception {
        Identifier id = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "animations/plush.animation.json");
        Map<String, Map<String, BedrockAnimation>> found = new LinkedHashMap<>();
        for (Resource r : rm.getResourceStack(id)) {       // lowest priority first
            try (BufferedReader reader = r.openAsReader()) {
                found.put(r.sourcePackId(), BedrockAnimation.parseAll(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                PlushPalClient.LOGGER.warn("PlushPal: animations from pack '{}' not loaded ({})", r.sourcePackId(), e.toString());
            }
        }
        if (found.isEmpty()) throw new IllegalStateException("no animations/plush.animation.json found");
        packAnimations = found;
        animationPackIds = List.copyOf(found.keySet());
        remix();
    }

    /** Rebuilds {@link #animations}: highest pack wins unless the settings say a given animation comes from a specific pack. */
    public static void remix() {
        Map<String, Map<String, BedrockAnimation>> packs = packAnimations;
        if (packs.isEmpty()) return;                       // nothing loaded yet
        Map<String, BedrockAnimation> out = new HashMap<>();
        for (Map<String, BedrockAnimation> m : packs.values()) out.putAll(m);   // later (higher) packs overwrite earlier ones
        for (Map.Entry<String, String> e : PlushConfig.get().animationPacks.entrySet()) {
            Map<String, BedrockAnimation> m = packs.get(e.getValue());
            if (m != null && m.containsKey(e.getKey())) out.put(e.getKey(), m.get(e.getKey()));
        }
        animations = out;
    }

    /** The packs that define this animation, lowest priority first. */
    public static List<String> packsWith(String animation) {
        List<String> out = new ArrayList<>();
        for (var e : packAnimations.entrySet()) if (e.getValue().containsKey(animation)) out.add(e.getKey());
        return out;
    }

    /** Friendly pack name: the mod's own animations are "Plush Pal! Default", zipped packs lose their "file/" and ".zip". */
    public static String packName(String packId) {
        if (packId == null) return "";
        if (packId.equals(PlushPalClient.ID) || (!animationPackIds.isEmpty() && packId.equals(animationPackIds.get(0)))) return "Plush Pal! Default";
        String n = packId.startsWith("file/") ? packId.substring(5) : packId;
        return n.endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }

    /** What the preview calls the animations in use: one pack's name, or "Custom mix" when several packs contribute. */
    public static String mixName() {
        Map<String, Map<String, BedrockAnimation>> packs = packAnimations;
        if (packs.isEmpty()) return "Plush Pal! Default";
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, BedrockAnimation> e : animations.entrySet()) {
            String from = null;
            for (var p : packs.entrySet()) if (p.getValue().get(e.getKey()) == e.getValue()) from = p.getKey();
            if (from != null) used.add(from);
        }
        // the built-in pack alone is "default"; when a pack replaced everything, show that pack
        if (used.size() == 1) return packName(used.iterator().next());
        if (used.isEmpty()) return "Plush Pal! Default";
        return "Custom mix (" + used.size() + " packs)";
    }

    private static JsonObject read(ResourceManager rm, String path) throws Exception {
        try (BufferedReader r = rm.getResourceOrThrow(Identifier.fromNamespaceAndPath(PlushPalClient.ID, path)).openAsReader()) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }
}
