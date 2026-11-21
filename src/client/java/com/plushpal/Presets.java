package com.plushpal;

import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Named setting profiles (config/plushpal_presets/NAME.json) and share strings. Loading a preset or importing a
 * string only changes the live settings; nothing is written to plushpal.json until you press SAVE.
 */
final class Presets {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _\\-]{1,32}");
    private static final String PREFIX = "PLUSH1:";
    private static final int MAX_STRING = 200_000;

    private Presets() {}

    private static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("plushpal_presets"); }

    static boolean validName(String name) { return name != null && NAME.matcher(name.trim()).matches(); }

    static List<String> list() {
        List<String> names = new ArrayList<>();
        try {
            if (Files.isDirectory(dir())) try (Stream<Path> s = Files.list(dir())) {
                s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".json"))
                        .forEach(f -> names.add(f.substring(0, f.length() - 5)));
            }
        } catch (IOException e) { PlushPalClient.LOGGER.warn("PlushPal: could not list presets", e); }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static boolean save(String name) {
        if (!validName(name)) return false;
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve(name.trim() + ".json"), PlushConfig.toJson());
            return true;
        } catch (IOException e) { PlushPalClient.LOGGER.warn("PlushPal: could not save preset", e); return false; }
    }

    static boolean load(String name) {
        if (!validName(name)) return false;
        try {
            return PlushConfig.applyJson(Files.readString(dir().resolve(name.trim() + ".json")));
        } catch (IOException e) { PlushPalClient.LOGGER.warn("PlushPal: could not load preset", e); return false; }
    }

    static boolean delete(String name) {
        if (!validName(name)) return false;
        try { return Files.deleteIfExists(dir().resolve(name.trim() + ".json")); }
        catch (IOException e) { PlushPalClient.LOGGER.warn("PlushPal: could not delete preset", e); return false; }
    }

    /** The current settings as one line of text: PLUSH1: + base64(gzip(json)). */
    static String export() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(PlushConfig.toJson().getBytes(StandardCharsets.UTF_8)); }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bo.toByteArray());
        } catch (IOException e) { return ""; }
    }

    /** Applies a share string to the live settings. False if the text isn't one of ours or is damaged. */
    static boolean importString(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (!s.startsWith(PREFIX) || s.length() > MAX_STRING) return false;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(s.substring(PREFIX.length()));
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                byte[] json = gz.readNBytes(MAX_STRING);
                return PlushConfig.applyJson(new String(json, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException | IOException e) { return false; }
    }
}
