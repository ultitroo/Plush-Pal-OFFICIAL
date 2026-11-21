package com.plushpal;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Settings window (open with F12). Three layout ideas on top of the Bedrock look:
 * <ul>
 *   <li><b>Home = card grid.</b> Opening the screen shows one big tile per category; click one to open it.</li>
 *   <li><b>Icon rail.</b> Inside a category a slim icon-only rail replaces the wide text sidebar. Hover an icon for its
 *       name; the top entry (grid glyph) goes back home.</li>
 *   <li><b>Live preview.</b> A panel on the right shows your plush animating with the current settings. Drag it to
 *       turn the plush, press Equip / Hi / Yay / Clap to replay animations. It hides itself on narrow windows
 *       and can be switched off with the Preview button.</li>
 * </ul>
 * Also: a search box + "Changed" filter in the banner (this page or every page), a reset arrow on every changed setting,
 * double-click / Ctrl+click / Shift+drag on sliders, and a preview with a view picker and state buttons.
 * Visuals are drawn by hand; input comes from invisible vanilla widgets underneath, so clicking, dragging and
 * hover behave like normal widgets. Scrolling moves one row at a time.
 */
public final class PlushConfigScreen extends Screen {
    // ---- palette (sampled from Bedrock's settings screens)
    private static final int DIM = 0x99000000, FRAME = 0xFF000000,
            HEADER_BG = 0xFFE6E6E6, HEADER_LINE = 0xFF5B5B5C, HEADER_TEXT = 0xFF1E1E1F, HEADER_MUTED = 0xFF77777A,
            SIDEBAR_BG = 0xFF2F3033, CONTENT_BG = 0xFF47484B, SEPARATOR = 0xFF353637,
            SEL_BG = 0xFF4F5051, SEL_TOP = 0xFF6B6C6D, SEL_BOTTOM = 0xFF262627,
            CARD_BG = 0xFF3A3B3C, CARD_HOVER = 0xFF4A4B4C, CARD_EDGE = 0xFF2A2B2C, STITCH = 0xFF6B6C6D,
            DARK = 0xFF1E1E1F, FACE = 0xFFD0D1D4, FACE_HI = 0xFFEDEEF0, FACE_SHADOW = 0xFF58585A,
            GREEN = 0xFF3C8527, GREEN_HI = 0xFF5FB04A, GREEN_SHADOW = 0xFF1D4D13, GREEN_DEEP = 0xFF2D6B1E,
            OFF_BOX = 0xFF8B8B8B, TRACK = 0xFF2B2B2C, SCROLL_TRACK = 0xFF2B2B2C, SCROLL_THUMB = 0xFFF2F2F2,
            TITLE = 0xFFFFFFFF, DESC = 0xFFB8B8B8, WHITE = 0xFFFFFFFF, UNSAVED = 0xFFB4501A, CHANGED = 0xFFF0A030,
            BAR_FILL = 0xFF3C8527, BAR_FILL_HI = 0xFF639D52, BAR_FILL_SHADOW = 0xFF1D4D13, // the green of button_green.png / toggle_on.png
             BAR_BORDER = 0xFF111112, HANDLE = 0xFFF2F2F2, HANDLE_HOVER = 0xFFFFFFFF;
    private static final int WIN_W_MAIN = 500, WIN_H_MAIN = 290, WIN_W_HOME = 300, WIN_W_HOME_PREVIEW = 376, WIN_H_HOME = 270;   // base sizes: the window grows with the screen from here (see fitWindow)
    private static final int FOOT_H = 30, RESET_W = 20, RESET_H = 20, BAR_H = 10, HEADER_H = 26, SCROLL_W = 8, CONTROL_W = 130, RAIL_W = 30, PREVIEW_W_SUB = 100, BANNER_H = 30, HOME_BANNER_H = 32, CLOSE_W = 34, SWITCH_W = 45, SWITCH_H = 24, TOGGLE_W = 45, TOGGLE_H = 24; // switches are always drawn at this fixed size (the art is 30 x 16, shown at 1.5x)

    private enum Tab {
        // grouped three by three, exactly as the home grid shows them (see GROUPS)
        GENERAL("General", "icons/general"), LAYOUT("Layout", "icons/layout"), SOUND("Sound", "icons/sound"),
        SKIN("Skin", "icons/skin"), COSMETICS("Accessories", "icons/cosmetics"), SCALE("Scale", "icons/scale"),
        ANIMATIONS("Animations", "icons/animations"), EMOTES("Emotes", "icons/emotes"), PARTICLES("Particles", "icons/particles"),
        PRESETS("Presets", "icons/presets");
        final String label, icon;
        Tab(String label, String icon) { this.label = label; this.icon = icon; }
    }

    /** Rail entries: index 0 is "home" (null), then every tab. */
    private static final Tab[] ENTRIES = new Tab[Tab.values().length + 1];
    static { System.arraycopy(Tab.values(), 0, ENTRIES, 1, Tab.values().length); }

    /** Home grid rows: one headline per row of three categories. */
    private static final String[] GROUPS = {"BASICS", "APPEARANCE", "MOTION", "SAVED SETUPS"};
    private static final int GROUP_LABEL_H = 11;

    private static final String[] ANIM_NAMES = {"Equip", "Hi", "Yay", "Clap", "Idle", "Swim idle", "Fall", "Attack", "Mine", "On fire", "Freeze", "Hurt (ow)", "Nervous"};
    private static final String[] QUICK_IDS = {"random", "yay", "clap", "hi"};
    private static final String[] QUICK_NAMES = {"Random", "Yay", "Clap", "Hi"};
    private static final String[] SLOT_IDS = {"", "hi", "yay", "clap"};
    private static final String[] SLOT_NAMES = {"Empty", "Hi", "Yay", "Clap"};

    private static Tab tab = null;        // null = home (card grid); remembered between openings
    private static boolean showPreview = true;
    private static int animIndex = 0;
    private static int scroll = 0;        // first visible row
    private static int tabScroll = 0;     // first visible rail entry
    private static int lastHover = 0;     // for the hover sound
    private static float selAnimY = -1;   // the selected-entry highlight slides to its entry

    /**
     * One line on a page. {@code value}/{@code setter} exist only for real settings (not headers or buttons): they are what
     * makes "changed" detection, the reset arrow and double-click-to-reset possible. {@code key} is stable (English title).
     */
    private static final class Def {
        final Tab tab;
        final String key, title, desc, section;
        final IntConsumer build;
        final Object value;               // the setting's value when the page was built (null = not a resettable setting)
        final Consumer<Object> setter;
        Def(Tab tab, String key, String section, String title, String desc, IntConsumer build, Object value, Consumer<Object> setter) {
            this.tab = tab; this.key = key; this.section = section; this.title = title; this.desc = desc;
            this.build = build; this.value = value; this.setter = setter;
        }
    }
    private record Row(String title, String desc, int y, Tab tab, String key) {}
    private record Reset(HitButton b, String key) {}
    private record TabHit(int index, HitButton b) {}
    private record Card(Tab tab, HitButton b, int count) {}
    private record Tile(Accessory a, int x, int y) {}
    private record Toggle(Button b, BooleanSupplier get, float[] pos) {} // pos: knob 0 (left) .. 1 (right), animated
    private record Styled(Button b, String text, int face, int hi, int shadow, int textColor, boolean bold) {}

    private final List<Def> defs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private final List<Styled> buttons = new ArrayList<>();
    private final List<TabHit> tabHits = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<AbstractWidget> hoverables = new ArrayList<>();
    private int wx, wy, ww, wh, rowH, contentX, ctlRight, rowsTop, tabTop, tabH, visible, total;
    private int sbX, sbTop, sbBottom, thumbY, thumbH, titleMaxW, tabVisible;
    private int mainRight, btnLeft, cardW, cardH, previewW;
    private boolean previewOn, previewFits;
    private int previewX, previewTop, previewBottom, previewBtnTop, previewBarTop;
    private long pageNs = System.nanoTime();   // restarts when you switch page (card pop-in)
    private long rowsNs = System.nanoTime();   // rows fade in one after another from here (page switch or scroll)
    private long rowsStep = 28_000_000L;       // delay between consecutive rows
    private String railTip;
    private int railTipY;
    private long railTipNs;
    private int homeX0, homeX1, bannerColor = 0xFF5FB04A;
    private Slider yawSlider;
    private final List<Tile> tiles = new ArrayList<>();
    private final List<Reset> resets = new ArrayList<>();
    private HitButton animBtn, animPrev, scopeBtn, changedBtn, undoBtn, closeBtn, doneBtn, replayBtn;
    private final String openJson = PlushConfig.toJson();   // the settings as they were when this screen opened: what "Undo changes" goes back to
    private String undoCountFor = null;
    private int undoCount = 0;
    private EditBox searchBox;
    private int searchX;                       // left edge of the search box (the banner text stops before it)
    private boolean searchDirty, refocusSearch;
    private static String searchText = "";
    private static boolean searchAll = false;  // false = this page only, true = every page
    private static boolean changedOnly = false;
    private static String pvCurrent = "idle";  // preview: the animation picked last (shown on the top-left button)
    private static final String[] PV_IDS = {"equip", "hi", "yay", "clap", "idle", "swim", "fall", "fire", "freeze", "hurt", "nervous"};
    private static final String[] PV_LABELS = {"Equip", "Hi", "Yay", "Clap", "Idle", "Swim", "Fall", "On fire", "Freeze", "Hurt", "Nervous"};
    // building rows: which tab / section / key prefix the row definitions currently belong to
    private Tab curTab = Tab.GENERAL;
    private String curSection = "", keyPrefix = "";
    // changed-settings analysis (rebuilt when the settings change)
    private static final class Analysis {
        final Map<String, Object> defaults = new HashMap<>();
        final Set<String> changed = new HashSet<>();
        final int[] perTab = new int[Tab.values().length];
        int total;
    }
    private Analysis analysis;
    private String analysisJson = "";
    private long analysisNs;
    // mouse polling (double-click to reset, Ctrl+click to type a value)
    private boolean mouseWas;
    private Slider lastClickSlider;
    private long lastClickNs;
    private int lastClickX;
    private static List<String> SAVED_PRESETS = new ArrayList<>();
    private static int testSoundIndex = 0;
    private String toastText;
    private long toastNs;
    // undo: a snapshot of the settings is pushed whenever they change and no mouse button is held (so a whole slider drag is one step)
    private static final Deque<String> UNDO = new ArrayDeque<>();
    private String lastSeen = PlushConfig.toJson();
    private boolean[] keyWas = new boolean[11];
    private Button masterSwitch;
    private long lastPreviewNs;
    private static float autoYaw = 0f; // the preview slowly turns on its own (so you see the back, cape and all) until you grab it

    /** Makes the next PlushConfigScreen open on the Emotes tab. */
    public static void showEmotesTab() { tab = Tab.EMOTES; scroll = 0; }

    private final Screen parent; // set when opened from Mod Menu, so closing returns to its list
    private long openNs = System.nanoTime();
    private String lastTooltip;

    /** Coming back from a dialog: the window dissolves in again instead of just appearing. */
    void replayOpen() { openNs = System.nanoTime(); closingNs = 0L; closeQueued = false; afterClose = null; dimHeld = true; }
    private boolean dimHeld;   // came back from a dialog: the dim behind was already there, so it does not fade in again
    private long tooltipNs;

    public PlushConfigScreen() {
        this(null);
    }

    public PlushConfigScreen(Screen parent) {
        super(Component.literal("PlushPal Settings"));
        this.parent = parent;
        searchText = ""; changedOnly = false; searchAll = false;
        PlushRenderer.previewSetState("idle"); PlushRenderer.previewSetView("gui");
        selAnimY = -1;
        pvCurrent = "idle"; PlushRenderer.previewLoop("idle");
        UiDraw.resetAnimations();
        PlushPreview.reset();
    }

    // ------------------------------------------------------------------ layout

    private void build(Tab t, PlushConfig c) {
        curTab = t; curSection = ""; keyPrefix = "";
        switch (t) {
            case GENERAL -> general(c);
            case SCALE -> scale(c);
            case LAYOUT -> layout(c);
            case ANIMATIONS -> animations(c);
            case EMOTES -> emotes(c);
            case PARTICLES -> particles(c);
            case SOUND -> sound(c);
            case SKIN -> skin(c);
            case COSMETICS -> cosmetics(c);
            case PRESETS -> presets(c);
        }
    }

    /**
     * The row definitions of one tab for the given settings object, without touching what is on this page. With
     * {@code expanded} the Animations tab lists every animation instead of just the picked one (for search / "Changed").
     */
    private List<Def> defsFor(Tab t, PlushConfig c, boolean expanded) {
        List<Def> saved = new ArrayList<>(defs);
        Tab savedTab = curTab; String savedSection = curSection, savedPrefix = keyPrefix;
        defs.clear();
        if (t == Tab.ANIMATIONS && expanded) {
            curTab = t; curSection = ""; keyPrefix = "";
            for (int i = 0; i < ANIM_NAMES.length; i++) animationBlock(c, i);
        } else build(t, c);
        List<Def> out = new ArrayList<>(defs);
        defs.clear();
        defs.addAll(saved);
        curTab = savedTab; curSection = savedSection; keyPrefix = savedPrefix;
        return out;
    }

    /** How many settings a tab has (for the number on its card). Builders only record row definitions, so this is cheap. */
    private int countFor(Tab t, PlushConfig c) {
        int n = 0;
        for (Def d : defsFor(t, c, false)) if (d.desc != null) n++;
        return n;
    }

