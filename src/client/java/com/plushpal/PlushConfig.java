package com.plushpal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** config/plushpal.json. Edit it, use the F12 screen, or run /plushpal reload. */
public final class PlushConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PlushConfig instance = new PlushConfig();

    public static PlushConfig get() { return instance; }

    public static final class Anim {
        public boolean enabled = true;
        /** Name inside plush.animation.json. Swap to use a different animation for this state. */
        public String animation;
        public double speed = 1.0;
        public double rotationIntensity = 1.0;
        public double positionIntensity = 1.0;
        public Anim() {}
        Anim(boolean enabled, String animation) { this.enabled = enabled; this.animation = animation; }
    }

    public static final class Scale {
        public double firstPerson = 1.0, thirdPerson = 1.0, gui = 1.0, ground = 1.0, fixed = 1.0, head = 1.0;
    }

    public static final class Particles {
        public boolean enabled = true;  // on by default
        public boolean fire = true;     // minecraft:flame while you're on fire
        public boolean bubbles = true;  // minecraft:bubble rising while you're underwater
        public boolean snow = true;     // minecraft:snowflake while you're freezing
        public double amount = 1.0;     // multiplier on how many are emitted
        public double size = 1.0;       // multiplier on how big each particle is
    }

    /** Legacy (config v4): migrated into {@link #accessories} on load, then dropped. */
    public static final class Cosmetics {
        public boolean cape = false;     // off by default; uses your cape, or a template if you have none
        public boolean capeSway = true;
    }

    public static final class Sound {
        public boolean enabled = true;
        public double volume = 1.0, pitch = 1.0;
    }

    // ---- master switch ----
    /** Off = the mod does nothing: the totem is drawn as a plain flat sprite, no animations, sounds, particles or emote keys. */
    public boolean modEnabled = true;

    // ---- model ----
    public double scale = 1.0;                       // global multiplier
    public Scale scaleByContext = new Scale();       // multiplied on top of "scale"
    public double[] modelOffset = {0, 0, 0};         // pixels (x, y, z) in item space

    // ---- skin ----
    /** Texture the plush with a player skin (see skinSource) instead of the bundled skin.png. Only the "username" source ever uses another player's skin. */
    public boolean usePlayerSkin = true;
    /** Where the skin comes from: "own" (your skin), "steve", "alex", "username" (see skinUsername) or "file" (config/plushpal/skin.png, works offline). */
    public String skinSource = "own";
    /** Minecraft username used when skinSource is "username". Looked up once online, then kept as a saved copy. */
    public String skinUsername = "";
    /** Arm layout of config/plushpal/skin.png: true = slim (Alex). */
    public boolean customSkinSlim = false;
    /** Contexts that get your skin: first_person, third_person, gui, ground, fixed (item frame + totem pop), head. */
    public List<String> playerSkinContexts = new ArrayList<>(List.of("first_person", "third_person", "gui", "ground", "fixed"));
    /** Use the 3-pixel slim arm layout when your skin is slim (Alex). */
    public boolean slimSkins = true;
    public int configVersion = 6;

    // ---- animation ----
    public boolean animationsEnabled = true;
    /** Animation mix: animation name -> id of the resource pack it should come from. Missing = the pack order decides (highest pack wins). */
    public Map<String, String> animationPacks = new java.util.LinkedHashMap<>();
    public double globalSpeed = 1.0;
    public double globalRotationIntensity = 1.0;
    public double globalPositionIntensity = 1.0;
    public double fallSpeedThreshold = 0.15;         // blocks/tick downward before "fall" plays
    public boolean animateInGui = true, animateOnGround = true, animateInFixed = true, animateOnHead = true;

    /** Seconds to blend linearly between two animations (0 = instant snap). */
    public double transitionTime = 0.2;

    public Anim handIn   = new Anim(true,  "plush_pull_up_w_sound");
    public Anim idle     = new Anim(true,  "plush_idle_loop");
    public Anim idleSwim = new Anim(true,  "plush_idle_swim");
    public Anim fall     = new Anim(true,  "plush_fall");
    public Anim attack   = new Anim(false, "plush_attack");
    public Anim mine     = new Anim(false, "plush_mine");
    public Anim onFire   = new Anim(false, "plush_on_fire");
    /** Played while you are freezing (powder snow). Author it in plush.animation.json. */
    public Anim freeze   = new Anim(true,  "plush_freezing_start");
    /** One-shot reaction when you take damage. */
    public Anim hurt     = new Anim(true,  "plush_ow");
    /** Loops while your health is low, instead of idle. */
    public Anim lowHealth = new Anim(true, "plush_nervous");
    /** Health (in hearts) at or below which the plush gets nervous. */
    public double lowHealthHearts = 3.0;

    // ---- emotes (G key + emote wheel) ----
    public Anim hi   = new Anim(true, "plush_hi");
    public Anim yay  = new Anim(true, "plush_yay");
    public Anim clap = new Anim(true, "plush_clap");
    /** What the G key plays: "random" (any enabled emote), "yay", "clap" or "hi". */
    public String quickEmote = "random";
    /** Emote in each wheel slot: upper-left, upper-right, right, lower-right, lower-left, left. "" = empty. */
    public List<String> wheelSlots = new ArrayList<>(List.of("hi", "yay", "clap", "", "", ""));

    public Sound sound = new Sound();
    public Particles particles = new Particles();
    public Cosmetics cosmetics; // legacy, migrated to "accessories" and then removed

    /** Accessory id (namespace:file) -> shown? Anything not listed uses the binding's default_enabled (off). */
    public Map<String, Boolean> accessories = new HashMap<>();
    public boolean animateAccessories = true;

    public boolean accessoryOn(Accessory a) {
        Boolean b = accessories.get(a.id);
        return b != null ? b : a.defaultEnabled;
    }

    public void setAccessory(String id, boolean on) { accessories.put(id, on); }

    // ---- UI ----
    public boolean uiAnimations = true;   // fades, sliding toggles, hover easing
    public double uiAnimSpeed = 1.0;
    public boolean uiSounds = true;
    public double uiVolume = 1.0;

    public Anim emote(int which) {
        return switch (which) { case EmoteTrigger.YAY -> yay; case EmoteTrigger.CLAP -> clap; default -> hi; };
    }

    public double scaleFor(String ctx) {
        double s = switch (ctx) {
            case "first_person" -> scaleByContext.firstPerson;
            case "third_person" -> scaleByContext.thirdPerson;
            case "gui" -> scaleByContext.gui;
            case "fixed" -> scaleByContext.fixed;
            case "head" -> scaleByContext.head;
            default -> scaleByContext.ground;
        };
        return Math.max(0.01, Math.min(20, scale * s));
    }

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("plushpal.json");
        PlushConfig c = new PlushConfig();
        try {
            if (Files.exists(file)) {
                PlushConfig read = GSON.fromJson(Files.readString(file), PlushConfig.class);
                if (read != null) c = read;
            }
        } catch (Exception e) {
            PlushPalClient.LOGGER.error("Bad plushpal.json, using defaults", e);
        }
        c.sanitize();
        instance = c;
        PlushData.remix();
        save();
    }

    /** The live settings as JSON (for presets, share strings and undo). */
    public static String toJson() { return GSON.toJson(instance); }

    /** Replaces the live settings with the ones in {@code json}. Not saved until {@link #save()}. False if it isn't valid. */
    public static boolean applyJson(String json) {
        try {
            PlushConfig c = GSON.fromJson(json, PlushConfig.class);
            if (c == null) return false;
            c.sanitize();
            instance = c;
            PlushData.remix();
            return true;
        } catch (Exception e) { return false; }
    }

    /** Replaces the live settings with defaults. Not saved until {@link #save()} (so it can still be discarded). */
    public static void resetToDefaults() {
        PlushConfig c = new PlushConfig();
        c.sanitize();
        instance = c;
        PlushData.remix();
    }

    /** A fresh, sanitised copy of the defaults (never the live settings). Used to find which settings differ from the defaults. */
    public static PlushConfig defaults() {
        PlushConfig c = new PlushConfig();
        c.sanitize();
        return c;
    }

    /** What is on disk right now; used to tell whether the live settings have unsaved changes. */
    private static String savedJson = "";

    public static boolean isDirty() { return !GSON.toJson(instance).equals(savedJson); }

    /** Throws away unsaved changes by reloading the last saved settings. */
    public static void discardChanges() {
        PlushConfig c = null;
        try { c = GSON.fromJson(savedJson, PlushConfig.class); } catch (Exception ignored) { }
        if (c == null) c = new PlushConfig();
        c.sanitize();
        instance = c;
        PlushData.remix();
    }

    public static void save() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("plushpal.json");
        try {
            String json = GSON.toJson(instance);
            Files.writeString(file, json);
            savedJson = json;
        } catch (IOException e) { PlushPalClient.LOGGER.error("Could not save config", e); }
    }

    private static Anim ensure(Anim a, boolean enabled, String name) {
        if (a == null) return new Anim(enabled, name);
        if (a.animation == null) a.animation = name;
        a.speed = Math.max(0, a.speed);
        return a;
    }

    private void sanitize() {
        if (modelOffset == null || modelOffset.length < 3) modelOffset = new double[]{0, 0, 0};
        if (scaleByContext == null) scaleByContext = new Scale();
        if (sound == null) sound = new Sound();
        if (particles == null) particles = new Particles();
        if (accessories == null) accessories = new HashMap<>();
        if (animationPacks == null) animationPacks = new java.util.LinkedHashMap<>();
        particles.amount = Math.max(0, Math.min(3, particles.amount));
        particles.size = Math.max(0.5, Math.min(2, particles.size));

        playerSkinContexts = playerSkinContexts == null
                ? new ArrayList<>(List.of("first_person", "third_person", "gui", "ground", "fixed"))
                : new ArrayList<>(playerSkinContexts);
        if (configVersion < 2 && !playerSkinContexts.contains("third_person")) playerSkinContexts.add("third_person");
        if (configVersion < 3) { // v3: ground + item frame/totem pop use your skin by default
            if (!playerSkinContexts.contains("ground")) playerSkinContexts.add("ground");
            if (!playerSkinContexts.contains("fixed")) playerSkinContexts.add("fixed");
        }
        if (configVersion < 6) particles.enabled = true;  // v6: particles are on by default (also turned on for older configs once)
        if (configVersion < 5 && cosmetics != null) { // v5: cosmetics became data-driven accessories
            if (cosmetics.cape) accessories.put("plushpal:cape", true);
            if (!cosmetics.capeSway) animateAccessories = false;
        }
        cosmetics = null;
        configVersion = 6;
        if (skinSource == null || !List.of("own", "steve", "alex", "username", "file").contains(skinSource)) skinSource = "own";
        if (skinUsername == null) skinUsername = "";

        handIn = ensure(handIn, true, "plush_pull_up_w_sound");
        idle = ensure(idle, true, "plush_idle_loop");
        idleSwim = ensure(idleSwim, true, "plush_idle_swim");
        fall = ensure(fall, true, "plush_fall");
        attack = ensure(attack, false, "plush_attack");
        mine = ensure(mine, false, "plush_mine");
        onFire = ensure(onFire, false, "plush_on_fire");
        freeze = ensure(freeze, true, "plush_freezing_start");
        hurt = ensure(hurt, true, "plush_ow");
        lowHealth = ensure(lowHealth, true, "plush_nervous");
        lowHealthHearts = Math.max(0.5, Math.min(10, lowHealthHearts));
        hi = ensure(hi, true, "plush_hi");
        yay = ensure(yay, true, "plush_yay");
        clap = ensure(clap, true, "plush_clap");

        List<String> slots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String v = wheelSlots != null && i < wheelSlots.size() ? wheelSlots.get(i) : (i < 3 ? List.of("hi", "yay", "clap").get(i) : "");
            slots.add(List.of("hi", "yay", "clap").contains(v) ? v : "");
        }
        wheelSlots = slots;
        if (quickEmote == null || !List.of("random", "yay", "clap", "hi").contains(quickEmote)) quickEmote = "random";
        transitionTime = Math.max(0, Math.min(2, transitionTime));
        globalSpeed = Math.max(0, globalSpeed);
        uiVolume = Math.max(0, Math.min(2, uiVolume));
        uiAnimSpeed = Math.max(0.5, Math.min(2, uiAnimSpeed));
    }
}
