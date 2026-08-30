package template.rip.gui.clickgui.mode;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.MinecraftClient;
import template.rip.Template;
import template.rip.api.font.JColor;
import template.rip.api.object.ToolTipHolder;
import template.rip.api.util.KeyUtils;
import template.rip.api.util.RenderUtils;
import template.rip.gui.ImguiLoader;
import template.rip.gui.clickgui.AchillesMenu;
import template.rip.gui.clickgui.CategoryTab;
import template.rip.module.Module;
import template.rip.module.modules.client.AchillesSettingsModule;

import java.util.ArrayList;
import java.util.List;

/**
 * ImGui adaptation of ECHO's compact liquid-glass category panels.
 */
public final class EchoTab {

    public static final float PANEL_WIDTH = 200.0F;
    private static final float PANEL_HEIGHT = 400.0F;
    private static final float HEADER_HEIGHT = 30.0F;
    private static final float MODULE_HEIGHT = 26.0F;
    private static final float PANEL_RADIUS = 6.0F;
    private static final float MODULE_RADIUS = 4.0F;
    private static final float PADDING = 6.0F;
    private static final float PANEL_SPACING = 8.0F;
    private static final float SCREEN_MARGIN = 12.0F;

    private EchoTab() {
    }

    public static void render(CategoryTab tab) {
        AchillesSettingsModule settings = Template.moduleManager.getModule(AchillesSettingsModule.class);
        if (settings == null) {
            return;
        }

        String rawName = tab.getName();
        if (rawName == null || rawName.isEmpty()) {
            return;
        }

        boolean dark = settings.echoDarkMode.isEnabled();
        float opacity = settings.echoPanelOpacity.getFValue();
        float[] accent = settings.color.getColor().getFloatColor();

        int flags = ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoScrollbar;

        Layout layout = calculateLayout(tab, settings);
        float targetHeight = tab.isCollapsed ? HEADER_HEIGHT : layout.height();
        ImGui.setNextWindowPos(layout.x(), layout.y());
        ImGui.setNextWindowSize(PANEL_WIDTH, targetHeight, 0);
        ImGui.pushStyleColor(ImGuiCol.WindowBg,
                dark ? 0.102F : 0.973F,
                dark ? 0.102F : 0.973F,
                dark ? 0.102F : 0.973F,
                opacity);
        ImGui.pushStyleColor(ImGuiCol.Border,
                dark ? 1.0F : 0.70F,
                dark ? 1.0F : 0.70F,
                dark ? 1.0F : 0.74F,
                dark ? 0.24F : 0.55F);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowRounding, PANEL_RADIUS);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowBorderSize, 1.0F);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 0.0F, 0.0F);

        String windowId = "##EchoPanel/" + tab.category.name();
        ImGui.begin(windowId, flags);

        // Echo panels use a responsive grid every frame so all categories remain
        // visible at smaller resolutions and GUI scales.
        tab.firstFrame = false;

        tab.isWindowFocused = ImGui.isWindowFocused();
        tab.isWindowHovered = ImGui.isWindowHovered();

        renderHeader(tab, rawName, dark, accent);

        if (!tab.isCollapsed) {
            ImGui.setCursorPos(0.0F, HEADER_HEIGHT);
            ImGui.beginChild("##EchoModules/" + tab.category.name(), PANEL_WIDTH, targetHeight - HEADER_HEIGHT, false);
            tab.isWindowHovered = tab.isWindowHovered || ImGui.isWindowHovered();

            tab.scrollUntil = Math.max(0.0F, Math.min(tab.scrollUntil, ImGui.getScrollMaxY()));
            tab.scrollY += (tab.scrollUntil - tab.scrollY) * 0.22F;
            ImGui.setScrollY(tab.scrollY);

            List<Module> toToggle = new ArrayList<>();
            for (Module module : Template.moduleManager.getModulesByCategory(tab.category)) {
                if (!module.isNotSearched()) {
                    renderModule(module, dark, accent, toToggle);
                }
            }
            toToggle.forEach(Module::toggle);

            tab.maxY = Math.min(targetHeight, ImGui.getCursorPosY() + HEADER_HEIGHT);
            ImGui.endChild();
        }

        ImGui.end();
        ImGui.popStyleVar(3);
        ImGui.popStyleColor(2);
    }

    private static Layout calculateLayout(CategoryTab tab, AchillesSettingsModule settings) {
        List<CategoryTab> tabs = AchillesMenu.getInstance().tabs;
        int count = Math.max(1, tabs.size());
        int index = Math.max(0, tabs.indexOf(tab));

        float screenWidth = MinecraftClient.getInstance().getWindow().getWidth();
        float screenHeight = MinecraftClient.getInstance().getWindow().getHeight();
        int columns = Math.max(1, (int) ((screenWidth - SCREEN_MARGIN * 2.0F + PANEL_SPACING)
                / (PANEL_WIDTH + PANEL_SPACING)));
        columns = Math.min(columns, count);
        int rows = (int) Math.ceil((double) count / columns);
        int row = index / columns;
        int column = index % columns;
        int itemsInRow = Math.min(columns, count - row * columns);

        float rowWidth = itemsInRow * PANEL_WIDTH + Math.max(0, itemsInRow - 1) * PANEL_SPACING;
        float x = (screenWidth - rowWidth) / 2.0F + column * (PANEL_WIDTH + PANEL_SPACING);
        float top = settings.echoDock.isEnabled() ? 50.0F : 10.0F;
        float availableHeight = screenHeight - top - SCREEN_MARGIN;
        float height = (availableHeight - Math.max(0, rows - 1) * PANEL_SPACING) / rows;
        height = Math.max(140.0F, Math.min(PANEL_HEIGHT, height));
        float y = top + row * (height + PANEL_SPACING);

        return new Layout(x, y, height);
    }

    private record Layout(float x, float y, float height) {
    }

    private static void renderHeader(CategoryTab tab, String rawName, boolean dark, float[] accent) {
        ImVec2 screen = ImGui.getCursorScreenPos();
        int headerColor = ImGui.getColorU32(
                dark ? 0.135F : 0.99F,
                dark ? 0.135F : 0.99F,
                dark ? 0.135F : 0.99F,
                dark ? 0.72F : 0.72F
        );
        ImGui.getWindowDrawList().addRectFilled(
                screen.x,
                screen.y,
                screen.x + PANEL_WIDTH,
                screen.y + HEADER_HEIGHT,
                headerColor,
                PANEL_RADIUS
        );
        ImGui.getWindowDrawList().addRectFilled(
                screen.x,
                screen.y + HEADER_HEIGHT - 2.0F,
                screen.x + PANEL_WIDTH,
                screen.y + HEADER_HEIGHT,
                ImGui.getColorU32(accent[0], accent[1], accent[2], 0.70F)
        );

        ImGui.setCursorPos(0.0F, 0.0F);
        ImGui.invisibleButton("##EchoHeader/" + tab.category.name(), PANEL_WIDTH, HEADER_HEIGHT);
        boolean hovered = ImGui.isItemHovered();
        if (hovered && ImGui.isMouseClicked(1)) {
            tab.isCollapsed = !tab.isCollapsed;
            tab.lastOpen = System.currentTimeMillis();
        }

        String icon = rawName.substring(0, 1);
        String title = rawName.length() > 2 ? rawName.substring(2).trim() : rawName;
        int textColor = dark
                ? ImGui.getColorU32(0.96F, 0.97F, 1.0F, hovered ? 1.0F : 0.94F)
                : ImGui.getColorU32(0.13F, 0.13F, 0.19F, hovered ? 1.0F : 0.94F);

        ImGui.setCursorPos(PADDING, 7.0F);
        ImGui.pushStyleColor(ImGuiCol.Text, textColor);
        if (ImguiLoader.fontAwesome16 != null) {
            ImGui.pushFont(ImguiLoader.fontAwesome16);
            ImGui.text(icon);
            ImGui.popFont();
            ImGui.sameLine(0.0F, 6.0F);
        }
        if (ImguiLoader.mediumPoppins20 != null) {
            ImGui.pushFont(ImguiLoader.mediumPoppins20);
        }
        ImGui.text(title);
        if (ImguiLoader.mediumPoppins20 != null) {
            ImGui.popFont();
        }
        ImGui.popStyleColor();
    }

    private static void renderModule(Module module, boolean dark, float[] accent, List<Module> toToggle) {
        ImGui.pushID("Echo/" + module.getName());
        ImVec2 cursor = ImGui.getCursorPos();
        float rowWidth = PANEL_WIDTH - 8.0F;

        ImGui.setCursorPos(4.0F, cursor.y + 2.0F);
        boolean clicked = ImGui.invisibleButton("##EchoModule", rowWidth, MODULE_HEIGHT);
        boolean hovered = ImGui.isItemHovered();
        if (clicked) {
            toToggle.add(module);
        }
        if (hovered) {
            ToolTipHolder.setToolTip(module.getDescription().getContent());
            if (ImGui.isMouseClicked(1) && !module.settings.isEmpty()) {
                module.toggleShowOptions();
            }
        }

        ImVec2 row = ImGui.getItemRectMin();
        if (module.isEnabled()) {
            ImGui.getWindowDrawList().addRectFilled(
                    row.x,
                    row.y,
                    row.x + rowWidth,
                    row.y + MODULE_HEIGHT,
                    ImGui.getColorU32(accent[0], accent[1], accent[2], hovered ? 0.62F : 0.48F),
                    MODULE_RADIUS
            );
        } else if (hovered) {
            ImGui.getWindowDrawList().addRectFilled(
                    row.x,
                    row.y,
                    row.x + rowWidth,
                    row.y + MODULE_HEIGHT,
                    dark
                            ? ImGui.getColorU32(1.0F, 1.0F, 1.0F, 0.09F)
                            : ImGui.getColorU32(1.0F, 1.0F, 1.0F, 0.45F),
                    MODULE_RADIUS
            );
        }

        int textColor = module.isEnabled()
                ? ImGui.getColorU32(1.0F, 1.0F, 1.0F, 0.96F)
                : dark
                    ? ImGui.getColorU32(0.77F, 0.79F, 0.85F, hovered ? 1.0F : 0.84F)
                    : ImGui.getColorU32(0.20F, 0.20F, 0.28F, hovered ? 1.0F : 0.84F);

        ImGui.setCursorPos(10.0F, cursor.y + 8.0F);
        ImGui.pushStyleColor(ImGuiCol.Text, textColor);
        if (ImguiLoader.mediumPoppins18 != null) {
            ImGui.pushFont(ImguiLoader.mediumPoppins18);
        }
        RenderUtils.drawTexts(module.getFullName());
        if (ImguiLoader.mediumPoppins18 != null) {
            ImGui.popFont();
        }

        String bind = KeyUtils.getKeyName(module.getKey());
        float rightEdge = PANEL_WIDTH - 10.0F;
        if (!"None".equalsIgnoreCase(bind)) {
            float bindWidth = ImGui.calcTextSize(bind).x;
            ImGui.setCursorPos(rightEdge - bindWidth - (module.settings.isEmpty() ? 0.0F : 12.0F), cursor.y + 8.0F);
            ImGui.text(bind);
        }
        if (!module.settings.isEmpty()) {
            ImGui.setCursorPos(rightEdge - 8.0F, cursor.y + 8.0F);
            ImGui.text(module.showOptions() ? "\u25BC" : "\u25B6");
        }
        ImGui.popStyleColor();

        ImGui.setCursorPos(0.0F, cursor.y + MODULE_HEIGHT + 4.0F);
        if (module.showOptions()) {
            ImGui.indent(8.0F);
            if (ImguiLoader.poppins18 != null) {
                ImGui.pushFont(ImguiLoader.poppins18);
            }
            module.renderSettings();
            if (ImguiLoader.poppins18 != null) {
                ImGui.popFont();
            }
            ImGui.unindent(8.0F);
            ImGui.setCursorPosY(ImGui.getCursorPosY() + 4.0F);
            module.settingsOpenProgress = module.getSettingsHeight();
        } else {
            module.settingsOpenProgress = 0.0F;
        }

        ImGui.popID();
    }
}
