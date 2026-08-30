package template.rip.gui.clickgui.mode;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.MinecraftClient;
import template.rip.gui.ImguiLoader;
import template.rip.gui.clickgui.ConfigParent;
import template.rip.gui.clickgui.LegitMenu;
import template.rip.module.modules.client.AchillesSettingsModule;

/** Top navigation dock adapted from ECHO's GlassScreen dock. */
public final class EchoDock {

    private EchoDock() {
    }

    public static void render(AchillesSettingsModule settings) {
        if (!settings.echoDock.isEnabled()) {
            return;
        }

        boolean dark = settings.echoDarkMode.isEnabled();
        float opacity = settings.echoPanelOpacity.getFValue();
        float[] accent = settings.color.getColor().getFloatColor();
        float width = 390.0F;
        float x = MinecraftClient.getInstance().getWindow().getWidth() / 2.0F - width / 2.0F;

        ImGui.setNextWindowPos(x, 10.0F);
        ImGui.setNextWindowSize(width, 34.0F, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 6.0F);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 6.0F, 4.0F);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4.0F, 0.0F);
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

        int flags = ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar;
        ImGui.begin("##EchoDock", flags);

        if (ImguiLoader.mediumPoppins18 != null) {
            ImGui.pushFont(ImguiLoader.mediumPoppins18);
        }
        ImGui.pushStyleColor(ImGuiCol.Text,
                dark ? 0.96F : 0.13F,
                dark ? 0.97F : 0.13F,
                dark ? 1.0F : 0.19F,
                0.96F);
        ImGui.text("Stegered Client");
        ImGui.sameLine(0.0F, 18.0F);

        boolean configs = ConfigParent.getInstance().isOn;
        boolean legit = LegitMenu.getInstance().isOn;
        if (dockButton("Features", !configs && !legit, accent)) {
            ConfigParent.getInstance().isOn = false;
            LegitMenu.getInstance().isOn = false;
        }
        ImGui.sameLine(0.0F, 4.0F);
        if (dockButton("Configs", configs, accent)) {
            ConfigParent.getInstance().isOn = true;
            LegitMenu.getInstance().isOn = false;
        }
        ImGui.sameLine(0.0F, 4.0F);
        if (dockButton("Legit", legit, accent)) {
            ConfigParent.getInstance().isOn = false;
            LegitMenu.getInstance().isOn = true;
        }

        ImGui.popStyleColor();
        if (ImguiLoader.mediumPoppins18 != null) {
            ImGui.popFont();
        }
        ImGui.end();
        ImGui.popStyleColor(2);
        ImGui.popStyleVar(3);
    }

    private static boolean dockButton(String label, boolean selected, float[] accent) {
        if (selected) {
            ImGui.pushStyleColor(ImGuiCol.Button, accent[0], accent[1], accent[2], 0.70F);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent[0], accent[1], accent[2], 0.82F);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent[0], accent[1], accent[2], 0.92F);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 1.0F, 1.0F, 1.0F, 0.04F);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 1.0F, 1.0F, 1.0F, 0.10F);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 1.0F, 1.0F, 1.0F, 0.16F);
        }
        boolean clicked = ImGui.button(label, 70.0F, 24.0F);
        ImGui.popStyleColor(3);
        return clicked;
    }
}
