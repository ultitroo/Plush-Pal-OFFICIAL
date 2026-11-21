package com.plushpal;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu's "Configure" button opens the same Bedrock-style settings screen as F12.
 * Only Mod Menu ever loads this class, so PlushPal still runs fine without Mod Menu installed.
 */
public final class PlushModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PlushConfigScreen::new; // receives Mod Menu's screen as the parent to return to
    }
}
