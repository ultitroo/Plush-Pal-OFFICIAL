package com.plushpal;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One instance per display context ("first_person", "third_person", "gui", "ground", "fixed", "head"),
 * chosen by the select/display_context in assets/minecraft/items/totem_of_undying.json.
 */
public final class PlushRenderer implements SpecialModelRenderer<Boolean> {
    static final Identifier FLAME_TEX = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/particle/flame.png");
    private static final Identifier SOLID_TEX = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/particle/solid.png"); // white texel for the vertex-coloured flames
    static final Identifier BUBBLE_TEX = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/particle/bubble.png");
    static final Identifier SNOW_TEX = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/particle/snowflake.png");
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "textures/entity/plush.png");
    private static final Pose TARGET = new Pose(), OUT = new Pose(), ACC = new Pose(), ACC_BLEND = new Pose();
    private static final Map<String, State> STATES = new HashMap<>();
    private static final java.util.Set<String> WARNED = new java.util.HashSet<>();
    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

    // ---- live preview (the settings screen draws the plush in "gui" context and drives it from here)
    static volatile boolean preview = false;       // true while the settings screen shows its preview panel
    static volatile float previewZoom = 1f;        // mouse wheel over the preview: 0.3x (far) .. 2x (close)
    static volatile float previewYaw = 0f;         // degrees, turned by dragging the panel
    private static volatile int previewEquipSerial = 0, previewEmoteSerial = 0, previewEmoteWhich = 0;
    private static volatile double previewEmoteTime = -1e9;

    /** Replays the equip animation on the preview plush. */
    static void previewEquip() { previewEquipSerial++; }

    // The preview button picks one animation; once it has finished it waits a second, then plays again.
    private static final double LOOP_PAUSE = 1.0;
    private static volatile String previewLoopId = "idle";
    private static double previewDoneAt = -1;
    static void previewLoop(String id) {
        previewLoopId = id; previewDoneAt = -1;
        previewFrac = 0f; previewReal = realLength(id);
    }

    // Switching animation in the preview: whatever was still playing (an equip, an emote, an old forced state) is dropped
    // at once, so the new pick starts clean and loops by itself instead of queueing behind / being cut by the old one.
    private static volatile int previewCancelSerial = 0;
    static void previewCancel() { previewCancelSerial++; previewState = "idle"; previewDoneAt = -1; }

    // Progress of the picked animation, for the timeline bar under the preview.
    private static volatile float previewFrac = 0f;      // 0..1 through the current run
    private static volatile double previewReal = 0;      // how long one run takes in seconds (speed settings included); 0 = unknown
    static float previewProgress() { return previewFrac; }
    static double previewSeconds() { return previewReal; }

    private static double realLength(String id) {
        PlushConfig cfg = PlushConfig.get();
        PlushConfig.Anim ac = previewAnimFor(cfg, id);
        if (ac == null) return 0;
        BedrockAnimation a = PlushData.animations.get(ac.animation);
        double sp = ac.speed * cfg.globalSpeed;
        return a == null || sp <= 0 ? 0 : a.length / sp;
    }

    /** Called every preview frame: a second after the picked animation has finished (a first run was started), start it again. */
    private void previewReplay(State st, double now) {
        String id = previewLoopId;
        PlushConfig.Anim a = previewAnimFor(PlushConfig.get(), id);
        if (a == null || !a.enabled || id.equals("idle")) { previewDoneAt = -1; return; } // idle loops by itself; a disabled animation can't play
        boolean done = switch (id) {
            case "equip" -> !st.handIn && st.pvEquip == previewEquipSerial;
            case "hi", "yay", "clap" -> !st.hiActive && st.pvEmote == previewEmoteSerial;
            default -> !previewState.equals(id);
        };
        if (!done) { previewDoneAt = -1; return; }
        if (previewDoneAt < 0) { previewDoneAt = now; return; }
        if (now - previewDoneAt < LOOP_PAUSE) return;
        previewDoneAt = -1;
        switch (id) {
            case "equip" -> previewEquip();
            case "hi" -> previewEmote(EmoteTrigger.HI);
            case "yay" -> previewEmote(EmoteTrigger.YAY);
            case "clap" -> previewEmote(EmoteTrigger.CLAP);
            default -> previewSetState(id);
        }
    }

    /** Plays an emote on the preview plush (separate from the real G-key trigger, so your hand plush is not affected). */
    static void previewEmote(int which) { previewEmoteWhich = which; previewEmoteTime = System.nanoTime() / 1e9; previewEmoteSerial++; }

    // ---- preview view (which display context the preview imitates) and forced state
    static final String[] VIEWS = {"first_person", "third_person", "gui", "ground", "fixed", "head"};
    static final String[] VIEW_NAMES = {"First person", "Third person", "Inventory", "On the ground", "Item frame", "On head"};
    private static final Map<String, PlushRenderer> PREVIEWS = new HashMap<>();
    private static volatile String previewView = "gui";
    private static volatile String previewState = "idle";   // idle, swim, fall, fire, freeze, hurt, nervous
    private static volatile double previewStateStart = 0;

    static String previewView() { return previewView; }
    static String previewViewName() { return VIEW_NAMES[viewIndex(previewView)]; }
    private static int viewIndex(String v) { for (int i = 0; i < VIEWS.length; i++) if (VIEWS[i].equals(v)) return i; return 2; }
    static void previewSetView(String v) { previewView = VIEWS[viewIndex(v)]; }
    static void previewStepView(int d) { previewView = VIEWS[Math.floorMod(viewIndex(previewView) + d, VIEWS.length)]; }

    static String previewState() { return previewState; }
    /** Forces the preview plush into a state (the real player is ignored while previewing). */
    static void previewSetState(String s) { previewState = s; previewStateStart = System.nanoTime() / 1e9; }

    /** The animation setting behind a preview button id (equip, hi, yay, clap, idle, swim, fall, fire, freeze, hurt, nervous). */
    static PlushConfig.Anim previewAnimFor(PlushConfig c, String id) {
        return switch (id) {
            case "equip" -> c.handIn; case "hi" -> c.hi; case "yay" -> c.yay; case "clap" -> c.clap;
            case "idle" -> c.idle; case "swim" -> c.idleSwim; case "fall" -> c.fall; case "fire" -> c.onFire;
            case "freeze" -> c.freeze; case "hurt" -> c.hurt; case "nervous" -> c.lowHealth;
            default -> null;
        };
    }

    /** Which particle effects the forced preview state stands for. */
    static int previewEffects(PlushConfig.Particles pc) {
        return switch (previewState) {
            case "fire" -> pc.fire ? PlushParticles.FIRE : 0;
            case "swim" -> pc.bubbles ? PlushParticles.BUBBLES : 0;
            case "freeze" -> pc.snow ? PlushParticles.SNOW : 0;
            default -> 0;
        };
    }

    private static PlushRenderer previewRenderer() { return PREVIEWS.computeIfAbsent(previewView, v -> new PlushRenderer(v, true)); }

    /** The skin the settings preview should wear (same rules as the chosen view in game). */
    static Skin previewSkin() { return previewRenderer().skin(PlushConfig.get()); }

    /** The pose the preview plush has right now: same animation logic as in game, driven by the preview buttons. */
    static Pose previewPose() {
        Pose p = new Pose();
        p.copyFrom(previewRenderer().buildPose(PlushConfig.get()));
        return p;
    }

    private boolean previewing() { return preview && isPreview; }

    private static final class State {
        double lastSeen = -1e9, hurtStart = -1e9, handInStart, fallStart = -1, freezeStart = -1, attackStart = -1e9, hiStart, blendStart = -1e9;
        boolean handIn, swingPrev, hiActive, hurtActive;
        int cue, serial, hiSerial, hiWhich, hurtSerial, pvEquip, pvEmote, pvCancel;
        String chosen = "", current = "";
        final Pose last = new Pose(), from = new Pose();   // for the linear crossfade
        String stateKey = "";                              // which plush state is playing (equip, fall, idle, ...)
        double stateStart, stateSpeed = 1;
        String accKey = "";                                // which accessory animations are playing (for their crossfade)
        double accBlendStart = -1e9;
        final Pose accLast = new Pose(), accFrom = new Pose();
    }

    private final String context;
    private final boolean isPreview;   // the settings screen's own instances; they never share state with the ones in game
    PlushRenderer(String context) { this(context, false); }
    private PlushRenderer(String context, boolean isPreview) { this.context = context; this.isPreview = isPreview; }
    private String stateId() { return (isPreview ? "pv:" : "") + context; }

    // ---------------------------------------------------------------- SpecialModelRenderer

    @Override public Boolean extractArgument(ItemStack stack) { return Boolean.TRUE; }

    @Override public void getExtents(Consumer<Vector3fc> output) {
        output.accept(new Vector3f(0.1f, 0f, 0.1f));
        output.accept(new Vector3f(0.9f, 1.3f, 0.9f));
    }

    @Override
    public void submit(@Nullable Boolean arg, PoseStack poseStack, SubmitNodeCollector collector,
                       int light, int overlay, boolean hasFoil, int outlineColor) {
        PlushConfig cfg = PlushConfig.get();
        if (!cfg.modEnabled) { submitVanilla(poseStack, collector, light, overlay); return; } // mod switched off
        Skin skin = skin(cfg);
        GeoModel model = skin.slim() && PlushData.slimModel != null ? PlushData.slimModel : PlushData.model;
        if (model == null) return;
        if (LOGGED.add(context)) {
            PlushPalClient.LOGGER.info("PlushPal: first draw in context '{}' (first-person camera: {})",
                    context, Minecraft.getInstance().options.getCameraType().isFirstPerson());
        }
        Pose pose = new Pose();
        pose.copyFrom(buildPose(cfg));
        boolean first = context.equals("first_person");
        float s = (float) cfg.scaleFor(context);
        float yaw = previewing() ? (float) Math.toRadians(previewYaw) : 0f;
        float ox = (float) cfg.modelOffset[0], oy = (float) cfg.modelOffset[1], oz = (float) cfg.modelOffset[2];

        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(skin.texture()), (p, vc) -> {
            // item space (0..1, corner origin) -> scale about bottom centre -> pixels -> geo->java offset
            Matrix4f base = new Matrix4f(p.pose())
                    .translate(0.5f, 0f, 0.5f).rotateY(yaw).scale(s).translate(-0.5f, 0f, -0.5f)
                    .translate(ox / 16f, oy / 16f, oz / 16f);
            Matrix4f root = new Matrix4f(base).scale(1f / 16f).translate(8f, 0f, 5f);
            for (GeoModel.Bone b : model.roots) drawBone(b, root, pose, vc, light, overlay);
        });

        // Accessories (cape, ...): each has its own texture, so each is its own draw pass.
        Identifier ownSkin = skin.texture() != TEXTURE ? skin.texture() : null;
        for (Accessory acc : PlushData.accessories) {
            if (!cfg.accessoryOn(acc)) continue;
            Identifier tex = acc.textureFor(ownSkin, skin.cape(), TEXTURE);
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(tex), (p, vc) -> {
                Matrix4f root = new Matrix4f(p.pose())
                        .translate(0.5f, 0f, 0.5f).rotateY(yaw).scale(s).translate(-0.5f, 0f, -0.5f)
                        .translate(ox / 16f, oy / 16f, oz / 16f)
                        .scale(1f / 16f)
                        .translate(8f, 0f, 5f);
                for (GeoModel.Bone b : acc.attached.roots) drawBone(b, root, pose, vc, light, overlay);
            });
        }

        // Interaction particles: part of the plush (drawn in its own space), only for your own plush in hand.
        LocalPlayer me = Minecraft.getInstance().player;
        if (cfg.particles.enabled && me != null && (first || (context.equals("third_person") && mine(me)))) {
            PlushParticles.Set ps = PlushParticles.set(context);
            // Particles live relative to the "particle" locator and are drawn at its CURRENT position, so they ride along with the plush's animation.
            float[] loc = locator(model, "particle", pose);
            ps.update(PlushParticles.effects(me, cfg.particles), cfg.particles, new float[]{0f, 0f, 0f});
            float size = (float) cfg.particles.size;
            particlePass(collector, poseStack, ps.fire, FLAME_TEX, true, true, size, s, ox, oy, oz, loc, light, overlay);
            particlePass(collector, poseStack, ps.bubbles, BUBBLE_TEX, false, false, size, s, ox, oy, oz, loc, light, overlay);
            particlePass(collector, poseStack, ps.snow, SNOW_TEX, false, false, size, s, ox, oy, oz, loc, light, overlay);
        }
    }

    private static final Identifier VANILLA_TOTEM = Identifier.fromNamespaceAndPath("minecraft", "textures/item/totem_of_undying.png");

    // Display transforms {rotX, rotY, rotZ, transX, transY, transZ, scale} (degrees / 1/16 blocks) per context.
    // PLUSH_DISPLAY mirrors models/item/plush.json (the base model the game applies before calling us);
    // VANILLA_DISPLAY is minecraft:item/generated, which the real totem uses.
    private static final String[] DISPLAY_CONTEXTS = {"first_person", "third_person", "gui", "ground", "fixed", "head"};
    private static final float[][] PLUSH_DISPLAY = {
            {-24, 119, 0, 6.75f, 0.25f, -7.25f, 0.5f},
            {0, 137, 0, 0, 2, 0, 0.3f},
            {-10, 146, 16, 0, -2, 0, 0.7f},
            {0, 0, 0, 0, 0, 0, 0.7f},
            {0, 0, 0, 0, -1.75f, -2.5f, 0.7f},
            {0, 0, 0, 0, 14.25f, 0, 1f}};
    private static final float[][] VANILLA_DISPLAY = {
            {0, -90, 25, 1.13f, 3.2f, 1.13f, 0.68f},
            {0, 0, 0, 0, 3, 1, 0.55f},
            {0, 0, 0, 0, 0, 0, 1f},
            {0, 0, 0, 0, 2, 0, 0.5f},
            {0, 180, 0, 0, 0, 0, 0.5f},
            {0, 180, 0, 0, 13, 7, 1.03f}};

    private static Matrix4f display(float[] t) {
        return new Matrix4f().translate(t[3] / 16f, t[4] / 16f, t[5] / 16f)
                .rotateXYZ((float) Math.toRadians(t[0]), (float) Math.toRadians(t[1]), (float) Math.toRadians(t[2]))
                .scale(t[6]);
    }

    /**
     * With the mod switched off the item has to look like the plain vanilla totem. The game has already applied the
     * plush model's display transform, so this swaps it for the vanilla one (undo the plush's, apply the totem's) and
     * draws the totem sprite as a full-size, thin, double-sided card in the same spot a normal item model would be.
     */
    private void submitVanilla(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay) {
        int idx = 3; // unknown contexts count as "ground"
        for (int i = 0; i < DISPLAY_CONTEXTS.length; i++) if (DISPLAY_CONTEXTS[i].equals(context)) idx = i;
        Matrix4f fix = new Matrix4f().translate(0.5f, 0.5f, 0.5f)
                .mul(display(PLUSH_DISPLAY[idx]).invert())
                .mul(display(VANILLA_DISPLAY[idx]))
                .translate(-0.5f, -0.5f, -0.5f);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(VANILLA_TOTEM), (p, vc) -> {
            Matrix4f m = new Matrix4f(p.pose()).mul(fix);
            Vector3f tmp = new Vector3f();
            float[][] c = {{0f, 1f, 0, 0}, {1f, 1f, 1, 0}, {1f, 0f, 1, 1}, {0f, 0f, 0, 1}}; // x, y, u, v
            for (int side = 0; side < 2; side++) {
                float z = side == 0 ? 0.5f - 1f / 32f : 0.5f + 1f / 32f; // one pixel thick, like the real item
                for (int k = 0; k < 4; k++) {
                    float[] q = c[side == 0 ? k : 3 - k];
                    m.transformPosition(tmp.set(q[0], q[1], z));
                    vc.addVertex(tmp.x, tmp.y, tmp.z).setColor(255, 255, 255, 255).setUv(q[2], q[3])
                            .setOverlay(overlay).setLight(light).setNormal(0f, 0f, side == 0 ? -1f : 1f);
                }
            }
        });
    }

    private void particlePass(SubmitNodeCollector collector, PoseStack poseStack, java.util.List<PlushParticles.P> list, Identifier tex,
                              boolean glow, boolean flame, float size, float s, float ox, float oy, float oz, float[] loc, int light, int overlay) {
        if (list.isEmpty()) return;
        java.util.List<PlushParticles.P> snapshot = new java.util.ArrayList<>(list);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutCull(tex), (p, vc) -> {
            Matrix4f root = new Matrix4f(p.pose())
                    .translate(0.5f, 0f, 0.5f).scale(s).translate(-0.5f, 0f, -0.5f)
                    .translate(ox / 16f, oy / 16f, oz / 16f)
                    .scale(1f / 16f)
                    .translate(8f, 0f, 5f)
                    .translate(loc[0], loc[1], loc[2]);
            PlushParticles.draw(snapshot, root, vc, glow ? 0xF000F0 : light, overlay, size,
                    flame ? PlushParticles.KIND_FLAME : tex == BUBBLE_TEX ? PlushParticles.KIND_BUBBLE : PlushParticles.KIND_SNOW); // flames are fullbright
        });
    }

    record Skin(Identifier texture, boolean slim, Identifier cape) {}

    /** Local player's own skin for the configured contexts (plus whether it is slim), else the bundled texture. */
    Skin skin(PlushConfig cfg) {
        if (cfg.usePlayerSkin && cfg.playerSkinContexts.contains(context)) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            boolean mineOk = isPreview || !context.equals("third_person") || (p != null && mine(p)); // other people's plushes keep the bundled skin
            if (mineOk) {
                if (!"own".equals(cfg.skinSource)) { // Steve / Alex / username / offline file
                    Skin s = SkinSource.resolve(cfg);
                    return s != null ? s : new Skin(TEXTURE, false, null); // still loading: bundled skin for a moment
                }
                if (p != null) {
                    var skin = p.getSkin();
                    return new Skin(skin.body().texturePath(), cfg.slimSkins && SkinUtil.isSlim(skin), SkinUtil.cape(skin));
                }
            }
        }
        return new Skin(TEXTURE, false, null);
    }

    /**
     * The renderer can't see who holds the item, so "mine" is inferred:
     * - Punchy (when loaded) draws your in-hand item with other display contexts, so everything in hand is yours;
     * - you are holding a totem; or
     * - you held one within the last two seconds, which covers the lowering animation after you unequip
     *   (the item is still drawn then, but your hand is already empty).
     */
    private static boolean mine(LocalPlayer p) {
        if (Compat.punchyActive()) return true;
        if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return true;
        return System.nanoTime() / 1e9 - EquipTracker.lastHeld < 2.0;
    }

    // ---------------------------------------------------------------- animation selection

    private Pose buildPose(PlushConfig cfg) {
        Pose target = TARGET, out = OUT;
        target.clear();
        if (!cfg.animationsEnabled || PlushData.animations.isEmpty()) { out.clear(); accessoriesOnly(cfg, out); return out; }

        boolean first = context.equals("first_person");
        boolean hand = first || context.equals("third_person");
        if (!hand) {
            boolean ok = switch (context) {
                case "gui" -> cfg.animateInGui;
                case "fixed" -> cfg.animateInFixed;
                case "head" -> cfg.animateOnHead;
                default -> cfg.animateOnGround;
            };
            if (!ok && !previewing()) { out.clear(); accessoriesOnly(cfg, out); return out; }
        }

        double now = System.nanoTime() / 1e9;
        State st = STATES.computeIfAbsent(stateId(), k -> {
            State n = new State(); n.serial = EquipTracker.serial; n.hurtSerial = EquipTracker.hurtSerial; n.hiSerial = EmoteTrigger.serial; n.pvEquip = previewEquipSerial; n.pvEmote = previewEmoteSerial; n.pvCancel = previewCancelSerial; return n;
        });
        if (now - st.lastSeen > 3.0) { st.fallStart = -1; st.freezeStart = -1; st.attackStart = -1e9; st.swingPrev = false; } // long gap: reset
        st.lastSeen = now;
        // Hand-in only starts when the client tick sees a real equip, so frame lag can't replay it (or its sound).
        if (hand && st.serial != EquipTracker.serial) {
            st.serial = EquipTracker.serial; st.handIn = true; st.handInStart = now; st.cue = 0;
        }

        if (previewing() && st.pvCancel != previewCancelSerial) { // a new preview pick: stop the old one-shots first
            st.pvCancel = previewCancelSerial; st.handIn = false; st.hiActive = false;
        }
        if (previewing() && st.pvEquip != previewEquipSerial) { // preview's Equip button
            st.pvEquip = previewEquipSerial; st.handIn = true; st.handInStart = now; st.cue = 0;
        }

        st.chosen = "";
        st.stateKey = "";
        select(cfg, st, now, hand, first, target);
        if (previewing()) previewReplay(st, now);

        // Linear crossfade whenever the chosen animation changes (from the last frame's pose to the new one).
        if (!st.chosen.equals(st.current)) { st.from.copyFrom(st.last); st.current = st.chosen; st.blendStart = now; }
        double t = cfg.transitionTime;
        if (t > 0 && now - st.blendStart < t) Pose.lerp(st.from, target, (float) ((now - st.blendStart) / t), out);
        else out.copyFrom(target);
        st.last.copyFrom(out);
        // Accessories animate on top of the plush. They get the same state names (equip, fall, freeze, ...) and
        // fade between their own animations the same way.
        boolean anyAcc = false;
        if (cfg.animateAccessories) {
            Pose acc = ACC;
            acc.clear();
            String state = st.stateKey.isEmpty() ? "idle" : st.stateKey;
            StringBuilder key = new StringBuilder();
            for (Accessory a : PlushData.accessories) {
                if (!cfg.accessoryOn(a)) continue;
                anyAcc = true;
                key.append(a.id).append(':').append(a.applyFor(state, now, cfg.globalSpeed, st.stateStart, st.stateSpeed, acc)).append(';');
            }
            if (anyAcc) {
                String k = key.toString();
                if (!k.equals(st.accKey)) { st.accFrom.copyFrom(st.accLast); st.accKey = k; st.accBlendStart = now; }
                Pose shown = acc;
                if (t > 0 && now - st.accBlendStart < t) {
                    Pose.lerp(st.accFrom, acc, (float) ((now - st.accBlendStart) / t), ACC_BLEND);
                    shown = ACC_BLEND;
                }
                out.overwriteFrom(shown);
                st.accLast.copyFrom(shown);
            }
        }
        if (!anyAcc) { st.accKey = ""; st.accLast.clear(); }
        return out;
    }

    /** Picks the highest-priority animation and writes its pose into {@code pose}. */
    private void select(PlushConfig cfg, State st, double now, boolean hand, boolean first, Pose pose) {
        LocalPlayer self = Minecraft.getInstance().player;
        boolean pv = previewing();
        boolean own = !pv && self != null && (first || (hand && mine(self))); // this is (probably) my plush; never in the preview
        if ((hand || pv) && st.handIn) {
            if (play(cfg.handIn, st.handInStart, now, false, pose, st, own || pv)) return;
            st.handIn = false;
        }
        if (hand || pv) { // G key wave (or the preview's own emote buttons)
            if (pv) {
                if (st.pvEmote != previewEmoteSerial) {
                    st.pvEmote = previewEmoteSerial; st.hiStart = previewEmoteTime; st.hiWhich = previewEmoteWhich; st.hiActive = true;
                }
            } else if (st.hiSerial != EmoteTrigger.serial) {
                st.hiSerial = EmoteTrigger.serial; st.hiStart = EmoteTrigger.time; st.hiWhich = EmoteTrigger.which; st.hiActive = true;
            }
            if (st.hiActive) {
                if (play(cfg.emote(st.hiWhich), st.hiStart, now, false, pose, st, false)) return;
                st.hiActive = false;
            }
        }

        if (pv && forcedPreview(cfg, st, now, pose)) return; // the preview's state buttons

        // Holder state is only known for my own plush: first person, or what Punchy draws in hand.
        LocalPlayer p = own ? self : null;
        if (p != null) {
            // Damage reaction ("ow"): a one-shot that starts when the client tick sees your health drop.
            if (st.hurtSerial != EquipTracker.hurtSerial) {
                st.hurtSerial = EquipTracker.hurtSerial;
                if (now - st.hurtStart > 0.4) { st.hurtStart = EquipTracker.hurtTime; st.hurtActive = true; } // ignore rapid repeats
            }
            if (st.hurtActive) {
                if (play(cfg.hurt, st.hurtStart, now, false, pose, st, false)) return;
                st.hurtActive = false;
            }
            Minecraft mc = Minecraft.getInstance();
            if (p.isOnFire() && play(cfg.onFire, now, now, true, pose, st, false)) return;

            boolean mining = mc.gameMode != null && mc.gameMode.isDestroying() && mc.options.keyAttack.isDown();
            if (mining && play(cfg.mine, 0, now, false, pose, st, false)) return;

            // Freezing (powder snow): plays while the freeze counter is above zero.
            if (p.getTicksFrozen() > 0) {
                if (st.freezeStart < 0) st.freezeStart = now; // loop from its first frame when freezing begins
                if (play(cfg.freeze, st.freezeStart, now, false, pose, st, false)) return;
            } else st.freezeStart = -1;

            boolean falling = !p.onGround() && !p.isInWater() && !p.onClimbable() && !p.isPassenger()
                    && !p.isFallFlying() && (p.getY() - p.yo) < -cfg.fallSpeedThreshold;
            if (falling) {
                if (st.fallStart < 0) st.fallStart = now;
                if (play(cfg.fall, st.fallStart, now, false, pose, st, false)) return;
            } else st.fallStart = -1;

            if (p.isUnderWater() && play(cfg.idleSwim, 0, now, false, pose, st, false)) return; // head under water, not just touching it
            // Low health: the plush gets nervous (replaces idle)
            if (p.getHealth() > 0 && p.getHealth() <= cfg.lowHealthHearts * 2 && play(cfg.lowHealth, 0, now, false, pose, st, false)) return;
        }
        play(cfg.idle, 0, now, false, pose, st, false);
    }

    /** Plays the state picked with the preview's buttons. A one-shot that has finished puts the picker back on Idle. */
    private boolean forcedPreview(PlushConfig cfg, State st, double now, Pose pose) {
        String s = previewState;
        PlushConfig.Anim ac = previewAnimFor(cfg, s);
        if (ac == null || s.equals("idle")) return false;
        boolean hold = s.equals("fire");                       // like in game: keeps burning while it is the state
        if (play(ac, hold ? now : previewStateStart, now, hold, pose, st, false)) return true;
        if (ac.enabled) previewState = "idle";                 // finished (a disabled animation just stays dim)
        return false;
    }

    /** Plush animations are off here, but accessories still play their idle (and "always") animations. */
    private static void accessoriesOnly(PlushConfig cfg, Pose out) {
        if (!cfg.animateAccessories) return;
        double now = System.nanoTime() / 1e9;
        for (Accessory a : PlushData.accessories) if (cfg.accessoryOn(a)) a.applyFor("idle", now, cfg.globalSpeed, 0, 1, out);
    }

    /** Name of the plush state an Anim config belongs to (what accessory bindings use as keys). */
    private static String keyOf(PlushConfig c, PlushConfig.Anim a) {
        if (a == c.handIn) return "equip";
        if (a == c.hi) return "hi";
        if (a == c.yay) return "yay";
        if (a == c.clap) return "clap";
        if (a == c.idleSwim) return "swim_idle";
        if (a == c.fall) return "fall";
        if (a == c.attack) return "attack";
        if (a == c.mine) return "mine";
        if (a == c.onFire) return "on_fire";
        if (a == c.freeze) return "freeze";
        if (a == c.hurt) return "hurt";
        if (a == c.lowHealth) return "low_health";
        return "idle";
    }

    private boolean play(PlushConfig.Anim ac, double start, double now, boolean hold, Pose pose, State st, boolean cues) {
        if (!ac.enabled) return false;
        BedrockAnimation a = PlushData.animations.get(ac.animation);
        if (a == null) {
            if (WARNED.add(ac.animation)) PlushPalClient.LOGGER.warn("PlushPal: animation '{}' not found in plush.animation.json", ac.animation);
            return false;
        }
        PlushConfig cfg = PlushConfig.get();
        double t = (now - start) * ac.speed * cfg.globalSpeed;
        boolean fin = a.isFinished(t, hold);
        if (previewing() && ac == previewAnimFor(cfg, previewLoopId)) { // feed the timeline bar with the picked animation only
            previewFrac = fin || a.length <= 0 ? 1f : (float) Math.max(0, Math.min(1, a.effectiveTime(t, hold) / a.length));
            double sp = ac.speed * cfg.globalSpeed;
            previewReal = sp > 0 ? a.length / sp : 0;
        }
        if (fin) return false;
        if (cues) fireCues(a, t, st, cfg);
        st.chosen = ac.animation;
        st.stateKey = keyOf(cfg, ac);
        st.stateStart = hold ? 0 : start; // "hold" states (on fire) keep re-using start = now
        st.stateSpeed = ac.speed * cfg.globalSpeed;
        a.apply(a.effectiveTime(t, hold), pose,
                ac.rotationIntensity * cfg.globalRotationIntensity,
                ac.positionIntensity * cfg.globalPositionIntensity);
        return true;
    }

    private void fireCues(BedrockAnimation a, double t, State st, PlushConfig cfg) {
        while (st.cue < a.cues.size() && a.cues.get(st.cue).time() <= t) {
            BedrockAnimation.Cue cue = a.cues.get(st.cue++);
            Minecraft mc = Minecraft.getInstance();
            if (!cfg.sound.enabled || mc.level == null || mc.player == null) continue;
            SoundEvent ev = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(PlushPalClient.ID, cue.effect()));
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), ev, SoundSource.PLAYERS,
                    (float) cfg.sound.volume, (float) cfg.sound.pitch, false);
        }
    }

    // ---------------------------------------------------------------- geometry

    /** Where the named locator (e.g. "particle") is right now, in model pixels, following its bone's animation. Falls back to the middle of the plush. */
    static float[] locator(GeoModel model, String name, Pose pose) {
        Vector3f v = new Vector3f();
        Matrix4f id = new Matrix4f();
        for (GeoModel.Bone r : model.roots) if (findLocator(r, id, pose, name, v)) return new float[]{v.x, v.y, v.z};
        return new float[]{0f, 8f, 3f};
    }

    private static boolean findLocator(GeoModel.Bone b, Matrix4f parent, Pose pose, String name, Vector3f out) {
        float[] a = pose.get(b.name);
        float dx = 0, dy = 0, dz = 0, rx = b.rx, ry = b.ry, rz = b.rz;
        if (a != null) { dx = a[0]; dy = a[1]; dz = a[2]; rx += a[3]; ry += a[4]; rz += a[5]; }
        Matrix4f m = new Matrix4f(parent)
                .translate(b.px + dx, b.py + dy, b.pz + dz)
                .rotateZ((float) Math.toRadians(rz)).rotateY((float) Math.toRadians(ry)).rotateX((float) Math.toRadians(rx))
                .translate(-b.px, -b.py, -b.pz);
        float[] l = b.locators.get(name);
        if (l != null) { m.transformPosition(out.set(l[0], l[1], l[2])); return true; }
        for (GeoModel.Bone c : b.children) if (findLocator(c, m, pose, name, out)) return true;
        return false;
    }

    private static void drawBone(GeoModel.Bone b, Matrix4f parent, Pose pose, VertexConsumer vc, int light, int overlay) {
        float[] a = pose.get(b.name);
        float dx = 0, dy = 0, dz = 0, rx = b.rx, ry = b.ry, rz = b.rz;
        if (a != null) { dx = a[0]; dy = a[1]; dz = a[2]; rx += a[3]; ry += a[4]; rz += a[5]; }
        Matrix4f m = new Matrix4f(parent)
                .translate(b.px + dx, b.py + dy, b.pz + dz)
                .rotateZ((float) Math.toRadians(rz)).rotateY((float) Math.toRadians(ry)).rotateX((float) Math.toRadians(rx))
                .translate(-b.px, -b.py, -b.pz);
        for (GeoModel.Cube c : b.cubes) drawCube(c, m, vc, light, overlay);
        for (GeoModel.Bone child : b.children) drawBone(child, m, pose, vc, light, overlay);
    }

    // per face (north,south,east,west,up,down): 4 corners of {xMax?, yMax?, zMax?, uFrac, vFrac}
    static final int[][][] CORNERS = {
            {{1,1,0, 0,0}, {0,1,0, 1,0}, {0,0,0, 1,1}, {1,0,0, 0,1}},
            {{0,1,1, 0,0}, {1,1,1, 1,0}, {1,0,1, 1,1}, {0,0,1, 0,1}},
            {{1,1,1, 0,0}, {1,1,0, 1,0}, {1,0,0, 1,1}, {1,0,1, 0,1}},
            {{0,1,0, 0,0}, {0,1,1, 1,0}, {0,0,1, 1,1}, {0,0,0, 0,1}},
            {{0,1,0, 1,1}, {1,1,0, 0,1}, {1,1,1, 0,0}, {0,1,1, 1,0}},
            {{0,0,0, 1,0}, {1,0,0, 0,0}, {1,0,1, 0,1}, {0,0,1, 1,1}},
    };
    // corner emit order; the down face needs the opposite winding so it faces outward (-Y)
    private static final int[] ORDER_NORMAL = {0, 3, 2, 1};
    private static final int[] ORDER_DOWN = {0, 1, 2, 3};
    static final float[][] NORMALS = {{0,0,-1}, {0,0,1}, {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}};

    private static void drawCube(GeoModel.Cube c, Matrix4f parent, VertexConsumer vc, int light, int overlay) {
        Matrix4f m = parent;
        if (c.rotated) {
            m = new Matrix4f(parent).translate(c.px, c.py, c.pz)
                    .rotateZ((float) Math.toRadians(c.rz)).rotateY((float) Math.toRadians(c.ry)).rotateX((float) Math.toRadians(c.rx))
                    .translate(-c.px, -c.py, -c.pz);
        }
        Vector3f pos = new Vector3f(), nrm = new Vector3f();
        for (int f = 0; f < 6; f++) {
            GeoModel.Face face = c.faces[f];
            if (face == null) continue;
            m.transformDirection(nrm.set(NORMALS[f][0], NORMALS[f][1], NORMALS[f][2])).normalize();
            for (int k : (f == 5 ? ORDER_DOWN : ORDER_NORMAL)) {
                int[] q = CORNERS[f][k];
                m.transformPosition(pos.set(q[0] == 1 ? c.x1 : c.x0, q[1] == 1 ? c.y1 : c.y0, q[2] == 1 ? c.z1 : c.z0));
                vc.addVertex(pos.x, pos.y, pos.z).setColor(255, 255, 255, 255)
                        .setUv(face.u0() + face.du() * q[3], face.v0() + face.dv() * q[4])
                        .setOverlay(overlay).setLight(light).setNormal(nrm.x, nrm.y, nrm.z);
            }
        }
    }

    // ---------------------------------------------------------------- JSON hook

    public record Unbaked(String context) implements SpecialModelRenderer.Unbaked<Boolean> {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("context").forGetter(Unbaked::context)).apply(i, Unbaked::new));

        @Override public MapCodec<Unbaked> type() { return MAP_CODEC; }

        @Override public SpecialModelRenderer<Boolean> bake(SpecialModelRenderer.BakingContext ctx) {
            return new PlushRenderer(context);
        }
    }
}