    // ---- "changed" detection: every tab is built once with your settings and once with the defaults, then compared row by row

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Math.abs(x.doubleValue() - y.doubleValue()) < 1e-4;
        return Objects.equals(a, b);
    }

    private Analysis analysis() {
        if (analysis == null) recomputeAnalysis();
        return analysis;
    }

    private void recomputeAnalysis() {
        PlushConfig live = PlushConfig.get(), def = PlushConfig.defaults();
        Analysis a = new Analysis();
        for (Tab t : Tab.values()) {
            Map<String, Def> byKey = new HashMap<>();
            for (Def d : defsFor(t, def, true)) if (d.value != null) byKey.put(d.key, d);
            for (Def d : defsFor(t, live, true)) {
                if (d.value == null) continue;
                Def dd = byKey.get(d.key);
                if (dd == null) continue;
                a.defaults.put(d.key, dd.value);
                if (!same(d.value, dd.value)) { a.changed.add(d.key); a.perTab[t.ordinal()]++; a.total++; }
            }
        }
        analysis = a;
        analysisJson = PlushConfig.toJson();
        analysisNs = System.nanoTime();
    }

    /** Called every frame: re-runs the analysis shortly after the settings changed (not on every frame of a slider drag). */
    private void refreshAnalysis(String json) {
        if (analysis != null && !json.equals(analysisJson) && System.nanoTime() - analysisNs > 150_000_000L) recomputeAnalysis();
    }

    private boolean isChanged(String key) { return analysis().changed.contains(key); }

    private void resetDef(Def d) {
        Object def = analysis().defaults.get(d.key);
        if (def == null || d.setter == null) return;
        d.setter.accept(def);
        rebuildWidgets();
    }

    // ---- search / "Changed" filter

    private boolean filtering() { return !searchText.isEmpty() || changedOnly; }

    private boolean matches(Def d) {
        if (changedOnly && (d.value == null || !isChanged(d.key))) return false;
        if (searchText.isEmpty()) return true;
        String hay = (d.title + " " + d.desc + " " + d.section + " " + d.tab.label).toLowerCase(Locale.ROOT);
        for (String w : searchText.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    /** The filtered page: only rows that match, each kept with its section header (named "Tab / Section" when searching every page). */
    private void buildFiltered(PlushConfig c) {
        List<Tab> scope = searchAll ? List.of(Tab.values()) : List.of(tab);
        for (Tab t : scope) {
            Def header = null;
            for (Def d : defsFor(t, c, true)) {
                if (d.desc == null) { header = d; continue; }
                if (!matches(d)) continue;
                if (header != null) {
                    String title = searchAll ? t.label + " / " + header.title : header.title;
                    defs.add(new Def(t, header.key, header.section, title, null, y -> {}, null, null));
                    header = null;
                }
                defs.add(d);
            }
        }
    }

    /** Number of real settings (section headers have no description and don't count). */
    private int settingCount() {
        int n = 0;
        for (Def d : defs) if (d.desc != null) n++;
        return n;
    }

    private static final double PAGE_OUT = 0.1, PAGE_IN = 0.18;   // seconds: old page dissolves, then the new one dissolves in
    private boolean tabPending;
    private boolean closeQueued;
    private Runnable afterClose;
    private long closingNs;                       // != 0 while the window is dissolving away (Done, X, Esc)
    private static final double OPEN_T = 0.32, CLOSE_T = 0.32;   // the whole window dissolving in / out
    private static final float SHELL_END = 0.45f;                 // window dissolve: panels first (0..this), then the contents
    private int bodyBottom;                       // where the page area ends and the footer (Done) begins
    private Tab pendingTab;
    private long pendingNs;

    private void goTab(Tab t) {
        if (!tabPending && t != tab && PlushConfig.get().uiAnimations) {   // any switch (page, home <-> page): dissolve out, swap, dissolve in
            tabPending = true; pendingTab = t; pendingNs = System.nanoTime();
            return;
        }
        applyTab(t);
    }

    /** Rebuilds the current page in place (no transition: only tab switches dissolve). */
    private void refreshPage() { rebuildWidgets(); }

    private void applyTab(Tab t) {
        tabPending = false;
        tab = t;
        scroll = 0;
        pageNs = System.nanoTime();
        rowsNs = pageNs; rowsStep = 0L;   // the whole page dissolves in at once
        rebuildWidgets();
    }

    @Override
    protected void init() {
        defs.clear(); rows.clear(); toggles.clear(); sliders.clear(); buttons.clear(); tabHits.clear(); cards.clear(); hoverables.clear(); tiles.clear(); masterSwitch = null;
        resets.clear(); doneBtn = null; replayBtn = null; animBtn = null; animPrev = null; scopeBtn = null; changedBtn = null; searchBox = null; searchX = wx + ww;
        analysis = null;                  // settings may have changed (preset, reset, undo): the analysis is rebuilt on demand
        SAVED_PRESETS = Presets.list();
        PlushConfig c = PlushConfig.get();
        boolean home = tab == null;

        // 1) build this tab's row definitions first (widgets are only created later) so we know the real text sizes
        if (!home) {
            if (filtering()) buildFiltered(c);
            else build(tab, c);
        }

        // 2) size: the base size above, grown to fill most of the screen (up to 1.5x) so it follows the window's shape and GUI scale.
        //    The preview panel changes the width, and the window stays centred when it appears or goes away.
        // the preview only shows when the window with it still fits the screen; otherwise it is simply left out
        if (home) {
            previewW = 118;
            previewFits = width - 8 >= WIN_W_HOME_PREVIEW && height - 8 >= WIN_H_HOME;
            previewOn = showPreview && previewFits;
            ww = previewOn ? WIN_W_HOME_PREVIEW : WIN_W_HOME;
            wh = WIN_H_HOME;
        } else {
            previewW = PREVIEW_W_SUB;
            previewFits = width - 8 >= WIN_W_MAIN + previewW + 10 && height - 8 >= WIN_H_MAIN;
            previewOn = showPreview && previewFits;
            ww = WIN_W_MAIN + (previewOn ? previewW + 10 : 0);
            wh = WIN_H_MAIN;
        }
        ww = fitWindow(ww, width);
        wh = fitWindow(wh, height);
        PlushRenderer.preview = previewOn;
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        bodyBottom = wy + wh - FOOT_H;
        rowH = Mth.clamp((wh - FOOT_H - HEADER_H - 8 - (home ? 0 : BANNER_H)) / 6, 26, 34);
        previewX = home ? wx + 8 : wx + ww - previewW - 6;
        previewTop = wy + HEADER_H + 8;
        previewBottom = bodyBottom - 8;
        mainRight = (previewOn && !home) ? previewX - 4 : wx + ww;
        contentX = wx + RAIL_W + 12;
        ctlRight = mainRight - 28;
        // if the screen is too small for everything, names are shortened with "..." instead of overlapping
        int valueW = minecraft.font.width("-8.00 px");
        titleMaxW = Math.max(30, (ctlRight - CONTROL_W - valueW - 10 - RESET_W - 8) - contentX);
        rowsTop = wy + HEADER_H + 4 + (home ? 0 : BANNER_H);

        // header, like the Punchy settings: centred title, "Undo changes (N)" and a big X on the right
        if (tab != null) add(new HitButton(wx, wy, 40, HEADER_H, "back", b -> goTab(null)));   // back arrow: one level up
        closeBtn = add(new HitButton(wx + ww - CLOSE_W, wy, CLOSE_W, HEADER_H, null, b -> onClose()));   // onClose plays the back sound itself
        int undoW = minecraft.font.width("Undo changes (99)") + 16;
        if (undoW > ww / 2 - 40) undoW = minecraft.font.width("Undo (99)") + 16;   // narrow window: short label
        undoBtn = add(new HitButton(closeBtn.getX() - undoW, wy + 1, undoW, HEADER_H - 1, "press", b -> undoAll()));
        btnLeft = undoBtn.getX();
        int doneW = Math.min(ww - 40, 170), doneH = 22;
        doneBtn = add(new HitButton(wx + (ww - doneW) / 2, bodyBottom + (FOOT_H - doneH) / 2, doneW, doneH, "press", b -> startClose()));   // footer: Done = save, dissolve, leave

        if (home) initCards(c);
        else initRail();

        // only the rows that fit are laid out; the rest are reached by scrolling
        total = defs.size();
        visible = Math.max(1, (bodyBottom - rowsTop - 4) / rowH);
        scroll = Mth.clamp(scroll, 0, Math.max(0, total - visible));
        if (!home) {
            for (int i = scroll; i < Math.min(total, scroll + visible); i++) {
                Def d = defs.get(i);
                int y = rowsTop + (i - scroll) * rowH;
                rows.add(new Row(d.title, d.desc, y, d.tab, d.key));
                int slidersBefore = sliders.size();
                d.build.accept(y);
                if (d.value != null && d.setter != null) {
                    if (sliders.size() > slidersBefore) { // a slider row: it learns its default (tick, double-click reset)
                        Slider sl = sliders.get(slidersBefore);
                        Object dv = analysis().defaults.get(d.key);
                        sl.defaultValue = dv instanceof Number n ? n.doubleValue() : null;
                        sl.key = d.key;
                        sl.onReset = () -> resetDef(d);
                    }
                    // the green reset arrow on the left of the control (only drawn / acting while the setting differs)
                    int rx = ctlRight - CONTROL_W - valueW - 10 - RESET_W - 6;
                    HitButton rb = new HitButton(rx, y + (rowH - 2 - RESET_H) / 2, RESET_W, RESET_H, null, b -> {
                        if (isChanged(d.key)) { UiSounds.play("press", 1f); resetDef(d); }
                    });
                    addRenderableWidget(rb);
                    resets.add(new Reset(rb, d.key));
                }
            }
            // scroll bar: wheel scrolls; clicking the track above/below the thumb pages up/down
            sbX = mainRight - 16; sbTop = rowsTop + 2; sbBottom = bodyBottom - 6;
            if (total > visible) {
                int trackH = sbBottom - sbTop;
                thumbH = Math.max(24, trackH * visible / total);
                thumbY = sbTop + (trackH - thumbH) * scroll / (total - visible);
                if (thumbY - sbTop > 0) add(new HitButton(sbX, sbTop, SCROLL_W, thumbY - sbTop, null, b -> setScroll(scroll - visible)));
                if (sbBottom - (thumbY + thumbH) > 0) add(new HitButton(sbX, thumbY + thumbH, SCROLL_W, sbBottom - (thumbY + thumbH), null, b -> setScroll(scroll + visible)));
            }
        } else total = visible = 0;

        if (!home) initSearch();
        if (previewOn) initPreview();
    }

    /** The search field, the page/all switch and the "Changed" filter, on the right of the category banner. */
    private void initSearch() {
        int bannerX = wx + RAIL_W + 6, bannerR = mainRight - 6, bannerY = wy + HEADER_H + 4;
        int bh = 18, by = bannerY + (BANNER_H - 4 - bh) / 2, gap = 4, cw = 52, sw = 34;
        int cx = bannerR - 6 - cw, sx = cx - gap - sw;
        int titleEnd = bannerX + 10 + 24 + 8 + minecraft.font.width(tab.label.toUpperCase()) + 8;
        int boxW = Mth.clamp(sx - gap - titleEnd, 64, 112);
        int boxX = sx - gap - boxW;
        searchX = boxX;

        searchBox = new EditBox(minecraft.font, boxX, by, boxW, bh, Component.literal("Search"));
        searchBox.setMaxLength(32);
        searchBox.setValue(searchText);
        searchBox.setResponder(text -> {
            if (!text.equals(searchText)) { searchText = text; searchDirty = true; refocusSearch = true; }
        });
        addRenderableWidget(searchBox);
        if (refocusSearch) { setInitialFocus(searchBox); refocusSearch = false; }

        scopeBtn = styled(sx, by, sw, bh, searchAll ? "All" : "Page", searchAll ? GREEN : FACE, searchAll ? GREEN_HI : FACE_HI,
                searchAll ? GREEN_SHADOW : FACE_SHADOW, searchAll ? WHITE : DARK, false, "select", b -> {
                    searchAll = !searchAll;
                    scroll = 0;
                    toast(searchAll ? "Searching every page" : "Searching this page only");
                    refreshPage();
                });
        changedBtn = styled(cx, by, cw, bh, "Changed", changedOnly ? GREEN : FACE, changedOnly ? GREEN_HI : FACE_HI,
                changedOnly ? GREEN_SHADOW : FACE_SHADOW, changedOnly ? WHITE : DARK, false, "select", b -> {
                    changedOnly = !changedOnly;
                    scroll = 0;
                    refreshPage();
                });
    }

    /** Home: the master switch on top, then three labelled rows of three category cards, next to the stage. */
    private void initCards(PlushConfig c) {
        int gap = 6, x0 = (previewOn ? previewX + previewW + 10 : wx + 12), x1 = wx + ww - 10;
        int bannerY = wy + HEADER_H + 8;
        int y0 = bannerY + HOME_BANNER_H + 6, y1 = bodyBottom - 10;
        homeX0 = x0; homeX1 = x1;
        cardW = (x1 - x0 - 2 * gap) / 3;
        int rowCount = GROUPS.length;
        cardH = (y1 - y0 - rowCount * GROUP_LABEL_H - (rowCount - 1) * gap) / rowCount;

        // the big on/off switch for the whole mod, on the right end of the banner
        masterSwitch = add(new HitButton(x1 - 6 - SWITCH_W, bannerY + (HOME_BANNER_H - SWITCH_H) / 2, SWITCH_W, SWITCH_H, "press",
                b -> PlushConfig.get().modEnabled = !PlushConfig.get().modEnabled));
        toggles.add(new Toggle(masterSwitch, () -> PlushConfig.get().modEnabled, new float[]{c.modEnabled ? 1f : 0f}));

        Tab[] all = Tab.values();
        for (int i = 0; i < all.length; i++) {
            Tab t = all[i];
            int x = x0 + (i % 3) * (cardW + gap), y = y0 + (i / 3) * (cardH + gap + GROUP_LABEL_H) + GROUP_LABEL_H;
            int w = t == Tab.PRESETS ? x1 - x0 : cardW;   // Presets sits alone on its own row, full width
            HitButton h = add(new HitButton(x, y, w, cardH, "select", b -> goTab(t)));
            cards.add(new Card(t, h, countFor(t, c)));
        }
    }

    /** Each category gets its own colour, so the home page has some life. */
    private static int accent(Tab t) {
        return switch (t) { // matched to the icons' colours
            case GENERAL -> 0xFFA0A2A8; case SCALE -> 0xFF9B59E0; case LAYOUT -> 0xFF3A9BFF;
            case ANIMATIONS -> 0xFF4CC84A; case EMOTES -> 0xFFF5C518; case PARTICLES -> 0xFF6FD6F5;
            case SOUND -> 0xFFFF8A1F; case SKIN -> 0xFFC98A5B; case COSMETICS -> 0xFFD83A3A;
            case PRESETS -> 0xFF2EC4B6;
        };
    }

    /** A live one-line summary of what that category is set to right now. */
    private String status(Tab t, PlushConfig c) {
        switch (t) {
            case GENERAL: return c.animationsEnabled ? "Animations on" : "Animations off";
            case SCALE: return String.format("Size %.2fx", c.scale);
            case LAYOUT: return String.format("Offset %.0f, %.0f, %.0f", c.modelOffset[0], c.modelOffset[1], c.modelOffset[2]);
            case ANIMATIONS: {
                int on = 0;
                for (int i = 0; i < ANIM_NAMES.length; i++) if (animOf(c, i).enabled) on++;
                return on + " of " + ANIM_NAMES.length + " on";
            }
            case EMOTES: return "G key: " + c.quickEmote;
            case PARTICLES: return c.particles.enabled ? "On (beta)" : "Off";
            case PRESETS: { int n = SAVED_PRESETS.size(); return n == 0 ? "None saved" : n + " saved"; }
            case SOUND: return c.sound.enabled ? "Volume " + Math.round(c.sound.volume * 100) + "%" : "Muted";
            case SKIN: return !c.usePlayerSkin ? "Default skin" : switch (c.skinSource) { case "steve" -> "Steve"; case "alex" -> "Alex"; case "username" -> c.skinUsername.isEmpty() ? "A username" : c.skinUsername; case "file" -> "Offline file"; default -> "Your skin"; };
            default: {
                int on = 0;
                for (Accessory a : PlushData.accessories) if (c.accessoryOn(a)) on++;
                return on + " worn";
            }
        }
    }

    /** Inside a category: the slim icon-only rail (home glyph on top, then one icon per tab). */
    private void initRail() {
        int n = ENTRIES.length;
        tabTop = wy + HEADER_H + 6;
        int avail = wh - FOOT_H - HEADER_H - 12;
        tabH = Mth.clamp(avail / n, 20, 26);
        tabVisible = Math.max(1, Math.min(n, avail / tabH));
        tabScroll = Mth.clamp(tabScroll, 0, n - tabVisible);
        int selIdx = tab == null ? 0 : tab.ordinal() + 1; // keep the selected entry on screen
        if (selIdx < tabScroll) tabScroll = selIdx;
        else if (selIdx >= tabScroll + tabVisible) tabScroll = selIdx - tabVisible + 1;
        for (int i = tabScroll; i < tabScroll + tabVisible; i++) {
            Tab t = ENTRIES[i];
            HitButton h = new HitButton(wx, tabTop + (i - tabScroll) * tabH, RAIL_W, tabH, "select", b -> {
                if (t != tab) goTab(t);
            });
            add(h);
            tabHits.add(new TabHit(i, h));
        }
    }

    /** What a preview button does: states are forced and stay (until a one-shot ends); equip / emotes just play once. */
    private static void previewStep(int d) {
        int at = 0;
        for (int i = 0; i < PV_IDS.length; i++) if (PV_IDS[i].equals(pvCurrent)) at = i;
        previewTrigger(PV_IDS[(at + d + PV_IDS.length) % PV_IDS.length]);
    }

    private static void previewTrigger(String id) {
        pvCurrent = id;
        PlushRenderer.previewCancel();   // the animation that was playing stops right here, the new one starts clean
        PlushRenderer.previewLoop(id);
        switch (id) {
            case "equip" -> PlushRenderer.previewEquip();
            case "hi" -> PlushRenderer.previewEmote(EmoteTrigger.HI);
            case "yay" -> PlushRenderer.previewEmote(EmoteTrigger.YAY);
            case "clap" -> PlushRenderer.previewEmote(EmoteTrigger.CLAP);
            default -> PlushRenderer.previewSetState(id);
        }
    }

    /**
     * The preview panel's input: a "> Idle" button in the top-left corner (each click switches to the next animation,
     * which then replays every time it ends), a quiet invisible slider over the stage for drag-to-turn, and the view
     * picker (which display context the plush imitates) along the bottom. The label is drawn dynamically, so
     * clicking needs no rebuild.
     */
    private void initPreview() {
        int pad = 6;
        previewBtnTop = previewBottom - pad;

        int vx = previewX + pad, vw = previewW - 2 * pad;
        previewBarTop = previewBottom - pad - 11;   // the name and its progress line sit at the bottom of the panel

        // animation switcher, top left over the stage: "< Idle >" (the left arrow goes back, anywhere else goes on)
        int topY = previewTop + 4, replayS = 20, replayGap = 4;
        int btnW = Math.min(vw - replayS - replayGap, 84), sx = vx;
        // replay: a square green button in the top-right corner of the stage that plays the current animation again
        replayBtn = add(new HitButton(vx + vw - replayS, topY - 2, replayS, replayS, "press", b -> previewTrigger(pvCurrent)));
        animPrev = add(new HitButton(sx, topY, 14, 16, "select", b -> previewStep(-1)));
        animBtn = add(new HitButton(sx + 14, topY, btnW - 14, 16, "select", b -> previewStep(1)));

        int top = previewTop + 22;
        yawSlider = new Slider(previewX, top, previewW, Math.max(20, previewBarTop - 4 - top), -180, 180,
                PlushRenderer.previewYaw, "%.0f", v -> PlushRenderer.previewYaw = (float) v, true);
        addRenderableWidget(yawSlider);
    }

    /** A title in the chunky Punchy heading font (falls back to the bold game font for letters it does not have). */
    private void heading(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        if (PixelFont.supports(text)) PixelFont.draw(g, text, x, y, color);
        else g.text(minecraft.font, Component.literal(text).withStyle(ChatFormatting.BOLD), x, y, color, false);
    }

    private int headingWidth(String text) {
        return PixelFont.supports(text) ? PixelFont.width(text) : minecraft.font.width(Component.literal(text).withStyle(ChatFormatting.BOLD));
    }

    private void setTabScroll(int s) {
        s = Mth.clamp(s, 0, Math.max(0, ENTRIES.length - tabVisible));
        if (s != tabScroll) {
            tabScroll = s;
            UiSounds.play("scroll", 1f);
            rebuildWidgets();
        }
    }

    private void setScroll(int s) {
        s = Mth.clamp(s, 0, Math.max(0, total - visible));
        if (s != scroll) {
            scroll = s;
            rowsNs = System.nanoTime(); rowsStep = 12_000_000L; // quick re-reveal so scrolling feels smooth
            UiSounds.play("scroll", 1f);
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (previewOn && scrollY != 0 && mouseX >= previewX && mouseX < previewX + previewW && mouseY >= previewTop && mouseY < previewBottom) {
            // wheel over the preview: zoom the plush out / in
            PlushRenderer.previewZoom = Mth.clamp(PlushRenderer.previewZoom * (float) Math.pow(1.12, scrollY), 0.3f, 2f);
            return true;
        }
        if (tab != null && mouseX >= wx && mouseX < wx + RAIL_W && mouseY > wy + HEADER_H && ENTRIES.length > tabVisible && scrollY != 0) {
            setTabScroll(tabScroll - (int) Math.signum(scrollY)); // wheel over the rail scrolls the entry list
            return true;
        }
        if (tab != null && mouseX > wx + RAIL_W && mouseX < mainRight && total > visible && scrollY != 0) {
            setScroll(scroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** How many settings differ from when this screen opened (cached until the settings change). */
    private int undoCount() {
        String now = PlushConfig.toJson();
        if (!now.equals(undoCountFor)) {
            undoCountFor = now;
            try { undoCount = diffCount(JsonParser.parseString(openJson), JsonParser.parseString(now)); }
            catch (RuntimeException e) { undoCount = now.equals(openJson) ? 0 : 1; }
        }
        return undoCount;
    }

    private static int diffCount(JsonElement a, JsonElement b) {
        if (a.isJsonObject() && b.isJsonObject()) {
            int n = 0;
            Set<String> keys = new HashSet<>(a.getAsJsonObject().keySet());
            keys.addAll(b.getAsJsonObject().keySet());
            for (String k : keys) {
                JsonElement x = a.getAsJsonObject().get(k), y = b.getAsJsonObject().get(k);
                if (x == null || y == null) n++; else n += diffCount(x, y);
            }
            return n;
        }
        if (a.isJsonArray() && b.isJsonArray() && a.getAsJsonArray().size() == b.getAsJsonArray().size()) {
            int n = 0;
            for (int i = 0; i < a.getAsJsonArray().size(); i++) n += diffCount(a.getAsJsonArray().get(i), b.getAsJsonArray().get(i));
            return n;
        }
        return a.equals(b) ? 0 : 1;
    }

    /** "Undo changes": puts every setting back the way it was when this screen opened (nothing is saved). */
    private void undoAll() {
        if (undoCount() == 0) return;
        UNDO.push(PlushConfig.toJson());
        if (PlushConfig.applyJson(openJson)) { lastSeen = PlushConfig.toJson(); toast("Changes undone"); refreshPage(); }
    }

    /** Back / X / ESC: asks first if there are unsaved changes. SAVE saves and leaves without asking. */
    @Override
    public void onClose() {
        if (closingNs != 0) return;
        UiSounds.play("back", 1f);
        if (PlushConfig.isDirty()) ScreenUtil.open(new UnsavedChangesScreen(this));
        else animateClose(this::exit);
    }

    /** The live preview only runs while this screen is up. */
    @Override
    public void removed() {
        PlushRenderer.preview = false;
        super.removed();
    }

    /** "Done": save, then dissolve the window (instantly when UI animations are off) and leave. */
    private void startClose() {
        if (closingNs != 0) return;
        PlushConfig.save();
        UiSounds.play("back", 1f);
        animateClose(this::exit);
    }

    /** The window dissolves away (instantly when UI animations are off), then {@code after} runs. */
    private void animateClose(Runnable after) {
        if (closingNs != 0) return;
        if (!PlushConfig.get().uiAnimations) { after.run(); return; }
        afterClose = after;
        closingNs = System.nanoTime();
    }

    /** Runs the pending "leave" once the dissolve has finished (called at the end of every frame while closing). */
    private void finishClose() {
        if (closingNs == 0 || closeQueued || UiDraw.progress(closingNs, CLOSE_T) < 1f) return;
        closeQueued = true;
        Runnable r = afterClose;
        if (r != null) minecraft.execute(r);   // leave after this frame is drawn
    }

    /** Leave this screen (back to Mod Menu's list if that's where we came from). */
    void exit() {
        if (parent != null) ScreenUtil.open(parent);
        else super.onClose();
    }

    // ---- widget helpers

    /** Base size -> actual size: fills ~85% of the screen, never below the base size and never above 1.5x it (or the screen). */
    private static int fitWindow(int base, int screen) {
        int v = Math.max(base, Math.min(Math.round(screen * 0.85f), Math.round(base * 1.5f)));
        return Math.min(v, screen - 8);
    }

    private <T extends AbstractWidget> T add(T w) {
        addRenderableWidget(w);
        hoverables.add(w);
        return w;
    }

    private HitButton styled(int x, int y, int w, int h, String text, int face, int hi, int shadow, int textColor, boolean bold, String sound, Button.OnPress onPress) {
        HitButton b = add(new HitButton(x, y, w, h, sound, onPress));
        buttons.add(new Styled(b, text, face, hi, shadow, textColor, bold));
        return b;
    }

    // ---- row definitions (built lazily, only for visible rows)

    /** English text -> translated text, when assets/plushpal/lang has "plushpal.ui.<slug>"; otherwise the English text itself. */
    private static final Map<String, String> ENGLISH = new HashMap<>(); // translated -> English (help images are looked up by the English name)

    private static final Map<String, String> SLUGS = new HashMap<>();

    private static String tr(String english) {
        if (english == null) return null;
        String key = "plushpal.ui." + SLUGS.computeIfAbsent(english, PlushConfigScreen::slug);
        if (I18n.get(key).equals(key)) return english;
        String t = I18n.get(key);
        ENGLISH.put(t, english);
        return t;
    }

    private void addDef(String keyTitle, String title, String desc, IntConsumer build, Object value, Consumer<Object> setter) {
        defs.add(new Def(curTab, curTab.name() + "|" + keyPrefix + keyTitle, curSection, title, desc, build, value, setter));
    }

    /** A section title inside a page (no control; has no description, which is how drawing tells it apart). */
    private void header(String title) {
        curSection = title;
        addDef(title, tr(title), null, y -> {}, null, null);
    }

    private void addToggle(int y, BooleanSupplier get, Consumer<Boolean> set) {
        Button b = add(new HitButton(ctlRight - TOGGLE_W, y + (rowH - TOGGLE_H) / 2, TOGGLE_W, TOGGLE_H, "press", btn -> set.accept(!get.getAsBoolean())));
        toggles.add(new Toggle(b, get, new float[]{get.getAsBoolean() ? 1f : 0f}));
    }

    private void toggleRow(String title, String desc, BooleanSupplier get, Consumer<Boolean> set) {
        addDef(title, tr(title), tr(desc), y -> addToggle(y, get, set), get.getAsBoolean(), o -> set.accept((Boolean) o));
    }

    /** An accessory: a thumbnail of its texture next to the on/off switch. */
    private void accessoryRow(Accessory a, PlushConfig c) {
        String desc = a.description.isEmpty() ? "Show this accessory on the plush" : a.description;
        addDef("acc:" + a.id, a.name, desc + " (" + a.id + ")", y -> {
            addToggle(y, () -> c.accessoryOn(a), v -> c.setAccessory(a.id, v));
            tiles.add(new Tile(a, ctlRight - TOGGLE_W - 32, y + (rowH - 26) / 2));
        }, c.accessoryOn(a), o -> c.setAccessory(a.id, (Boolean) o));
    }

    private void sliderRow(String title, String desc, double min, double max, double v, String fmt, DoubleConsumer set) {
        addDef(title, tr(title), tr(desc), y -> sliders.add(add(new Slider(ctlRight - CONTROL_W, y + (rowH - 24) / 2, 130, 24, min, max, v, fmt, set))),
                v, o -> set.accept(((Number) o).doubleValue()));
    }

    private void cycleRow(String title, String desc, String[] names, IntSupplier get, IntConsumer set) {
        addDef(title, tr(title), tr(desc), y -> styled(ctlRight - CONTROL_W, y + (rowH - 22) / 2, 130, 22, names[get.getAsInt()],
                GREEN, GREEN_HI, GREEN_SHADOW, WHITE, false, "select", b -> { set.accept((get.getAsInt() + 1) % names.length); rebuildWidgets(); }),
                get.getAsInt(), o -> set.accept(((Number) o).intValue()));
    }

    private void buttonRow(String title, String desc, String label, Runnable action) {
        addDef(title, tr(title), tr(desc), y -> styled(ctlRight - CONTROL_W, y + (rowH - 22) / 2, 130, 22, label,
                FACE, FACE_HI, FACE_SHADOW, DARK, false, "press", b -> action.run()), null, null);
    }

    private void toast(String text) { toastText = text; toastNs = System.nanoTime(); }

    // ---- tabs

    private void general(PlushConfig c) {
        header("Animation");
        toggleRow("Animations", "Turn every plush animation on or off", () -> c.animationsEnabled, v -> c.animationsEnabled = v);
        sliderRow("Global speed", "Playback speed for every animation", 0, 3, c.globalSpeed, "%.2fx", v -> c.globalSpeed = v);
        sliderRow("Rotation amount", "How far the arms, legs and head swing", 0, 2, c.globalRotationIntensity, "%.2fx", v -> c.globalRotationIntensity = v);
        sliderRow("Movement amount", "How far the plush moves around", 0, 2, c.globalPositionIntensity, "%.2fx", v -> c.globalPositionIntensity = v);
        sliderRow("Transition time", "How long it blends between two animations", 0, 1, c.transitionTime, "%.2fs", v -> c.transitionTime = v);
        header("Triggers");
        sliderRow("Low health (hearts)", "Hearts at or below which the plush gets nervous", 0.5, 10, c.lowHealthHearts, "%.1f", v -> c.lowHealthHearts = v);
        sliderRow("Fall trigger speed", "How fast you fall before the fall animation plays", 0.05, 0.6, c.fallSpeedThreshold, "%.2f", v -> c.fallSpeedThreshold = v);
        header("These menus");
        toggleRow("UI animations", "Fades, sliding switches and smooth hover in these menus", () -> c.uiAnimations, v -> c.uiAnimations = v);
        sliderRow("UI animation speed", "How fast the menu animations play", 0.5, 2, c.uiAnimSpeed, "%.2fx", v -> c.uiAnimSpeed = v);
        header("This window");
        buttonRow("Preview panel", "Show or hide the live plush preview (it hides itself on narrow windows)", showPreview ? "Hide" : "Show", () -> {
            showPreview = !showPreview;
            if (showPreview && !previewFits) toast("The window is too narrow for the preview");
            refreshPage();
        });
        buttonRow("Reset everything", "Put every setting back to its default (nothing is saved until you save; Undo changes brings it back)", "Reset", () -> {
            PlushConfig.resetToDefaults();
            refreshPage();
        });
        buttonRow("Save and close", "Save every setting and leave", "Save", this::startClose);
    }

    /** Presets: its own page (not a general setting). Saved presets are listed one per row, with Load / Delete on the row. */
    private void presets(PlushConfig c) {
        List<String> saved = SAVED_PRESETS;
        header("Saved presets");
        buttonRow("Save current settings", "Save everything you have set up right now under a name, so you can come back to it", "Save as...", () ->
                ScreenUtil.open(new TextPromptScreen(this, "Save preset", "Letters, numbers, spaces, - and _ (up to 32)", "", 32, name -> {
                    if (!Presets.validName(name)) { toast("Use letters, numbers, spaces, - and _ only"); return; }
                    if (Presets.save(name)) toast("Saved preset " + name.trim());
                    else toast("Could not save the preset");
                    rebuildWidgets();
                })));
        if (saved.isEmpty()) {
            addDef("none", "No presets yet", "Presets you save show up here, one per row", y -> {}, null, null);
        }
        for (String name : saved) {
            addDef("preset:" + name, name, "Load applies this preset to your settings (nothing is saved until you press SAVE, and Ctrl+Z undoes it). Delete removes the preset file only", y -> {
                int by = y + (rowH - 22) / 2, x = ctlRight - CONTROL_W;
                styled(x, by, 62, 22, "Load", GREEN, GREEN_HI, GREEN_SHADOW, WHITE, false, "press", b -> {
                    if (Presets.load(name)) { toast("Loaded " + name); refreshPage(); }
                    else toast("Could not load that preset");
                });
                styled(x + 68, by, 62, 22, "Delete", FACE, FACE_HI, FACE_SHADOW, DARK, false, "press", b -> {
                    toast(Presets.delete(name) ? "Deleted " + name : "Could not delete it");
                    refreshPage();
                });
            }, null, null);
        }
        header("Share");
        buttonRow("Copy to share", "Copy all your settings as one line of text you can send to a friend", "Copy", () ->
                toast(PlatformUtil.setClipboard(Presets.export()) ? "Copied! Send it to a friend" : "Could not reach the clipboard"));
        buttonRow("Paste to import", "Apply settings copied from a friend's \"Copy to share\". Nothing is saved until you press SAVE", "Paste", () -> {
            if (Presets.importString(PlatformUtil.clipboard())) { toast("Imported the settings"); refreshPage(); }
            else toast("The clipboard has no PlushPal settings");
        });
    }

    private void scale(PlushConfig c) {
        PlushConfig.Scale s = c.scaleByContext;
        header("Everywhere");
        sliderRow("Global scale", "Size of the plush everywhere", 0.25, 3, c.scale, "%.2fx", v -> c.scale = v);
        header("In each view");
        sliderRow("First person", "Size in your hand", 0.25, 3, s.firstPerson, "%.2fx", v -> s.firstPerson = v);
        sliderRow("Third person", "Size in someone's hand (F5)", 0.25, 3, s.thirdPerson, "%.2fx", v -> s.thirdPerson = v);
        sliderRow("Inventory", "Size in menus and the hotbar", 0.25, 3, s.gui, "%.2fx", v -> s.gui = v);
        sliderRow("On the ground", "Size as a dropped item", 0.25, 3, s.ground, "%.2fx", v -> s.ground = v);
        sliderRow("Item frame / pop", "Size in item frames and the totem pop", 0.25, 3, s.fixed, "%.2fx", v -> s.fixed = v);
        sliderRow("On head", "Size when worn on the head", 0.25, 3, s.head, "%.2fx", v -> s.head = v);
    }

    private void layout(PlushConfig c) {
        header("Position");
        sliderRow("Offset X", "Move the plush left or right", -8, 8, c.modelOffset[0], "%.2f px", v -> c.modelOffset[0] = v);
        sliderRow("Offset Y", "Move the plush up or down", -8, 8, c.modelOffset[1], "%.2f px", v -> c.modelOffset[1] = v);
        sliderRow("Offset Z", "Move the plush forward or back", -8, 8, c.modelOffset[2], "%.2f px", v -> c.modelOffset[2] = v);
        header("Animate in");
        toggleRow("Animate in inventory", "Play animations on the item in menus", () -> c.animateInGui, v -> c.animateInGui = v);
        toggleRow("Animate on ground", "Play animations on dropped items", () -> c.animateOnGround, v -> c.animateOnGround = v);
        toggleRow("Animate in frames / pop", "Play animations in item frames and totem pop", () -> c.animateInFixed, v -> c.animateInFixed = v);
        toggleRow("Animate on head", "Play animations when worn on the head", () -> c.animateOnHead, v -> c.animateOnHead = v);
    }

    private void animations(PlushConfig c) {
        header("Animation packs");
        buttonRow("Use pack order", "Forget your mix: every animation comes from the highest resource pack that has it", "Reset mix", () -> {
            c.animationPacks.clear();
            PlushData.remix();
            refreshPage();
        });
        header("Choose");
        addDef("Animation", "Animation", "Pick which animation to edit", y -> {
            int by = y + (rowH - 22) / 2, x = ctlRight - CONTROL_W;
            styled(x, by, 22, 22, "<", FACE, FACE_HI, FACE_SHADOW, DARK, true, "select", b -> { animIndex = Math.floorMod(animIndex - 1, ANIM_NAMES.length); refreshPage(); });
            styled(x + 24, by, 82, 22, ANIM_NAMES[animIndex], GREEN, GREEN_HI, GREEN_SHADOW, WHITE, false, "select", b -> { animIndex = (animIndex + 1) % ANIM_NAMES.length; refreshPage(); });
            styled(x + 108, by, 22, 22, ">", FACE, FACE_HI, FACE_SHADOW, DARK, true, "select", b -> { animIndex = (animIndex + 1) % ANIM_NAMES.length; refreshPage(); });
        }, null, null);
        animationBlock(c, animIndex);
    }

    /** One animation's settings. Keys carry the animation's number, so "Speed" of Idle and of Fall are different settings. */
    private void animationBlock(PlushConfig c, int idx) {
        PlushConfig.Anim a = animOf(c, idx);
        String savedPrefix = keyPrefix;
        keyPrefix = "anim" + idx + ":";
        header(ANIM_NAMES[idx]);
        packRow(c, a.animation);
        toggleRow("Enabled", "Turn this animation on or off", () -> a.enabled, v -> a.enabled = v);
        sliderRow("Speed", "Playback speed of this animation", 0, 3, a.speed, "%.2fx", v -> a.speed = v);
        sliderRow("Rotation amount", "How far the limbs rotate", 0, 2, a.rotationIntensity, "%.2fx", v -> a.rotationIntensity = v);
        sliderRow("Movement amount", "How far the plush moves", 0, 2, a.positionIntensity, "%.2fx", v -> a.positionIntensity = v);
        keyPrefix = savedPrefix;
    }

    /** Which resource pack this animation comes from. Only shown when more than one pack has it, so packs can be mixed. */
    private void packRow(PlushConfig c, String animation) {
        if (animation == null) return;
        List<String> packs = PlushData.packsWith(animation);
        if (packs.size() < 2) return;
        String[] names = new String[packs.size() + 1];
        names[0] = fit("Auto: " + PlushData.packName(packs.get(packs.size() - 1)), CONTROL_W - 10);   // pack order: the highest pack wins
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < packs.size(); i++) {
            names[i + 1] = fit(PlushData.packName(packs.get(i)), CONTROL_W - 10);
            list.append(i == 0 ? "" : ", ").append(PlushData.packName(packs.get(i)));
        }
        cycleRow("Pack", "Which resource pack supplies this animation (click to cycle). Available: " + list, names,
                () -> Math.max(0, packs.indexOf(c.animationPacks.get(animation)) + 1),
                i -> {
                    if (i <= 0 || i > packs.size()) c.animationPacks.remove(animation);
                    else c.animationPacks.put(animation, packs.get(i - 1));
                    PlushData.remix();
                });
    }

    private static PlushConfig.Anim animOf(PlushConfig c, int i) {
        return switch (i) {
            case 0 -> c.handIn;
            case 1 -> c.hi;
            case 2 -> c.yay;
            case 3 -> c.clap;
            case 4 -> c.idle;
            case 5 -> c.idleSwim;
            case 6 -> c.fall;
            case 7 -> c.attack;
            case 8 -> c.mine;
            case 9 -> c.onFire;
            case 10 -> c.freeze;
            case 11 -> c.hurt;
            default -> c.lowHealth;
        };
    }

    private void emotes(PlushConfig c) {
        header("Triggers");
        cycleRow("Quick emote (G)", "What the G key plays. Random = any enabled emote", QUICK_NAMES,
                () -> Math.max(0, List.of(QUICK_IDS).indexOf(c.quickEmote)), i -> c.quickEmote = QUICK_IDS[i]);
        buttonRow("Emote wheel", "Open it now. Bind a key in Controls", "Open wheel", () -> {
            ScreenUtil.open(new EmoteWheelScreen());
        });
        header("Wheel slots");
        for (int i = 0; i < 6; i++) {
            int slot = i;
            cycleRow("Slot " + (i + 1), "Which emote sits in wheel slot " + (i + 1) + " (numbered clockwise from the upper left)", SLOT_NAMES,
                    () -> Math.max(0, List.of(SLOT_IDS).indexOf(c.wheelSlots.get(slot))), v -> c.wheelSlots.set(slot, SLOT_IDS[v]));
        }
        header("Allowed emotes");
        toggleRow("Hi", "Allow the Hi emote", () -> c.hi.enabled, v -> c.hi.enabled = v);
        toggleRow("Yay", "Allow the Yay emote", () -> c.yay.enabled, v -> c.yay.enabled = v);
        toggleRow("Clap", "Allow the Clap emote", () -> c.clap.enabled, v -> c.clap.enabled = v);
    }

    private void cosmetics(PlushConfig c) {
        header("Behaviour");
        toggleRow("Animate accessories", "Play the accessories' own animations (like the cape sway)", () -> c.animateAccessories, v -> c.animateAccessories = v);
        header("Worn");
        if (PlushData.accessories.isEmpty()) header("No accessories found");
        for (Accessory a : PlushData.accessories) accessoryRow(a, c);
        header("Add more");
        buttonRow("Get accessories", "Accessories come from resource packs (assets/<namespace>/accessories/binding). This opens your resourcepacks folder; drop a pack in, then press F3+T", "Open folder", () ->
                toast(PlatformUtil.openFolder(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks")) ? "Opened the resourcepacks folder" : "Could not open the folder"));
    }

    private void particles(PlushConfig c) {
        PlushConfig.Particles pc = c.particles;
        header("Master");
        toggleRow("Particles (beta)", "Beta: flames, bubbles and snowflakes drawn on the plush itself, attached to the item and your hand. Off by default", () -> pc.enabled, v -> pc.enabled = v);
        header("Effects");
        toggleRow("Flames", "Flame particles while you're on fire", () -> pc.fire, v -> pc.fire = v);
        toggleRow("Bubbles", "Bubbles rise from the plush while you're underwater", () -> pc.bubbles, v -> pc.bubbles = v);
        toggleRow("Snowflakes", "Snowflakes while you're freezing", () -> pc.snow, v -> pc.snow = v);
        header("Tuning");
        sliderRow("Amount", "How many particles are emitted", 0.25, 3, pc.amount, "%.2fx", v -> pc.amount = v);
        sliderRow("Size", "How big each particle is", 0.5, 2, pc.size, "%.2fx", v -> pc.size = v);
    }

    private void sound(PlushConfig c) {
        header("Plush");
        toggleRow("Plush sound", "Play the plush sound when you equip it", () -> c.sound.enabled, v -> c.sound.enabled = v);
        sliderRow("Plush volume", "How loud the plush sound is", 0, 2, c.sound.volume, "%.2f", v -> c.sound.volume = v);
        sliderRow("Plush pitch", "How high or low the plush sound is", 0.5, 2, c.sound.pitch, "%.2f", v -> c.sound.pitch = v);
        buttonRow("Test plush sound", "Plays the plush sound at the volume and pitch above", "Play", UiSounds::testPlush);
        header("Menus");
        toggleRow("Menu sounds", "Hover, click, scroll and back sounds", () -> c.uiSounds, v -> c.uiSounds = v);
        sliderRow("Menu volume", "How loud the menu sounds are", 0, 2, c.uiVolume, "%.2f", v -> c.uiVolume = v);
        String[] testNames = {"Hover", "Select", "Back", "Scroll", "Press"};
        String[] testIds = {"hover", "select", "back", "scroll", "press"};
        cycleRow("Menu sound", "Pick which menu sound the Play button below uses", testNames, () -> testSoundIndex, i -> testSoundIndex = i);
        buttonRow("Test menu sound", "Plays the picked menu sound at the menu volume above", "Play", () -> UiSounds.testMenu(testIds[testSoundIndex]));
    }

    private void skin(PlushConfig c) {
        header("Skin");
        toggleRow("Use my skin", "Texture the plush with the skin chosen below instead of the bundled plush skin", () -> c.usePlayerSkin, v -> c.usePlayerSkin = v);
        String[] sources = {"own", "steve", "alex", "username", "file"};
        String[] sourceNames = {"My skin", "Steve", "Alex", "Username", "Offline file"};
        cycleRow("Skin source", "Whose skin the plush wears: yours, Steve, Alex, a player's username, or a PNG file that works offline", sourceNames,
                () -> Math.max(0, List.of(sources).indexOf(c.skinSource)), i -> { c.skinSource = sources[i]; SkinSource.refresh(); });
        if ("own".equals(c.skinSource)) {
            toggleRow("Slim skin support", "Use the narrower arm layout when your skin is slim (Alex)", () -> c.slimSkins, v -> c.slimSkins = v);
        } else if ("username".equals(c.skinSource)) {
            String st = SkinSource.status();
            buttonRow("Username", "The player whose skin to use. It is looked up once online and saved, so it keeps working offline." + (st.isEmpty() ? "" : " Status: " + st),
                    c.skinUsername.isEmpty() ? "Set name..." : c.skinUsername, () ->
                            ScreenUtil.open(new TextPromptScreen(this, "Skin username", "The exact Minecraft username", c.skinUsername, 16, name -> {
                                if (!SkinSource.validUsername(name)) { toast("That is not a valid username"); return; }
                                PlushConfig.get().skinUsername = name;
                                SkinSource.refresh();
                            })));
            buttonRow("Refresh skin", "Look the username up again (for when the player changed their skin)", "Refresh", () -> { SkinSource.refresh(); toast("Refreshing..."); rebuildWidgets(); });
        } else if ("file".equals(c.skinSource)) {
            String st = SkinSource.status();
            buttonRow("Skin file", "Put a 64x64 or 64x32 PNG named skin.png in config/plushpal. Works without internet." + (st.isEmpty() ? "" : " Status: " + st),
                    "Open folder", () -> toast(PlatformUtil.openFolder(SkinSource.dir()) ? "Opened config/plushpal" : "Could not open the folder"));
            toggleRow("Slim arms", "Turn on if skin.png uses the narrow Alex arm layout", () -> c.customSkinSlim, v -> { c.customSkinSlim = v; SkinSource.refresh(); });
        }
        header("Wear it in");
        String[][] ctx = {{"first_person", "First person"}, {"third_person", "Third person"}, {"gui", "Inventory"},
                {"ground", "On the ground"}, {"fixed", "Item frame / totem pop"}, {"head", "On head"}};
        for (String[] e : ctx) {
            toggleRow(e[1], "Use your skin in this view", () -> c.playerSkinContexts.contains(e[0]), v -> {
                c.playerSkinContexts.remove(e[0]);
                if (v) c.playerSkinContexts.add(e[0]);
            });
        }
    }

    // ------------------------------------------------------------------ keyboard shortcuts + undo

    /**
     * Polled once per frame (no input overrides to keep in sync with Minecraft's changing event classes):
     * 1-9 jump to a category (0 = home), Ctrl+Z undoes the last change, a double-click on a slider resets it,
     * Ctrl+click on a slider lets you type its value. Typing in the search box turns the number / undo keys off.
     */
    private void pollShortcuts(int mouseX, int mouseY) {
        // undo history
        String now = PlushConfig.toJson();
        refreshAnalysis(now);
        if (!now.equals(lastSeen) && !PlatformUtil.mouseDown()) { // a finished change (a slider drag counts as one)
            UNDO.push(lastSeen);
            while (UNDO.size() > 50) UNDO.removeLast();
            lastSeen = now;
        }
        boolean typing = searchBox != null && searchBox.isFocused();
        boolean[] down = new boolean[11];
        if (!typing) {
            for (int i = 0; i <= 9; i++) down[i] = PlatformUtil.keyDown('0' + i);
            down[10] = PlatformUtil.keyDown('Z');
        }
        boolean[] was = keyWas;
        keyWas = down;                                       // updated first: the early returns below must not re-trigger next frame
        for (int i = 0; i <= 9; i++) {
            if (down[i] && !was[i] && !PlatformUtil.ctrlDown()) {
                Tab[] all = Tab.values();
                Tab target = i == 0 ? null : (i - 1 < all.length ? all[i - 1] : tab);
                if (target != tab) { UiSounds.play("select", 1f); goTab(target); return; }
            }
        }
        if (down[10] && !was[10] && PlatformUtil.ctrlDown() && !UNDO.isEmpty()) {
            String prev = UNDO.pop();
            if (PlushConfig.applyJson(prev)) { lastSeen = PlushConfig.toJson(); toast("Undone"); refreshPage(); return; }
        }

        // left mouse button: a fresh press on a slider
        boolean mouse = PlatformUtil.leftMouseDown();
        boolean pressed = mouse && !mouseWas;
        mouseWas = mouse;
        if (!mouse) for (Slider sl : sliders) sl.settle();
        if (pressed && tab != null) {
            for (Slider sl : sliders) {
                if (!sl.isMouseOver(mouseX, mouseY)) continue;
                String view = viewForKey(sl.key);
                if (view != null) PlushRenderer.previewSetView(view); // the Scale sliders show their own view in the preview
                long t = System.nanoTime();
                if (PlatformUtil.ctrlDown()) {                        // Ctrl+click: type a number
                    sl.restoreSettled();                              // the click itself moved the slider; undo that
                    openValuePrompt(sl);
                    return;
                }
                if (sl == lastClickSlider && t - lastClickNs < 400_000_000L && Math.abs(mouseX - lastClickX) <= 4) { // double-click: back to default
                    lastClickSlider = null;
                    sl.resetToDefault();
                    return;
                }
                lastClickSlider = sl; lastClickNs = t; lastClickX = mouseX;
                break;
            }
        }

        if (tabPending && UiDraw.progress(pendingNs, PAGE_OUT) >= 1f) applyTab(pendingTab);
        if (searchDirty) { // the text in the search box changed: show the matching rows
            searchDirty = false;
            scroll = 0;
            rowsNs = System.nanoTime(); rowsStep = 12_000_000L;
            rebuildWidgets();
        }
    }

    /** Scale-tab slider -> the preview view that shows it. */
    private static String viewForKey(String key) {
        if (key == null) return null;
        return switch (key) {
            case "SCALE|First person" -> "first_person";
            case "SCALE|Third person" -> "third_person";
            case "SCALE|Inventory" -> "gui";
            case "SCALE|On the ground" -> "ground";
            case "SCALE|Item frame / pop" -> "fixed";
            case "SCALE|On head" -> "head";
            default -> null;
        };
    }

    private void openValuePrompt(Slider sl) {
        ScreenUtil.open(new TextPromptScreen(this, "Type a value", "From " + sl.rangeText(), sl.plainValue(), 10, text -> {
            try {
                sl.setTyped(Double.parseDouble(text.trim().replace(',', '.')));
            } catch (NumberFormatException e) {
                toast("That is not a number");
            }
        }));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        UiDraw.beginFrame();
        pollShortcuts(mouseX, mouseY);
        boolean home = tab == null;
        // Opening / closing is a plain fade, with no black cover: the window's panels fade in over the game (so it shows
        // through), then the contents fade in on top of them. Closing plays the same thing backwards.
        float vis = closingNs != 0 ? 1f - UiDraw.progress(closingNs, CLOSE_T) : UiDraw.progress(openNs, OPEN_T);   // 0 = gone, 1 = fully there
        float shell = UiDraw.ease(Math.min(1f, vis / SHELL_END));                                                  // panels: 0 -> 1 while opening
        float cover = vis <= SHELL_END ? 1f : 1f - UiDraw.ease((vis - SHELL_END) / (1f - SHELL_END));              // contents: 1 (hidden) -> 0
        g.fill(0, 0, width, height, UiDraw.withAlpha(DIM, dimHeld && closingNs == 0 ? 1f : UiDraw.ease(vis)));
        UiDraw.ring(g, wx - 2, wy - 2, wx + ww + 2, wy + wh + 2, 2, UiDraw.withAlpha(FRAME, shell));            // the thin black outline
        g.fill(wx, wy + HEADER_H, wx + ww, wy + wh, UiDraw.withAlpha(CONTENT_BG, shell));
        if (!home) g.fill(wx, wy + HEADER_H, wx + RAIL_W, bodyBottom, UiDraw.withAlpha(SIDEBAR_BG, shell));
        if (previewOn && !home) g.fill(previewX - 4, wy + HEADER_H, wx + ww, bodyBottom, UiDraw.withAlpha(SIDEBAR_BG, shell));
        g.fill(wx, bodyBottom, wx + ww, wy + wh, UiDraw.withAlpha(SIDEBAR_BG, shell));                          // footer strip with the Done button
        if (vis < SHELL_END) {   // only the panels so far: the header joins them, nothing else is drawn yet
            g.fill(wx, wy, wx + ww, wy + HEADER_H, UiDraw.withAlpha(0xFFE2E4E5, shell));
            finishClose();
            return;
        }
        g.fill(wx, bodyBottom, wx + ww, bodyBottom + 1, 0xFF343537);                   // groove between the page and the footer
        g.fill(wx, bodyBottom + 1, wx + ww, bodyBottom + 2, 0xFF535457);
        if (!home) {
            groove(g, wx + RAIL_W - 2, wy + HEADER_H + 2, bodyBottom, false);                 // rail | content
            if (previewOn) groove(g, previewX - 4, wy + HEADER_H + 2, bodyBottom, true);      // content | preview panel
        }

        drawHeader(g);
        Icons.nineSlice(g, "widgets/frame_raised", wx, wy, ww, wh, 3);   // shaded outline round the whole window
        drawBanner(g);

        // rail: icons only, the selected entry's highlight slides to it
        if (!home) {
            int iconSize = 12;                       // icons are 12 x 12: drawn 1:1 so the pixels stay square
            float selTarget = -1;
            for (TabHit th : tabHits) if (ENTRIES[th.index()] == tab) selTarget = th.b().getY();
            if (selTarget >= 0) {
                selAnimY = selAnimY < 0 ? selTarget : UiDraw.approach(selAnimY, selTarget, 18f);
                int sy = Math.round(selAnimY);
                g.fill(wx, sy, wx + RAIL_W, sy + tabH, SEL_BG);
                g.fill(wx, sy, wx + RAIL_W, sy + 1, SEL_TOP);
                g.fill(wx, sy + tabH - 1, wx + RAIL_W, sy + tabH, SEL_BOTTOM);
                g.fill(wx, sy, wx + 3, sy + tabH, GREEN_HI); // marker on the left edge
            }
            for (TabHit th : tabHits) {
                Tab t = ENTRIES[th.index()];
                int y = th.b().getY(), ix = wx + (RAIL_W - iconSize) / 2;
                float lift = UiDraw.hover(UiDraw.key(wx, y, RAIL_W, tabH) ^ 7L, th.b().isHovered() && t != tab);
                int iy = y + (tabH - iconSize) / 2 - Math.round(lift * 2f); // icon hops up a little on hover
                if (th.b().isHovered() && t != tab) g.fill(wx, y, wx + RAIL_W, y + tabH, 0xFF3A3B3C);
                if (t == null) drawHomeGlyph(g, ix, iy, iconSize, TITLE);
                else {
                    Icons.draw(g, t.icon, ix, iy, iconSize);
                    if (analysis().perTab[t.ordinal()] > 0) { // something on that page differs from the defaults
                        g.fill(ix + iconSize - 2, iy - 2, ix + iconSize + 3, iy + 3, FRAME);
                        g.fill(ix + iconSize - 1, iy - 1, ix + iconSize + 2, iy + 2, CHANGED);
                    }
                }
            }
            drawRailScrollbar(g);
        }

        // rows: name + description. A tooltip (with a picture, if there is one) shows when you hover the name.
        String tooltip = null, tooltipImg = null;
        for (Row r : rows) {
            if (r.desc() == null) { // section header: accent-coloured caps with a rule running to the right
                int acc = accent(r.tab()), hy = r.y() + rowH - 15;
                String t = r.title().toUpperCase();
                heading(g, t, contentX, hy - 1, acc);
                int lx = contentX + headingWidth(t) + 8, lx1 = mainRight - 20;
                if (lx1 > lx) {
                    g.fill(lx, hy + 4, lx1, hy + 5, 0xFF343537);   // same two-tone groove as the row lines
                    g.fill(lx, hy + 5, lx1, hy + 6, 0xFF535457);
                }
                g.fill(contentX, r.y() + rowH - 3, contentX + 12, r.y() + rowH - 2, acc);
                continue;
            }
            float rowHot = UiDraw.hover(UiDraw.key(wx, r.y(), 3, 3) ^ 13L, rowControlHovered(r));   // the whole bar lights up while its control is hovered
            if (rowHot > 0.01f) g.fill(wx + RAIL_W + 3, r.y(), mainRight - 20, r.y() + rowH - 2, UiDraw.withAlpha(0x1EFFFFFF, rowHot));
            g.fill(wx + RAIL_W + 3, r.y() + rowH - 2, mainRight - 20, r.y() + rowH - 1, 0xFF343537);
            g.fill(wx + RAIL_W + 3, r.y() + rowH - 1, mainRight - 20, r.y() + rowH, 0xFF535457);
            // two lines like the Punchy settings: the name, and under it what the setting does in grey
            String shown = fit(r.title(), titleMaxW), descShown = fit(r.desc(), titleMaxW);
            int tw = minecraft.font.width(shown), ty = r.y() + (rowH - 2 - 19) / 2;
            boolean nameHover = mouseX >= contentX - 3 && mouseX <= contentX + titleMaxW && mouseY >= ty - 3 && mouseY <= ty + 20;
            g.text(minecraft.font, shown, contentX, ty, nameHover ? 0xFFA8E68F : TITLE, false);
            g.text(minecraft.font, descShown, contentX, ty + 10, DESC, false);
            String helpImg = helpImage(r.tab(), ENGLISH.getOrDefault(r.title(), r.title()));
            if (nameHover && (helpImg != null || !descShown.equals(r.desc()) || !shown.equals(r.title()))) { // full text / picture only when there is more to see
                tooltip = r.desc();
                tooltipImg = helpImg;
            }
        }

        if (!home && defs.isEmpty()) {
            String none = filtering() ? "Nothing matches" : "No settings here";
            g.text(minecraft.font, none, (wx + RAIL_W + mainRight) / 2 - minecraft.font.width(none) / 2, rowsTop + 30, DESC, false);
            if (filtering() && !searchAll) {
                String hint = "Try the All switch to search every page";
                g.text(minecraft.font, fit(hint, mainRight - wx - RAIL_W - 20), (wx + RAIL_W + mainRight) / 2 - Math.min(minecraft.font.width(hint), mainRight - wx - RAIL_W - 20) / 2, rowsTop + 44, HEADER_MUTED, false);
            }
        }
        if (home) drawCards(g);
        drawTiles(g);
        if (previewOn) drawPreview(g, mouseX, mouseY);

        super.extractRenderState(g, mouseX, mouseY, delta); // invisible widgets (input only)

        for (Styled s : buttons) drawButton(g, s);
        for (Toggle t : toggles) drawToggle(g, t);
        for (Slider s : sliders) drawSlider(g, s);
        for (Reset r : resets) drawReset(g, r);
        if (previewOn) drawPreviewControls(g);
        drawRowReveal(g);
        if (searchBox != null) Icons.nineSlice(g, "widgets/frame_inset", searchBox.getX(), searchBox.getY(), searchBox.getWidth(), searchBox.getHeight(), 3);
        if (searchBox != null && searchText.isEmpty() && !searchBox.isFocused())
            g.text(minecraft.font, "Search...", searchBox.getX() + 5, searchBox.getY() + (searchBox.getHeight() - 8) / 2, 0xFF777779, false);
        if (replayBtn != null && replayBtn.isHovered()) { tooltip = "Replay"; tooltipImg = null; }
        if (scopeBtn != null && scopeBtn.isHovered()) { tooltip = "Search this page only, or every page"; tooltipImg = null; }
        if (changedBtn != null && changedBtn.isHovered()) { tooltip = "Show only settings that differ from the defaults"; tooltipImg = null; }

        railTip = null;
        for (TabHit th : tabHits) {
            HitButton h = th.b();
            UiDraw.outline(g, h.getX() + 2, h.getY() + 2, h.getWidth() - 4, h.getHeight() - 4,
                    UiDraw.hover(UiDraw.key(h.getX(), h.getY(), h.getWidth(), h.getHeight()), h.isHovered()));
            if (h.isHovered()) {
                Tab t = ENTRIES[th.index()];
                String label = t == null ? "All settings" : t.label;
                if (t != null && analysis().perTab[t.ordinal()] > 0) label += " (" + analysis().perTab[t.ordinal()] + " changed)";
                if (!label.equals(railTip)) railTipNs = lastRailTip(label) ? railTipNs : System.nanoTime();
                railTip = label;
                railTipY = h.getY();
            }
        }
        lastRailTipLabel = railTip;
        drawScrollbar(g);
        if (railTip != null) drawRailTip(g, railTip, wx + RAIL_W + 4, railTipY + (tabH - 14) / 2, UiDraw.progress(railTipNs, 0.1));

        if (tooltip != null) {
            String tipKey = tooltip + "|" + tooltipImg;
            if (!tipKey.equals(lastTooltip)) { lastTooltip = tipKey; tooltipNs = System.nanoTime(); }
            drawTooltip(g, tooltip, tooltipImg, mouseX, mouseY, UiDraw.progress(tooltipNs, 0.12));
        } else lastTooltip = null;
        hoverSound();
        if (doneBtn != null) {   // footer: wide green DONE bar in the chunky pixel font
            int sink = UiDraw.button(g, minecraft.font, doneBtn.getX(), doneBtn.getY(), doneBtn.getWidth(), doneBtn.getHeight(), "",
                    GREEN, GREEN_HI, GREEN_SHADOW, WHITE, true, doneBtn.isHovered());
            int dw = PixelFont.width("DONE");
            PixelFont.draw(g, "DONE", doneBtn.getX() + (doneBtn.getWidth() - dw) / 2, doneBtn.getY() + 6 + sink, WHITE, GREEN_SHADOW);
        }
        drawToast(g);

        if (cover > 0f) {   // window opening / closing: the contents fade into their own panel colour (no black anywhere)
            g.fill(wx, wy, wx + ww, wy + HEADER_H + 2, UiDraw.withAlpha(0xFFE2E4E5, cover));
            if (!home) g.fill(wx, wy + HEADER_H + 2, wx + RAIL_W, bodyBottom, UiDraw.withAlpha(SIDEBAR_BG, cover));
            g.fill(home ? wx : wx + RAIL_W, wy + HEADER_H + 2, previewOn && !home ? previewX - 4 : wx + ww, bodyBottom, UiDraw.withAlpha(CONTENT_BG, cover));
            if (previewOn && !home) g.fill(previewX - 4, wy + HEADER_H + 2, wx + ww, bodyBottom, UiDraw.withAlpha(SIDEBAR_BG, cover));
            g.fill(wx, bodyBottom, wx + ww, wy + wh, UiDraw.withAlpha(SIDEBAR_BG, cover));
        }
        finishClose();
    }

    private static final Map<Identifier, int[]> TEX_SIZES = new HashMap<>();

    private static int[] textureSize(Identifier tex) {
        return TEX_SIZES.computeIfAbsent(tex, id -> {
            try (java.io.InputStream in = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()) {
                byte[] b = in.readNBytes(24); // PNG width/height live in the IHDR chunk
                int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
                int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
                if (w > 0 && h > 0) return new int[]{w, h};
            } catch (Throwable ignored) { }
            return new int[]{64, id.getPath().contains("cape") ? 32 : 64}; // dynamic textures (your skin / cape) have no file to measure
        });
    }

    /** Small turning 3D thumbnails of each accessory, drawn beside its switch on the Accessories page. */
    private void drawTiles(GuiGraphicsExtractor g) {
        if (tiles.isEmpty()) return;
        PlushRenderer.Skin sk = PlushRenderer.previewSkin();
        Identifier own = sk.texture() != PlushRenderer.TEXTURE ? sk.texture() : null;
        int size = 26, inner = size - 4;
        for (Tile t : tiles) {
            g.fill(t.x() - 1, t.y() - 1, t.x() + size + 1, t.y() + size + 1, CARD_EDGE);
            g.fill(t.x(), t.y(), t.x() + size, t.y() + size, CARD_BG);
            Icons.nineSlice(g, "widgets/frame_inset", t.x() - 1, t.y() - 1, size + 2, size + 2, 3);
            // the accessory's real 3D model, slowly turning (the flat texture below is only a fallback)
            float spin = 28f + (float) ((System.nanoTime() / 1e9) * 40.0 % 360.0);
            if (PlushPreview.drawAccessory(g, t.x() + 2, t.y() + 2, inner, inner, t.a(), own, sk.cape(), spin)) continue;
            Identifier tex = t.a().textureFor(own, sk.cape(), PlushRenderer.TEXTURE);
            int[] dim = textureSize(tex);
            float k = Math.min((float) inner / dim[0], (float) inner / dim[1]);
            int dw = Math.max(1, Math.round(dim[0] * k)), dh = Math.max(1, Math.round(dim[1] * k));
            // texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight
            g.blit(RenderPipelines.GUI_TEXTURED, tex, t.x() + (size - dw) / 2, t.y() + (size - dh) / 2, 0, 0, dw, dh, dim[0], dim[1], dim[0], dim[1]);
        }
    }

    /** A short message ("Saved preset X") that fades out at the bottom of the window. */
    private void drawToast(GuiGraphicsExtractor g) {
        if (toastText == null) return;
        double age = (System.nanoTime() - toastNs) / 1e9;
        if (age > 2.6) { toastText = null; return; }
        float a = age < 2.0 ? 1f : (float) (1.0 - (age - 2.0) / 0.6);
        int w = minecraft.font.width(toastText) + 16, h = 18, x = wx + (ww - w) / 2, y = bodyBottom - h - 6;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, UiDraw.withAlpha(0xFF000000, a));
        g.fill(x, y, x + w, y + h, UiDraw.withAlpha(0xFF2C2D2E, a));
        g.text(minecraft.font, toastText, x + 8, y + 5, UiDraw.withAlpha(TITLE, a), false);
    }

    /**
     * Page dissolve like Punchy's: the old page breaks up into the background in noisy pixel cells, then the new one
     * comes in the same way. Only used when you switch tabs.
     */
    private void drawRowReveal(GuiGraphicsExtractor g) {
        int y0 = wy + HEADER_H + 2, y1 = bodyBottom - 2;
        int x0 = tab == null ? wx + 2 : wx + RAIL_W;
        int x1 = tab == null ? wx + ww - 2 : (previewOn ? previewX - 4 : wx + ww - 2);
        if (tabPending) {   // old page (or the home cards) going away
            UiDraw.dissolveOver(g, x0, y0, x1, y1, UiDraw.ease(UiDraw.progress(pendingNs, PAGE_OUT)), CONTENT_BG);
            return;
        }
        if (rowsStep == 0L) {   // new page coming in
            UiDraw.dissolveOver(g, x0, y0, x1, y1, 1f - UiDraw.ease(UiDraw.progress(rowsNs, PAGE_IN)), CONTENT_BG);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {   // scrolling / searching: quick plain fade, row by row
            float a = 1f - UiDraw.ease(UiDraw.progress(rowsNs + i * rowsStep, 0.16));
            if (a < 0.01f) continue;
            Row r = rows.get(i);
            g.fill(wx + RAIL_W, r.y(), mainRight - 20, r.y() + rowH, UiDraw.withAlpha(CONTENT_BG, a));
        }
    }

    private String lastRailTipLabel;
    private boolean lastRailTip(String label) { return label.equals(lastRailTipLabel); }

    /** What each category is about (shown in the banner). */
    private static String blurb(Tab t) {
        return switch (t) {
            case GENERAL -> "Speeds, blending and triggers";
            case SCALE -> "How big the plush is in every view";
            case LAYOUT -> "Where it sits and where it animates";
            case ANIMATIONS -> "Tune each animation one by one";
            case EMOTES -> "The G key, wheel slots and emotes";
            case PARTICLES -> "Flames, bubbles and snowflakes (beta)";
            case SOUND -> "Plush and menu sounds";
            case SKIN -> "Wear your own skin on the plush";
            case COSMETICS -> "Capes and other accessories";
            case PRESETS -> "Save, load and share your whole setup";
        };
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gr = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    /**
     * Banner under the header. Home: a "PlushPal" plate whose colour and line of text follow the category you hover,
     * with a ribbon of every category colour along the bottom (the hovered one swells). Inside a category: its icon,
     * name, a one-line description and setting count on a plate in that category's colour.
     */
    private void drawBanner(GuiGraphicsExtractor g) {
        boolean home = tab == null;
        Tab hov = null;
        if (home) for (Card cd : cards) if (cd.b().isHovered()) hov = cd.tab();
        Tab shown = home ? hov : tab;
        boolean modOn = PlushConfig.get().modEnabled;
        boolean overSwitch = home && masterSwitch != null && masterSwitch.isHovered();
        int target = shown != null ? accent(shown) : (modOn ? 0xFF5FB04A : 0xFF8A8B8D);
        bannerColor = mix(bannerColor, target, 1f - (float) Math.exp(-14f * Math.min(UiDraw.dt, 1f)));
        int col = bannerColor;

        int x = home ? homeX0 : wx + RAIL_W + 6, w = (home ? homeX1 : mainRight - 6) - x;
        int y = wy + HEADER_H + (home ? 8 : 4), h = home ? HOME_BANNER_H : BANNER_H - 4;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, DARK);
        g.fill(x, y, x + w, y + h, 0xFF3E3F41);
        g.fill(x, y, x + 4, y + h, col);
        g.fill(x + 4, y, x + w, y + 1, SEL_TOP);
        Icons.nineSlice(g, "widgets/frame_raised", x - 1, y - 1, w + 2, h + 2, 3);

        // ribbon: every category's colour; on home the hovered one swells
        if (home) {
            int n = Tab.values().length;
            for (int i = 0; i < n; i++) {
                int sx0 = x + 4 + (w - 4) * i / n, sx1 = x + 4 + (w - 4) * (i + 1) / n;
                boolean on = Tab.values()[i] == hov;
                g.fill(sx0, y + h - (on ? 5 : 2), sx1 - (i < n - 1 ? 1 : 0), y + h, accent(Tab.values()[i]));
            }
        } else {
            g.fill(x + 4, y + h - 2, x + w, y + h, col);
        }

        int iconSize = h >= 26 ? 24 : 12;
        String icon = shown != null ? shown.icon : "icons/emotes";
        Icons.draw(g, icon, x + 10, y + (h - iconSize) / 2 - (home ? 1 : 0), iconSize);
        int tx = x + 10 + iconSize + 8;
        String title = home ? "PLUSHPAL" : tab.label.toUpperCase();
        String sub = overSwitch ? "Turn the whole mod on or off"
                : shown != null ? blurb(shown)
                : home ? (modOn ? "ON - pick a category below" + (analysis().total > 0 ? "  -  " + analysis().total + " changed" : "") : "OFF - the totem looks vanilla")
                : "";
        heading(g, title, tx, y + (home ? 7 : 5), TITLE);
        int right = x + w - 8 - (home ? SWITCH_W + 8 : 0);
        if (!home) {
            right = searchX - 8;                              // the search box sits at the right end of the banner
            if (!modOn) {
                String off = "MOD OFF";
                int ow = minecraft.font.width(off);
                if (right - ow > tx + 70) { right -= ow; g.text(minecraft.font, Component.literal(off).withStyle(ChatFormatting.BOLD), right, y + 5, UNSAVED, false); right -= 8; }
            }
            int cnt = settingCount();
            String n = filtering() ? cnt + " found" : cnt + (cnt == 1 ? " setting" : " settings");
            if (!filtering() && analysis().perTab[tab.ordinal()] > 0) n += ", " + analysis().perTab[tab.ordinal()] + " changed";
            int nw = minecraft.font.width(n);
            if (right - nw > tx + 70) { g.text(minecraft.font, n, right - nw, y + 5, DESC, false); right -= nw + 8; }
        }
        g.text(minecraft.font, fit(sub, Math.max(20, right - tx)), tx, y + (home ? 18 : 16), home && hov == null ? DESC : mix(DESC, col, 0.45f), false);
    }

    /** Light header: back arrow, "PLUSHPAL > CATEGORY" breadcrumb, unsaved marker (buttons sit on top of it). */
    /** A vertical divider: two dark steps and a light line beside them. {@code lightLeft} puts the light line on the left of the dark steps. */
    private static void groove(GuiGraphicsExtractor g, int x, int y0, int y1, boolean lightLeft) {
        if (lightLeft) { g.fill(x - 1, y0, x, y1, 0xFF525255); g.fill(x, y0, x + 1, y1, 0xFF2B2C2F); g.fill(x + 1, y0, x + 2, y1, 0xFF212224); }
        else { g.fill(x, y0, x + 1, y1, 0xFF2B2C2F); g.fill(x + 1, y0, x + 2, y1, 0xFF212224); g.fill(x + 2, y0, x + 3, y1, 0xFF525255); }
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        g.fill(wx, wy, wx + ww, wy + HEADER_H, 0xFFE2E4E5);                       // face
        g.fill(wx, wy, wx + ww, wy + 2, 0xFFECECEE);                              // light top edge
        int[] shade = {0xFFDCDDDE, 0xFFBFC0C2, 0xFFB2B2B5, 0xFF9E9FA3};           // the bottom fades into a soft shade
        for (int i = 0; i < shade.length; i++) g.fill(wx, wy + HEADER_H - shade.length + i, wx + ww, wy + HEADER_H - shade.length + i + 1, shade[i]);
        g.fill(wx, wy + HEADER_H, wx + ww, wy + HEADER_H + 1, 0xFF505254);        // then a two-step dark line
        g.fill(wx, wy + HEADER_H + 1, wx + ww, wy + HEADER_H + 2, 0xFF222325);
        int ty = wy + (HEADER_H - 8) / 2;
        if (tab != null) g.text(minecraft.font, Component.literal("<").withStyle(ChatFormatting.BOLD), wx + 12, ty, HEADER_TEXT, false);
        int bw = headingWidth("PLUSHPAL");
        if (PixelFont.supports("PLUSHPAL")) PixelFont.draw(g, "PLUSHPAL", wx + (ww - bw) / 2, ty, HEADER_TEXT, 0x55000000);
        else heading(g, "PLUSHPAL", wx + (ww - bw) / 2, ty, HEADER_TEXT);

        // Undo changes (N): dimmed while there is nothing to undo
        int n = undoCount();
        String label = (undoBtn.getWidth() >= minecraft.font.width("Undo changes (99)") + 16 ? "Undo changes" : "Undo") + " (" + n + ")";
        UiDraw.button(g, minecraft.font, undoBtn.getX(), undoBtn.getY(), undoBtn.getWidth(), undoBtn.getHeight() + 2, label,
                FACE, FACE_HI, FACE_SHADOW, n > 0 ? DARK : HEADER_MUTED, false, n > 0 && undoBtn.isHovered());

        // X: two thick pixel diagonals
        int cx = closeBtn.getX() + closeBtn.getWidth() / 2, cy = wy + (HEADER_H - 2) / 2, r = 5;
        int col = closeBtn.isHovered() ? 0xFFB04040 : HEADER_TEXT;
        for (int i = -r; i <= r; i++) {
            g.fill(cx + i - 1, cy + i - 1, cx + i + 1, cy + i + 1, col);
            g.fill(cx - i - 1, cy + i - 1, cx - i + 1, cy + i + 1, col);
        }
    }

    /** The "home" glyph: a 3 x 3 grid of little squares. */
    private static void drawHomeGlyph(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int cell = Math.max(3, size / 3 - 1), gap = Math.max(1, (size - 3 * cell) / 2);
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                g.fill(x + c * (cell + gap), y + r * (cell + gap), x + c * (cell + gap) + cell, y + r * (cell + gap) + cell, color);
    }

    /** Dashed "stitching" outline: the plush-y touch on cards and the preview panel. */
    private static void stitch(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 6) {
            int l = Math.min(3, w - i);
            g.fill(x + i, y, x + i + l, y + 1, color);
            g.fill(x + i, y + h - 1, x + i + l, y + h, color);
        }
        for (int j = 0; j < h; j += 6) {
            int l = Math.min(3, h - j);
            g.fill(x, y + j, x + 1, y + j + l, color);
            g.fill(x + w - 1, y + j, x + w, y + j + l, color);
        }
    }

    /** Home page: stitched tiles with a colour stripe, a live status line, a pop-in and a hover lift. */
    private void drawCards(GuiGraphicsExtractor g) {
        PlushConfig c = PlushConfig.get();
        int iconSize = cardH >= 58 ? 24 : 12;   // icons are 12 x 12: whole multiples only, so the pixels stay square
        boolean showStatus = cardH >= 40;
        for (int i = 0; i < cards.size(); i++) {
            Card cd = cards.get(i);
            HitButton hb = cd.b();
            int acc = accent(cd.tab());
            float pop = UiDraw.ease(UiDraw.progress(pageNs + i * 28_000_000L, 0.2));
            float hov = UiDraw.hover(UiDraw.key(hb.getX(), hb.getY(), hb.getWidth(), hb.getHeight()), hb.isHovered());
            int x = hb.getX(), y = hb.getY() + Math.round((1f - pop) * 8f) - Math.round(hov * 2f), w = hb.getWidth(), h = cardH;
            g.fill(x, y, x + w, y + h, DARK);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hov > 0.5f ? CARD_HOVER : CARD_BG);
            g.fill(x + 1, y + 1, x + w - 1, y + 4, acc);                       // colour stripe
            g.fill(x + 1, y + 4, x + w - 1, y + 5, SEL_BOTTOM);
            g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, CARD_EDGE);
            stitch(g, x + 3, y + 7, w - 6, h - 10, hov > 0.5f ? acc : STITCH);

            int textH = showStatus ? 20 : 9, top = y + 6 + (h - 6 - (iconSize + 4 + textH)) / 2;
            Icons.draw(g, cd.tab().icon, x + (w - iconSize) / 2, top, iconSize);
            String label = fit(cd.tab().label, w - 10);
            g.text(minecraft.font, label, x + (w - minecraft.font.width(label)) / 2, top + iconSize + 4, TITLE, false);
            if (showStatus) {
                int changed = analysis().perTab[cd.tab().ordinal()];
                String line = hb.isHovered() ? cd.count() + (cd.count() == 1 ? " setting" : " settings") + (changed > 0 ? ", " + changed + " changed" : "") : status(cd.tab(), c);
                line = fit(line, w - 8);
                g.text(minecraft.font, line, x + (w - minecraft.font.width(line)) / 2, top + iconSize + 15, hb.isHovered() ? acc : DESC, false);
            }
            int badge = analysis().perTab[cd.tab().ordinal()];
            if (badge > 0) { // "N changed": a small amber badge in the corner
                String n = String.valueOf(badge);
                int bw = minecraft.font.width(n) + 6, bx = x + w - bw - 4, by = y + 7;
                g.fill(bx - 1, by - 1, bx + bw + 1, by + 11, DARK);
                g.fill(bx, by, bx + bw, by + 10, CHANGED);
                g.text(minecraft.font, n, bx + 3, by + 1, DARK, false);
            }
            Icons.nineSlice(g, "widgets/frame_raised", x, y, w, h, 3);
            UiDraw.outline(g, x, y, w, h, hov);
            if (!c.modEnabled) g.fill(x, y, x + w, y + h, 0x80282829); // mod switched off: cards are dimmed (still usable)
        }
        for (int row = 0; row < GROUPS.length && row * 3 < cards.size(); row++) { // row headlines
            HitButton first = cards.get(row * 3).b();
            int ly = first.getY() - GROUP_LABEL_H + 1, lx = first.getX();
            String label = GROUPS[row];
            g.text(minecraft.font, Component.literal(label).withStyle(ChatFormatting.BOLD), lx, ly, DESC, false);
            int rx0 = lx + minecraft.font.width(Component.literal(label).withStyle(ChatFormatting.BOLD)) + 8;   // bold text is wider than plain
            if (homeX1 > rx0) {
                g.fill(rx0, ly + 4, homeX1, ly + 5, 0xFF343537);
                g.fill(rx0, ly + 5, homeX1, ly + 6, 0xFF535457);
            }
        }
    }

    /** The live preview, like the Punchy one: a sky-and-grass scene with your plush in it. Drag to turn it. */
    private void drawPreview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = previewX, w = previewW, y = previewTop, b = previewBottom;
        int horizon = y + Math.round((b - y) * 0.66f);
        for (int yy = y; yy < horizon; yy += 2) {                 // sky: banded, deeper blue at the top
            g.fill(x, yy, x + w, Math.min(horizon, yy + 2), mix(0x6F9BE0, 0xBFD9F5, (yy - y) / (float) Math.max(1, horizon - y)));
        }
        for (int yy = horizon; yy < b; yy += 2) {                  // grass: bright at the horizon, darker towards you
            g.fill(x, yy, x + w, Math.min(b, yy + 2), mix(0x6FA83F, 0x3F7A2A, (yy - horizon) / (float) Math.max(1, b - horizon)));
        }
        g.fill(x, horizon, x + w, horizon + 1, 0x33FFFFFF);        // a faint line where they meet

        int modelTop = y + 22, modelBottom = previewBarTop - 4, cx = x + w / 2;
        int floorY = modelBottom - 10;
        g.fill(cx - 22, floorY, cx + 22, floorY + 2, 0x33000000); // little floor shadow, widest at the bottom
        g.fill(cx - 30, floorY + 2, cx + 30, floorY + 4, 0x33000000);
        g.fill(cx - 22, floorY + 4, cx + 22, floorY + 6, 0x33000000);

        long now = System.nanoTime();
        float rdt = lastPreviewNs == 0 ? 0f : Math.min(0.1f, (now - lastPreviewNs) / 1e9f);
        lastPreviewNs = now;
        boolean grabbed = yawSlider != null && (yawSlider.isHovered() || yawSlider.isFocused());
        if (!grabbed) autoYaw = (autoYaw + 28f * rdt) % 360f; // slow turntable; stops while the cursor is over the preview
        boolean drawn = PlushPreview.draw(g, x + 4, modelTop, w - 8, floorY + 5 - modelTop, PlushRenderer.previewYaw + 28f + autoYaw);
        if (!drawn) {
            String msg = "No preview";
            g.text(minecraft.font, msg, cx - minecraft.font.width(msg) / 2, (modelTop + modelBottom) / 2 - 4, WHITE, true);
        }
        // darker strip along the bottom for the name
        g.fill(x, previewBarTop - 3, x + w, b, 0x66000000);
        g.fill(x, previewBarTop - 3, x + w, previewBarTop - 2, 0x22000000);
        if (!PlushConfig.get().modEnabled) { // mod switched off: veil over the stage
            g.fill(x, y, x + w, b, 0x99202021);
            Component off = Component.literal("MOD OFF").withStyle(ChatFormatting.BOLD);
            g.text(minecraft.font, off, cx - minecraft.font.width(off) / 2, (modelTop + modelBottom) / 2 - 4, UNSAVED, false);
        }
        Icons.nineSlice(g, "widgets/frame_inset", x, y, w, b - y, 3);   // the stage is recessed
    }

    /** Under the stage: the plush's name, with an amber line under it that fills as the picked animation plays. */
    private void drawTimeline(GuiGraphicsExtractor g) {
        int pad = 6, x = previewX + pad, w = previewW - 2 * pad, y = previewBarTop;
        String name = fit(PlushData.mixName(), w);
        g.text(minecraft.font, name, x, y, WHITE, true);
        int ly = y + 10, filled = Math.round(Mth.clamp(PlushRenderer.previewProgress(), 0f, 1f) * w);
        g.fill(x, ly, x + w, ly + 1, 0x55000000);
        if (filled > 0) g.fill(x, ly - 1, x + filled, ly + 1, BAR_FILL);
    }

    /**
     * The bar from the Punchy settings: a dark track, a fill and a square handle (the button texture). The fill runs from
     * {@code from} to {@code to} and the handle sits at {@code at} (all 0..1 of the whole track, so a full bar is really full);
     * {@code h} is the track's height.
     */
    private void drawBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float from, float to, float at, boolean hovered, boolean pressed) {
        g.fill(x, y, x + w, y + h, BAR_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, TRACK);
        int inner = w - 2;
        int fx0 = x + 1 + Math.round(Mth.clamp(Math.min(from, to), 0f, 1f) * inner), fx1 = x + 1 + Math.round(Mth.clamp(Math.max(from, to), 0f, 1f) * inner);
        if (fx1 > fx0) {
            g.fill(fx0, y + 1, fx1, y + h - 1, BAR_FILL);
            g.fill(fx0, y + 1, fx1, y + 2, BAR_FILL_HI);
            g.fill(fx0, y + h - 2, fx1, y + h - 1, BAR_FILL_SHADOW);
        }
        // square handle from textures/gui/widgets/button*.png; its centre follows the vanilla slider underneath (4 + f * (w - 8))
        int hs = h + 6;   // a square twice as tall as the thin track
        int hx = x + 4 + Math.round(Mth.clamp(at, 0f, 1f) * (w - 8)) - hs / 2, hy = y + (h - hs) / 2;
        Icons.nineSlice(g, "widgets/button" + (pressed ? "_pressed" : hovered ? "_hover" : ""), hx, hy, hs, hs, 3);   // rest / hover / held
    }

    private static String pvLabel(String id) {
        for (int i = 0; i < PV_IDS.length; i++) if (PV_IDS[i].equals(id)) return PV_LABELS[i];
        return id;
    }

    /** The parts of the preview controls that change without a rebuild: the view name and the "> Idle" button. */
    /** The circling "undo" arrow (a thick ring with an arrowhead at the upper left), 15 x 15, pixelated but still round. */
    private static final String[] REPLAY_ICON = {
            ".....XXXXX.....", ".XXXXXXXXXXX...", ".XXXXXXXXXXXX..", ".XXXX.....XXXX.", ".XXXX......XXX.",
            "............XXX", "............XXX", "XX..........XXX", "XXX.........XXX", "XXX.........XXX",
            ".XXX.......XXX.", ".XXXX.....XXXX.", "..XXXXXXXXXXX..", "...XXXXXXXXX...", ".....XXXXX....."};

    private void drawPreviewControls(GuiGraphicsExtractor g) {
        drawTimeline(g);
        if (replayBtn != null) {
            // like the recording: a plain light-grey square with a dark frame and a thick bottom edge, dark circling arrows on it
            int bx = replayBtn.getX(), by = replayBtn.getY(), bs = replayBtn.getWidth();
            Icons.nineSlice(g, "widgets/button", bx, by, bs, bs, 3);
            int rn = REPLAY_ICON.length, ix = bx + (bs - rn) / 2, iy = by + (bs - 3 - rn) / 2, ic = replayBtn.isHovered() ? GREEN : DARK;   // hover: UI green
            for (int r = 0; r < rn; r++)
                for (int c = 0; c < rn; c++)
                    if (REPLAY_ICON[r].charAt(c) == 'X') g.fill(ix + c, iy + r, ix + c + 1, iy + r + 1, ic);
        }
        if (animBtn != null && animPrev != null) {
            PlushConfig.Anim an = PlushRenderer.previewAnimFor(PlushConfig.get(), pvCurrent);
            boolean off = an != null && !an.enabled;          // that animation is switched off in the settings: shown dimmed
            int x0 = animPrev.getX(), x1 = animBtn.getX() + animBtn.getWidth(), y0 = animPrev.getY(), y1 = y0 + animPrev.getHeight();
            boolean hov = animPrev.isHovered() || animBtn.isHovered();
            boolean down = UiDraw.pressed(UiDraw.key(x0, y0, 14, 16), animPrev.isHovered()) || UiDraw.pressed(UiDraw.key(animBtn.getX(), y0, animBtn.getWidth(), 16), animBtn.isHovered());
            g.fill(x0, y0, x1, y1, down ? 0xF00E1014 : hov ? 0xE0343A46 : 0xC0242830);
            int tc = off ? 0xFF8A8B8D : WHITE;
            g.text(minecraft.font, "<", x0 + 4, y0 + 4, animPrev.isHovered() ? GREEN_HI : tc, false);   // hover colour: UI green (was amber)
            g.text(minecraft.font, ">", x1 - 4 - minecraft.font.width(">"), y0 + 4, animBtn.isHovered() ? GREEN_HI : tc, false);
            String label = fit(pvLabel(pvCurrent), x1 - x0 - 24);
            g.text(minecraft.font, label, (x0 + x1) / 2 - minecraft.font.width(label) / 2, y0 + 4, tc, false);
        }
    }

    /** The reset arrow of one row: amber while the setting differs from its default, nothing otherwise. */
    private void drawReset(GuiGraphicsExtractor g, Reset r) {
        if (!isChanged(r.key())) return;
        HitButton b = r.b();
        // bare icon (no box): the recreated circling arrows in UI green, light green on hover, dipping 2 px while pressed
        long rk = UiDraw.key(b.getX(), b.getY(), b.getWidth(), b.getHeight());
        float pr = UiDraw.press(rk, UiDraw.pressed(rk, b.isHovered()));
        int n = REPLAY_ICON.length;
        int ix = b.getX() + (b.getWidth() - n) / 2, iy = b.getY() + (b.getHeight() - n) / 2 + Math.round(pr * 2f);
        int col = b.isHovered() ? 0xFFA8E68F : GREEN_HI;
        for (int ry = 0; ry < n; ry++)
            for (int cx = 0; cx < n; cx++)
                if (REPLAY_ICON[ry].charAt(cx) == 'X') g.fill(ix + cx, iy + ry, ix + cx + 1, iy + ry + 1, col);
    }

    /** The shaded Bedrock frame (same look as widgets/frame_raised.png) drawn with fills so it can fade in with {@code alpha}. */
    private static void bevel(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        int dark = UiDraw.withAlpha(0xFF1E1E1F, alpha);
        g.fill(x, y, x + w, y + 1, dark);                       // outer dark border
        g.fill(x, y + h - 1, x + w, y + h, dark);
        g.fill(x, y + 1, x + 1, y + h - 1, dark);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, dark);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, UiDraw.withAlpha(0x38FFFFFF, alpha));      // light top edge
        g.fill(x + 1, y + 2, x + 2, y + h - 3, UiDraw.withAlpha(0x38FFFFFF, alpha));      // light left edge
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 3, UiDraw.withAlpha(0x19FFFFFF, alpha)); // softer right edge
        g.fill(x + 1, y + h - 3, x + w - 1, y + h - 2, UiDraw.withAlpha(0x23FFFFFF, alpha)); // light line above the shadow
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, UiDraw.withAlpha(0x72000000, alpha)); // thick darker bottom edge
    }

    /** Small dark label that fades in beside a hovered rail icon. */
    private void drawRailTip(GuiGraphicsExtractor g, String text, int x, int y, float alpha) {
        int w = minecraft.font.width(text) + 10, h = 14;
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, UiDraw.withAlpha(FRAME, alpha));
        g.fill(x, y, x + w, y + h, UiDraw.withAlpha(0xFF1E1E1F, alpha));
        bevel(g, x - 2, y - 2, w + 4, h + 4, alpha);
        g.text(minecraft.font, text, x + 5, y + 3, UiDraw.withAlpha(TITLE, alpha), false);
    }

    /** Slim bar on the rail's right edge, only when not every entry fits. */
    private void drawRailScrollbar(GuiGraphicsExtractor g) {
        int count = ENTRIES.length;
        if (count <= tabVisible) return;
        int x = wx + RAIL_W - 4, top = tabTop, bottom = tabTop + tabVisible * tabH, trackH = bottom - top;
        int thumb = Math.max(14, trackH * tabVisible / count);
        int ty = top + (trackH - thumb) * tabScroll / (count - tabVisible);
        g.fill(x, top, x + 3, bottom, 0xFF000000);
        g.fill(x + 1, top + 1, x + 2, bottom - 1, SCROLL_TRACK);
        g.fill(x, ty, x + 3, ty + thumb, 0xFF000000);
        g.fill(x + 1, ty + 1, x + 2, ty + thumb - 1, SCROLL_THUMB);
    }


    /** Shortens text with "..." so it never runs past maxW pixels. */
    private String fit(String text, int maxW) {
        if (minecraft.font.width(text) <= maxW) return text;
        String t = text;
        while (!t.isEmpty() && minecraft.font.width(t + "...") > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private List<String> wrap(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (minecraft.font.width(test) <= maxW || cur.length() == 0) cur = new StringBuilder(test);
            else { lines.add(cur.toString()); cur = new StringBuilder(word); }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * The help picture for a setting, or null. Looks for textures/gui/help/{tab}/{name}.png first, then
     * textures/gui/help/{name}.png, where {name} is the setting's title in lower case with underscores
     * (e.g. "Global speed" -> global_speed). See HELP_IMAGES_README.md.
     */
    private String helpImage(Tab t, String title) {
        String name = slug(title);
        String perTab = "help/" + t.name().toLowerCase() + "/" + name;
        if (Icons.exists(perTab)) return perTab;
        String shared = "help/" + name;
        return Icons.exists(shared) ? shared : null;
    }

    /** Bedrock-style dark tooltip next to the cursor: the description, then the help picture if there is one. */
    private void drawTooltip(GuiGraphicsExtractor g, String text, String img, int mouseX, int mouseY, float alpha) {
        List<String> lines = wrap(text, Math.min(190, width - 24));
        int textW = 0;
        for (String l : lines) textW = Math.max(textW, minecraft.font.width(l));

        int imgW = 0, imgH = 0;
        if (img != null) { // scaled to ~170 px wide (at most 110 tall), keeping the picture's proportions
            int[] d = Icons.sizeOf(img);
            imgW = Math.max(16, Math.min(170, width - 40));
            imgH = Math.max(8, Math.round(imgW * (float) d[1] / d[0]));
            if (imgH > 110) { imgH = 110; imgW = Math.max(16, Math.round(imgH * (float) d[0] / d[1])); }
        }
        int bw = Math.max(textW, imgW) + 12, bh = lines.size() * 11 + 7 + (img != null ? imgH + 6 : 0);
        int x = Mth.clamp(mouseX + 12, 4, Math.max(4, width - bw - 4));
        int y = mouseY + 16 + bh > height - 4 ? Math.max(4, mouseY - bh - 6) : mouseY + 16;
        g.fill(x - 2, y - 2, x + bw + 2, y + bh + 2, UiDraw.withAlpha(FRAME, alpha));
        g.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, UiDraw.withAlpha(HEADER_LINE, alpha));
        g.fill(x, y, x + bw, y + bh, UiDraw.withAlpha(0xFF1E1E1F, alpha));
        bevel(g, x - 2, y - 2, bw + 4, bh + 4, alpha);
        for (int i = 0; i < lines.size(); i++) g.text(minecraft.font, lines.get(i), x + 6, y + 4 + i * 11, UiDraw.withAlpha(TITLE, alpha), false);
        if (img != null && alpha >= 0.99f) { // the picture appears once the tooltip has finished fading in
            int ix = x + 6, iy = y + 4 + lines.size() * 11 + 3;
            g.fill(ix - 1, iy - 1, ix + imgW + 1, iy + imgH + 1, FRAME);
            Icons.drawRect(g, img, ix, iy, imgW, imgH);
        }
    }

    /** A soft tick whenever the cursor moves onto a different control. */
    private void hoverSound() {
        AbstractWidget hovered = null;
        for (AbstractWidget w : hoverables) if (w.isHovered()) { hovered = w; break; }
        int key = hovered == null ? 0 : Objects.hash(hovered.getX(), hovered.getY(), hovered.getWidth(), hovered.getHeight());
        if (key != lastHover) {
            if (key != 0) UiSounds.play("hover", 1f);
            lastHover = key;
        }
    }

    /** Bedrock button: dark border, light inner edge on top/sides, thick darker bottom edge, white outline on hover. */
    private void drawButton(GuiGraphicsExtractor g, Styled s) {
        Button b = s.b();
        UiDraw.button(g, minecraft.font, b.getX(), b.getY(), b.getWidth(), b.getHeight(), s.text(), s.face(), s.hi(), s.shadow(), s.textColor(), s.bold(), b.isHovered());
    }

    /**
     * Bedrock switch from textures/gui/widgets/toggle_*.png (30 x 16). The art shows the finished states:
     * ON  = green "I" track | knob (knob on the right), OFF = knob | gray "O" track (knob on the left).
     * Sliding is done by cutting the pictures into pieces: the green track (left 14 px of ON) and the gray track
     * (right 14 px of OFF) stay put, and the 16 px knob (right part of ON, or the _hover version) is drawn on top at
     * 0..14 px from the left, following the animated position 0 (off) .. 1 (on).
     */
    /** True while the mouse is over a switch, slider or button that belongs to this row. */
    private boolean rowControlHovered(Row r) {
        int y0 = r.y(), y1 = r.y() + rowH;
        for (Toggle t : toggles) if (t.b().isHovered() && t.b().getY() >= y0 && t.b().getY() < y1) return true;
        for (Slider sl : sliders) if (sl.isHovered() && sl.getY() >= y0 && sl.getY() < y1) return true;
        for (Styled st : buttons) if (st.b().isHovered() && st.b().getY() >= y0 && st.b().getY() < y1) return true;
        return false;
    }

    private void drawToggle(GuiGraphicsExtractor g, Toggle t) {
        Button b = t.b();
        boolean on = t.get().getAsBoolean();
        t.pos()[0] = UiDraw.approach(t.pos()[0], on ? 1f : 0f, 18f);
        float pos = Mth.clamp(t.pos()[0], 0f, 1f);
        // fixed size on purpose: 1.5x the 30 x 16 art whatever the window or GUI size, so the switch never changes shape on the player
        final int w = TOGGLE_W, h = TOGGLE_H, tw = 21, kw = 24, travel = 21;       // track halves 14 -> 21, knob 16 -> 24
        int ox = b.getX() + (b.getWidth() - w) / 2, oy = b.getY() + (b.getHeight() - h) / 2;
        Icons.region(g, "widgets/toggle_on", ox, oy, tw, h, 0, 0, 14, 16);                       // green "I" track
        Icons.region(g, "widgets/toggle_off", ox + 24, oy, tw, h, 16, 0, 14, 16);                // gray "O" track
        long tk = UiDraw.key(b.getX(), b.getY(), b.getWidth(), b.getHeight());
        float pr = UiDraw.press(tk, UiDraw.pressed(tk, b.isHovered()));
        String knobTex = pr > 0.5f ? "widgets/toggle_pressed" : b.isHovered() ? "widgets/toggle_knob_hover" : "widgets/toggle_on";   // rest / hover / pressed
        int kx = ox + Math.round(pos * travel);
        Icons.region(g, knobTex, kx, oy, kw, h, 14, 0, 16, 16);                                  // the knob
    }

    private void drawSlider(GuiGraphicsExtractor g, Slider s) {
        int x = s.getX(), y = s.getY(), w = s.getWidth(), h = s.getHeight();
        int th = 6, ty = y + (h - th) / 2;
        float f = (float) s.fraction();
        float zero = s.hasNegative() ? (float) s.fractionOf(0) : 0f;   // where the fill starts
        long sk = UiDraw.key(x, y, w, h);
        boolean grab = (s.isFocused() && PlatformUtil.leftMouseDown()) || UiDraw.pressed(sk, s.isHovered());
        drawBar(g, x, ty, w, th, zero, f, f, s.isHovered() || s.isFocused(), UiDraw.press(sk, grab) > 0.5f);

        String value = s.valueText();
        g.text(minecraft.font, value, x - 10 - minecraft.font.width(value), y + (h - 8) / 2, DESC, false);
    }

    /** Slim page scroll bar like the Punchy settings: a thin dark track with a light grey thumb. */
    private void drawScrollbar(GuiGraphicsExtractor g) {
        if (total <= visible) return;
        int x = sbX + (SCROLL_W - 4) / 2;                          // 4 pixels wide, centred in its lane
        g.fill(x, sbTop, x + 4, sbBottom, 0xFF111112);
        g.fill(x + 1, sbTop + 1, x + 3, sbBottom - 1, SCROLL_TRACK);
        g.fill(x, thumbY, x + 4, thumbY + thumbH, 0xFF111112);
        g.fill(x + 1, thumbY + 1, x + 3, thumbY + thumbH - 1, 0xFFC9CACD);
        g.fill(x + 1, thumbY + thumbH - 2, x + 3, thumbY + thumbH - 1, 0xFF8E8F93);
    }

    // ------------------------------------------------------------------ slider (input only; we draw it ourselves)

    /**
     * The slider: input only (we draw it ourselves). Steps are 0.05; hold Shift while dragging for fine control
     * (a tenth of the speed, steps of 0.01). Double-click resets it (see pollShortcuts), Ctrl+click types a value.
     */
    private static final class Slider extends AbstractSliderButton {
        private final double min, max;
        private final String fmt;
        private final DoubleConsumer set;
        private double lastReal;       // the value stored right now
        private double settledReal;    // the value the last time no mouse button was down (Ctrl+click puts it back)
        private double prevValue;      // slider position before the current drag event
        private boolean fine;          // Shift is being held in this drag
        private double fineAnchorMouse, fineAnchorValue;
        Double defaultValue;           // set by the screen: what a reset restores
        String key;
        Runnable onReset;

        private final boolean quiet; // no tick sounds (used by the preview's drag-to-turn surface)

        Slider(int x, int y, int w, int h, double min, double max, double value, String fmt, DoubleConsumer set) {
            this(x, y, w, h, min, max, value, fmt, set, false);
        }

        Slider(int x, int y, int w, int h, double min, double max, double value, String fmt, DoubleConsumer set, boolean quiet) {
            super(x, y, w, h, Component.empty(), Mth.clamp((value - min) / (max - min), 0, 1));
            this.quiet = quiet;
            this.min = min; this.max = max; this.fmt = fmt; this.set = set;
            this.lastReal = value;
            this.settledReal = value;
            this.prevValue = this.value;
            setAlpha(0f);
        }

        double fraction() { return value; }
        boolean hasNegative() { return min < 0 && max > 0; }
        double fractionOf(double real) { return Mth.clamp((real - min) / (max - min), 0, 1); }
        String valueText() { return String.format(fmt, lastReal); }
        String plainValue() { return String.format(Locale.ROOT, "%.2f", lastReal); }
        String rangeText() { return String.format(Locale.ROOT, "%.2f to %.2f", min, max); }

        void settle() { settledReal = lastReal; }
        void restoreSettled() { setReal(settledReal); }
        void resetToDefault() { if (onReset != null) onReset.run(); }
        void setTyped(double v) { setReal(Math.round(v * 100.0) / 100.0); }

        /** Jumps to an exact value: moves the handle and stores it. */
        void setReal(double r) {
            r = Mth.clamp(r, min, max);
            lastReal = r;
            value = fractionOf(r);
            prevValue = value;
            set.accept(r);
        }

        @Override protected void updateMessage() { setMessage(Component.empty()); }
        @Override protected void applyValue() {
            boolean shift = !quiet && PlatformUtil.shiftDown();
            if (shift) { // fine adjustment: movement since Shift went down counts a tenth
                if (!fine) { fine = true; fineAnchorMouse = value; fineAnchorValue = prevValue; }
                value = Mth.clamp(fineAnchorValue + (value - fineAnchorMouse) * 0.1, 0, 1);
            } else fine = false;
            double raw = min + value * (max - min);
            double r = shift ? Math.round(raw * 100.0) / 100.0 : Math.round(raw * 20.0) / 20.0; // 0.01 steps with Shift, else 0.05
            r = Mth.clamp(r, min, max);
            prevValue = value;
            if (r != lastReal) { lastReal = r; if (!quiet) UiSounds.play("scroll", 1f); } // tick for every step you drag
            set.accept(r);
        }
        @Override public void playDownSound(SoundManager manager) { /* the scroll ticks are the feedback */ }
    }
}
