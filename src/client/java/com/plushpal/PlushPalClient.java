package com.plushpal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.MapCodec;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class PlushPalClient implements ClientModInitializer {
    public static final String ID = "plushpal";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    public static KeyMapping configKey, emoteKey, wheelKey;

    @Override
    public void onInitializeClient() {
        PlushConfig.load();
        if (Compat.PUNCHY) LOGGER.info("PlushPal: Punchy detected - compatibility is on");

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.plushpal.config", InputConstants.Type.KEYBOARD, InputConstants.KEY_F12, category));
        emoteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.plushpal.emote", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, category));
        wheelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.plushpal.wheel", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            EquipTracker.tick(mc);
            KeybindSetupScreen.tick(mc);
            while (configKey.consumeClick()) ScreenUtil.open(new PlushConfigScreen());
            while (emoteKey.consumeClick()) if (PlushConfig.get().modEnabled) EmoteTrigger.fireQuick();
            while (wheelKey.consumeClick()) if (PlushConfig.get().modEnabled) ScreenUtil.open(new EmoteWheelScreen());
        });

        registerSpecialRenderer(Identifier.fromNamespaceAndPath(ID, "plush"), PlushRenderer.Unbaked.MAP_CODEC);
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath(ID, "data"), (ResourceManagerReloadListener) PlushData::reload);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("plushpal").then(ClientCommands.literal("reload").executes(ctx -> {
                    PlushConfig.load();
                    ctx.getSource().sendFeedback(Component.literal("PlushPal config reloaded"));
                    return 1;
                }))));
    }

    /**
     * Registers our renderer type with vanilla's special-model registry WITHOUT naming that registry class,
     * because its name/package differs between Minecraft versions. Scans net.minecraft.client.renderer for a
     * static IdMapper field typed over SpecialModelRenderer$Unbaked and calls put() on it.
     */
    private static void registerSpecialRenderer(Identifier id, MapCodec<?> codec) {
        try {
            ClassLoader loader = PlushPalClient.class.getClassLoader();
            var mc = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow();
            for (Path root : mc.getRootPaths()) {
                Path dir = root.resolve("net/minecraft/client/renderer");
                if (!Files.isDirectory(dir)) continue;
                List<Path> classes;
                try (Stream<Path> walk = Files.walk(dir)) {
                    classes = walk.filter(p -> p.toString().endsWith(".class")
                            && !p.getFileName().toString().contains("$")).toList();
                }
                for (Path p : classes) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    String name = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                    try {
                        Class<?> c = Class.forName(name, false, loader);
                        for (Field f : c.getDeclaredFields()) {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            String generic = f.getGenericType().getTypeName();
                            if (!generic.contains("SpecialModelRenderer$Unbaked") && !generic.contains("SpecialModelRenderer.Unbaked")) continue;
                            if (!f.getType().getSimpleName().contains("IdMapper")) continue;
                            f.setAccessible(true);
                            Object mapper = f.get(null);
                            for (Method m : mapper.getClass().getMethods()) {
                                if (m.getName().equals("put") && m.getParameterCount() == 2) {
                                    m.invoke(mapper, id, codec);
                                    LOGGER.info("Registered {} via {}#{}", id, name, f.getName());
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) { /* some client classes can't be loaded standalone; skip */ }
                }
            }
            LOGGER.error("PlushPal: could not find the special model registry; the plush will not render.");
        } catch (Throwable t) {
            LOGGER.error("PlushPal: failed to register the special renderer", t);
        }
    }
}
