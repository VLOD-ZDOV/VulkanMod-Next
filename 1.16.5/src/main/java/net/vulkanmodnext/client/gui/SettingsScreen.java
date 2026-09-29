package net.vulkanmodnext.client.gui;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.vulkanmodnext.client.Presets;
import net.vulkanmodnext.client.Settings;
import net.vulkanmodnext.client.VulkanConfig;

import java.util.List;

/**
 * The mod's settings, all of them, including the ones that do not work yet.
 *
 * <h2>Why show settings that do nothing</h2>
 *
 * Most of these steer a part of the mod that has not been ported to this
 * version. Two ways to handle that: hide them, or show them and say so.
 *
 * <p>Hiding is worse, and not by a little. A player who knows the 1.12.2 mod
 * opens this and reads absence as "gone", not as "not yet" — and there is
 * nowhere for them to find out which it is. Showing them with a switch that
 * silently does nothing is worse still: that is a lie the player acts on, and
 * they will report the effect as broken rather than as missing.
 *
 * <p>So every setting is here, every one remembers what it is set to, and the
 * ones that steer nothing today are greyed and labelled. The header carries the
 * count, because "8 of 106" is the honest one-line state of this port and a
 * player deserves to read it before they start changing things.
 */
public class SettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 21;
    private static final int ROWS = 9;

    private final Screen parent;
    /** Null while the presets page is showing. */
    private Settings.Category category = Settings.Category.GENERAL;
    private List<Settings.Setting> rows;
    private int scroll;
    private String lastPreset;

    public SettingsScreen(Screen parent) {
        super(new StringTextComponent("VulkanMod 1.16.5"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows = category == null ? java.util.Collections.emptyList() : Settings.of(category);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - ROWS)));

        int tabWidth = 74;
        int tabsLeft = this.width / 2 - (tabWidth * 5 + 16) / 2;
        Button presetsTab = new Button(tabsLeft, 28, tabWidth, 20,
                new StringTextComponent(category == null ? TextFormatting.WHITE + "Presets"
                        : TextFormatting.GRAY + "Presets"),
                pressed -> {
                    category = null;
                    scroll = 0;
                    init();
                });
        presetsTab.active = category != null;
        addButton(presetsTab);
        tabsLeft += tabWidth + 4;
        Settings.Category[] categories = Settings.Category.values();
        for (int i = 0; i < categories.length; i++) {
            Settings.Category tab = categories[i];
            String name = tab.name().charAt(0) + tab.name().substring(1).toLowerCase();
            Button button = new Button(tabsLeft + i * (tabWidth + 4), 28, tabWidth, 20,
                    new StringTextComponent(tab == category ? TextFormatting.WHITE + name
                            : TextFormatting.GRAY + name),
                    pressed -> {
                        category = tab;
                        scroll = 0;
                        init();
                    });
            button.active = tab != category;
            addButton(button);
        }

        int top = 58;
        int rowWidth = 300;
        int left = this.width / 2 - rowWidth / 2;
        if (category == null) {
            // Two columns of four: every preset on one page, no scrolling for
            // the thing a new player looks for first.
            int half = (rowWidth - 4) / 2;
            for (int i = 0; i < Presets.ALL.length; i++) {
                Presets.Preset preset = Presets.ALL[i];
                int x = left + (i % 2) * (half + 4);
                int y = top + (i / 2) * ROW_HEIGHT;
                addButton(new Button(x, y, half, 20, new StringTextComponent(preset.name),
                        pressed -> {
                            preset.apply();
                            lastPreset = preset.name;
                            init();
                        }));
            }
        }
        for (int i = 0; i < ROWS && i + scroll < rows.size(); i++) {
            Settings.Setting setting = rows.get(i + scroll);
            if (!setting.bool && setting.live) {
                // A slider for a number: the step buttons took a hundred
                // clicks to cross a 0-100 effect, which is a setting nobody
                // tries both ends of.
                addButton(new SettingSlider(left, top + i * ROW_HEIGHT, rowWidth, setting));
                continue;
            }
            addButton(new Button(left, top + i * ROW_HEIGHT, rowWidth, 20, label(setting),
                    pressed -> {
                        step(setting, !hasShiftDown());
                        init();
                    }));
        }

        addButton(new Button(this.width / 2 - 154, this.height - 28, 100, 20,
                new StringTextComponent("Reset all"), pressed -> {
                    VulkanConfig.reset();
                    init();
                }));
        addButton(new Button(this.width / 2 - 50, this.height - 28, 100, 20,
                new StringTextComponent("Done"), pressed -> onClose()));
        addButton(new Button(this.width / 2 + 54, this.height - 28, 100, 20,
                new StringTextComponent("Save"), pressed -> VulkanConfig.save()));
    }

    /**
     * Moves a setting one step, and wraps at the ends.
     *
     * Ranges here run from two values to two hundred and fifty-six, so a fixed
     * step would be either unusable at one end or maddening at the other. A
     * hundredth of the range, at least one, crosses any of them in about the
     * same number of clicks.
     */
    private void step(Settings.Setting setting, boolean forward) {
        int value = VulkanConfig.get(setting.key);
        if (setting.bool) {
            VulkanConfig.set(setting, value == 0 ? 1 : 0);
            return;
        }
        int span = setting.max - setting.min;
        int stride = Math.max(1, span / 100);
        int next = value + (forward ? stride : -stride);
        if (next > setting.max) {
            next = setting.min;
        } else if (next < setting.min) {
            next = setting.max;
        }
        VulkanConfig.set(setting, next);
    }

    /** A number setting dragged rather than clicked, told to the renderer as it moves. */
    private final class SettingSlider extends net.minecraft.client.gui.widget.AbstractSlider {

        private final Settings.Setting setting;

        SettingSlider(int x, int y, int width, Settings.Setting setting) {
            super(x, y, width, 20, StringTextComponent.EMPTY, fraction(setting));
            this.setting = setting;
            updateMessage();
        }

        private int current() {
            return setting.min + (int) Math.round(value * (setting.max - setting.min));
        }

        @Override
        protected void updateMessage() {
            setMessage(label(setting, current()));
        }

        @Override
        protected void applyValue() {
            if (current() != VulkanConfig.get(setting.key)) {
                VulkanConfig.set(setting, current());
            }
        }
    }

    private static double fraction(Settings.Setting setting) {
        int span = setting.max - setting.min;
        return span <= 0 ? 0.0 : (VulkanConfig.get(setting.key) - setting.min) / (double) span;
    }

    private ITextComponent label(Settings.Setting setting) {
        return label(setting, VulkanConfig.get(setting.key));
    }

    /**
     * Settings that cannot work on this version at all, as opposed to not yet.
     *
     * Ray tracing needs Vulkan 1.2 and the acceleration structure extension,
     * and the LWJGL this version of the game ships predates both. "Not yet
     * ported" would promise a player something that is not coming; the label
     * says which of the two it is.
     */
    private static final java.util.Set<String> IMPOSSIBLE = new java.util.HashSet<>(
            java.util.Arrays.asList("rayTracing", "rayTracingRadius", "tracedLights",
                    "tracedBlockLight", "temporalAccumulation"));

    /**
     * Settings whose cost does not exist on this version: 1.16.5 seeds its
     * visibility search with no flood fill to cache, and batches block entity
     * models into one buffer, so there is nothing for these to save.
     */
    private static final java.util.Set<String> NOT_NEEDED = new java.util.HashSet<>(
            java.util.Arrays.asList("visibilitySeedCache", "cacheBlockEntityModels"));

    private ITextComponent label(Settings.Setting setting, int value) {
        String shown = setting.bool ? (value != 0 ? "on" : "off") : Integer.toString(value);
        String colour = setting.live ? TextFormatting.WHITE.toString()
                : TextFormatting.DARK_GRAY.toString();
        String suffix = setting.live ? ""
                : IMPOSSIBLE.contains(setting.key)
                        ? TextFormatting.DARK_GRAY + "  (needs ray tracing, not on 1.16.5)"
                        : NOT_NEEDED.contains(setting.key)
                        ? TextFormatting.DARK_GRAY + "  (not needed on 1.16.5)"
                        : TextFormatting.DARK_GRAY + "  (not yet ported)";
        return new StringTextComponent(colour + setting.title() + ": "
                + (setting.live ? TextFormatting.YELLOW : TextFormatting.DARK_GRAY) + shown
                + suffix);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int most = Math.max(0, rows.size() - ROWS);
        int next = Math.max(0, Math.min(most, scroll - (int) Math.signum(amount)));
        if (next != scroll) {
            scroll = next;
            init();
        }
        return true;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float partialTicks) {
        renderBackground(matrices);
        drawCenteredString(matrices, this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        // The one line that says where this port actually is. It is at the top
        // on purpose: a player should read it before they start turning things
        // on and wondering why nothing happens.
        drawCenteredString(matrices, this.font,
                new StringTextComponent(TextFormatting.GRAY + "" + Settings.liveCount() + " of "
                        + Settings.all().size() + " settings do something in this port so far"),
                this.width / 2, 52 - 32, 0xA0A0A0);
        super.render(matrices, mouseX, mouseY, partialTicks);

        if (rows.size() > ROWS) {
            drawCenteredString(matrices, this.font, new StringTextComponent(
                            TextFormatting.DARK_GRAY + "" + (scroll + 1) + "–"
                                    + Math.min(rows.size(), scroll + ROWS) + " of " + rows.size()
                                    + ", scroll to see the rest"),
                    this.width / 2, this.height - 42, 0x808080);
        }
        if (category == null) {
            if (lastPreset != null) {
                drawCenteredString(matrices, this.font, new StringTextComponent(
                                TextFormatting.GREEN + lastPreset + " applied"),
                        this.width / 2, 58 + 4 * ROW_HEIGHT + 6, 0xFFFFFF);
            }
            Presets.Preset over = hoveredPreset(mouseX, mouseY);
            if (over != null) {
                List<ITextComponent> lines = new java.util.ArrayList<>();
                for (String line : wrap(over.description, 60)) {
                    lines.add(new StringTextComponent(line));
                }
                renderComponentTooltip(matrices, lines, mouseX, mouseY);
            }
            return;
        }
        // The description of whatever the pointer is over, wrapped by hand
        // because there is more of it than fits.
        Settings.Setting hovered = hovered(mouseY);
        if (hovered != null) {
            List<ITextComponent> lines = new java.util.ArrayList<>();
            for (String line : wrap(hovered.description, 60)) {
                lines.add(new StringTextComponent(line));
            }
            if (!hovered.live) {
                lines.add(new StringTextComponent(TextFormatting.DARK_GRAY
                        + (IMPOSSIBLE.contains(hovered.key)
                                ? "Needs ray tracing, which the Vulkan bindings of this game "
                                        + "version cannot reach."
                                : NOT_NEEDED.contains(hovered.key)
                                ? "The cost this saves on 1.12.2 does not exist on this version."
                                : "Remembered, but nothing reads it on this version yet.")));
            }
            renderComponentTooltip(matrices, lines, mouseX, mouseY);
        }
    }

    private Presets.Preset hoveredPreset(int mouseX, int mouseY) {
        int left = this.width / 2 - 150;
        int half = (300 - 4) / 2;
        int row = (mouseY - 58) / ROW_HEIGHT;
        if (mouseY < 58 || (mouseY - 58) % ROW_HEIGHT > 20 || mouseX < left
                || mouseX >= left + 300) {
            return null;
        }
        int column = mouseX < left + half ? 0 : mouseX >= left + half + 4 ? 1 : -1;
        int index = row * 2 + column;
        return column >= 0 && index < Presets.ALL.length ? Presets.ALL[index] : null;
    }

    private Settings.Setting hovered(int mouseY) {
        int index = (mouseY - 58) / ROW_HEIGHT;
        if (index < 0 || index >= ROWS || (mouseY - 58) % ROW_HEIGHT > 20) {
            return null;
        }
        int at = index + scroll;
        return at >= 0 && at < rows.size() ? rows.get(at) : null;
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    @Override
    public void onClose() {
        // Saved on the way out rather than on every click: a player dragging a
        // slider should not be writing a file two hundred times.
        VulkanConfig.save();
        this.minecraft.setScreen(parent);
    }
}
