package net.vulkanmodnext.client.gui;

import net.minecraft.client.resources.I18n;

/**
 * Menu text by key, with the English it was written in as the fallback.
 *
 * The English stays in the code rather than only in en_us.json so that a
 * missing or stale translation shows English instead of a raw key: a player
 * reading "vulkanmodnext.setting.fogDistance.desc" learns nothing.
 */
public final class Lang {

    private Lang() {
    }

    public static String tr(String key, String english, Object... args) {
        if (I18n.exists(key)) {
            return I18n.get(key, args);
        }
        return args.length == 0 ? english : String.format(english, args);
    }

    public static String settingName(String key, String english) {
        return tr("vulkanmodnext.setting." + key + ".name", english);
    }

    public static String settingDescription(String key, String english) {
        return tr("vulkanmodnext.setting." + key + ".desc", english);
    }

    public static String presetName(String name) {
        return tr("vulkanmodnext.preset." + id(name) + ".name", name);
    }

    public static String presetDescription(String name, String english) {
        return tr("vulkanmodnext.preset." + id(name) + ".desc", english);
    }

    /** "Golden Hour" → "golden_hour". */
    static String id(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }
}
