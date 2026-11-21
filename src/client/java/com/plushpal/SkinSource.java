package com.plushpal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Where the plush's skin comes from when it isn't "my skin": Steve, Alex, another player's username, or a PNG file on
 * disk (offline). Usernames are looked up once through Mojang's public API in the background, saved to
 * config/plushpal/skins/NAME.png, and that saved copy is used when you are offline.
 *
 * The downloaded / file image is registered as one dynamic texture (plushpal:custom_skin) on the render thread.
 */
final class SkinSource {
    static final Identifier STEVE = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
    static final Identifier ALEX = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/slim/alex.png");
    static final Identifier CUSTOM = Identifier.fromNamespaceAndPath(PlushPalClient.ID, "custom_skin");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    // written by the loader thread, read on the render thread
    private static volatile BufferedImage pending;
    private static volatile boolean pendingSlim;
    private static volatile String pendingKey = "";
    private static volatile String status = "";

    private static String requestedKey = "";
    private static String loadedKey = "";
    private static boolean loadedSlim;
    private static volatile BufferedImage loadedImage;
    private static boolean registerFailed;
    private static long lastFileCheck;
    private static String fileStamp = "";

    private SkinSource() {}

    static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("plushpal"); }
    static Path offlineFile() { return dir().resolve("skin.png"); }
    private static Path cachePng(String name) { return dir().resolve("skins").resolve(name.toLowerCase(Locale.ROOT) + ".png"); }
    private static Path cacheMeta(String name) { return dir().resolve("skins").resolve(name.toLowerCase(Locale.ROOT) + ".txt"); }

    static boolean validUsername(String s) { return s != null && USERNAME.matcher(s).matches(); }

    /** Short text for the settings page: what is happening with the chosen skin. */
    static String status() { return status; }

    /** The pixels of the registered custom skin (for the settings preview, which can't read dynamic textures). */
    static BufferedImage image(Identifier id) { return CUSTOM.equals(id) ? loadedImage : null; }

    /** Forget what was loaded so the next frame looks the skin up again (the "Refresh" button, or after editing the file). */
    static void refresh() { requestedKey = ""; loadedKey = ""; fileStamp = ""; }

    /**
     * The skin for the configured source, or null if it isn't ready yet / can't be used (the caller then shows the
     * bundled plush skin). Call on the render thread. Not used for the "own" source.
     */
    static PlushRenderer.Skin resolve(PlushConfig cfg) {
        String src = cfg.skinSource == null ? "own" : cfg.skinSource;
        switch (src) {
            case "steve": return new PlushRenderer.Skin(STEVE, false, null);
            case "alex": return new PlushRenderer.Skin(ALEX, true, null);
            case "username": {
                String name = cfg.skinUsername == null ? "" : cfg.skinUsername.trim();
                if (!validUsername(name)) { status = name.isEmpty() ? "No username set" : "Not a valid Minecraft username"; return null; }
                return custom("u:" + name.toLowerCase(Locale.ROOT), name, false);
            }
            case "file": {
                long now = System.nanoTime();
                if (now - lastFileCheck > 1_000_000_000L) { // notice when you replace skin.png, once a second at most
                    lastFileCheck = now;
                    try {
                        fileStamp = Files.isRegularFile(offlineFile()) ? String.valueOf(Files.getLastModifiedTime(offlineFile()).toMillis()) : "missing";
                    } catch (IOException e) { fileStamp = "missing"; }
                }
                if ("missing".equals(fileStamp)) { status = "No skin.png in config/plushpal"; return null; }
                return custom("f:" + fileStamp, null, cfg.customSkinSlim);
            }
            default: return null;
        }
    }

    private static PlushRenderer.Skin custom(String key, String username, boolean fileSlim) {
        if (!key.equals(requestedKey)) {
            requestedKey = key;
            registerFailed = false;
            start(key, username, fileSlim);
        }
        BufferedImage p = pending;
        if (p != null && key.equals(pendingKey)) {
            pending = null;
            if (register(p)) { loadedKey = key; loadedSlim = pendingSlim; loadedImage = p; }
            else registerFailed = true;
        }
        if (key.equals(loadedKey) && !registerFailed) return new PlushRenderer.Skin(CUSTOM, loadedSlim, null);
        return null;
    }

    // ------------------------------------------------------------ loading (background thread)

    private static void start(String key, String username, boolean fileSlim) {
        status = "Loading...";
        Thread t = new Thread(() -> {
            try {
                if (username == null) {
                    publish(key, ImageIO.read(offlineFile().toFile()), fileSlim);
                    status = "Using config/plushpal/skin.png";
                } else loadUsername(key, username);
            } catch (Throwable e) {
                status = "Could not load: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                PlushPalClient.LOGGER.warn("PlushPal: skin load failed", e);
            }
        }, "PlushPal-skin");
        t.setDaemon(true);
        t.start();
    }

    private static void publish(String key, BufferedImage img, boolean slim) throws IOException {
        if (img == null) throw new IOException("not a PNG image");
        pendingSlim = slim;
        pendingKey = key;
        pending = img;
    }

    private static void loadUsername(String key, String name) throws IOException {
        boolean haveCache = false;
        try { // a saved copy shows instantly (and is all you get when offline)
            if (Files.isRegularFile(cachePng(name))) {
                boolean slim = Files.isRegularFile(cacheMeta(name)) && Files.readString(cacheMeta(name)).trim().equals("slim");
                publish(key, ImageIO.read(cachePng(name).toFile()), slim);
                haveCache = true;
                status = "Saved copy (checking online...)";
            }
        } catch (IOException ignored) { }
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
            byte[] idBytes = get(http, "https://api.mojang.com/users/profiles/minecraft/" + name);
            if (idBytes == null) { status = haveCache ? "Player not found - using saved copy" : "Player not found"; return; }
            String id = JsonParser.parseString(new String(idBytes, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();
            byte[] profBytes = get(http, "https://sessionserver.mojang.com/session/minecraft/profile/" + id);
            if (profBytes == null) throw new IOException("no profile");
            JsonArray props = JsonParser.parseString(new String(profBytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("properties");
            String skinUrl = null;
            boolean slim = false;
            for (JsonElement e : props) {
                JsonObject p = e.getAsJsonObject();
                if (!"textures".equals(p.get("name").getAsString())) continue;
                String decoded = new String(Base64.getDecoder().decode(p.get("value").getAsString()), StandardCharsets.UTF_8);
                JsonObject skin = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
                skinUrl = skin.get("url").getAsString();
                slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                        && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
            }
            if (skinUrl == null) { status = "That player has no custom skin"; return; }
            URI u = URI.create(skinUrl.replaceFirst("^http://", "https://")); // Mojang hands out http:// links; upgrade them
            if (!"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().endsWith(".minecraft.net")) throw new IOException("unexpected skin address");
            byte[] png = get(http, u.toString());
            if (png == null) throw new IOException("skin download failed");
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("not a PNG image");
            Files.createDirectories(cachePng(name).getParent());
            Files.write(cachePng(name), png);
            Files.writeString(cacheMeta(name), slim ? "slim" : "wide");
            publish(key, img, slim);
            status = "Loaded " + name + (slim ? " (slim)" : "");
        } catch (IOException | RuntimeException e) {
            PlushPalClient.LOGGER.warn("PlushPal: skin lookup failed for {}", name, e);
            if (haveCache) status = "Offline - using saved copy";
            else throw new IOException("could not fetch " + name + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** GET returning the body, or null for 204 / 404 (unknown player). */
    private static byte[] get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "PlushPal").GET().build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int code = res.statusCode();
        if (code == 204 || code == 404) return null;
        if (code != 200) throw new IOException("HTTP " + code);
        if (res.body().length > 2_000_000) throw new IOException("file too large");
        return res.body();
    }

    // ------------------------------------------------------------ registering the texture (render thread)

    private static boolean register(BufferedImage source) {
        try {
            BufferedImage img = upgradeLegacy(source);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bo);
            Object nativeImage = readNative(new ByteArrayInputStream(bo.toByteArray()));
            Object tex = newDynamicTexture(nativeImage);
            if (tex == null) throw new IllegalStateException("no usable DynamicTexture constructor");
            try { tex.getClass().getMethod("upload").invoke(tex); } catch (NoSuchMethodException ignored) { }
            Object manager = Minecraft.getInstance().getTextureManager();
            for (Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].isAssignableFrom(Identifier.class) && m.getParameterTypes()[1].isInstance(tex)) {
                    m.invoke(manager, CUSTOM, tex);
                    return true;
                }
            }
            throw new IllegalStateException("TextureManager.register not found");
        } catch (Throwable t) {
            status = "Could not show this skin (see log)";
            PlushPalClient.LOGGER.warn("PlushPal: could not register the custom skin texture", t);
            return false;
        }
    }

    private static Object readNative(InputStream in) throws Exception {
        for (String cls : new String[]{"com.mojang.blaze3d.platform.NativeImage", "net.minecraft.client.renderer.texture.NativeImage"}) {
            try {
                Class<?> c = Class.forName(cls);
                return c.getMethod("read", InputStream.class).invoke(null, in);
            } catch (ClassNotFoundException ignored) { }
        }
        throw new ClassNotFoundException("NativeImage");
    }

    private static Object newDynamicTexture(Object nativeImage) throws Exception {
        Class<?> dt = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");
        Supplier<String> label = () -> "plushpal custom skin";
        for (Constructor<?> c : dt.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 1 && p[0].isInstance(nativeImage)) return c.newInstance(nativeImage);
            if (p.length == 2 && p[1].isInstance(nativeImage)) {
                if (p[0] == Supplier.class) return c.newInstance(label, nativeImage);
                if (p[0] == String.class) return c.newInstance(label.get(), nativeImage);
            }
        }
        return null;
    }

    /** 64x32 skins (before 1.8) have no left arm / leg: copy them mirrored from the right ones, as the game does. */
    private static BufferedImage upgradeLegacy(BufferedImage in) {
        if (in.getWidth() != 64 || in.getHeight() != 32) return in;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.dispose();
        mirrorLimb(out, 0, 16, 16, 48);  // right leg -> left leg
        mirrorLimb(out, 40, 16, 32, 48); // right arm -> left arm
        return out;
    }

    /** Copies a 16x16 limb block, mirroring every face and swapping its left/right sides. */
    private static void mirrorLimb(BufferedImage img, int sx, int sy, int dx, int dy) {
        // top / bottom faces: columns 4..11 of the first 4 rows; side faces: 4 columns of 4 each, 12 rows
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int col = x / 4, within = x % 4;
                int srcX;
                if (y < 4) { // top (4..7) and bottom (8..11) faces: just mirror inside the face
                    if (col == 0 || col == 3) continue;
                    srcX = col * 4 + (3 - within);
                } else {     // right(0) front(1) left(2) back(3): left/right swap, every face mirrored
                    int srcCol = col == 0 ? 2 : col == 2 ? 0 : col;
                    srcX = srcCol * 4 + (3 - within);
                }
                img.setRGB(dx + x, dy + y, img.getRGB(sx + srcX, sy + y));
            }
        }
    }
}
