package template.rip.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import template.rip.Template;
import template.rip.api.event.events.HandleInputEvent;
import template.rip.api.event.events.KeyBindingEvent;
import template.rip.api.event.events.KeyPressEvent;
import template.rip.api.event.events.MousePressEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.event.orbit.EventPriority;
import template.rip.api.util.KeyUtils;
import template.rip.gui.clickgui.AchillesMenu;
import template.rip.module.modules.client.AchillesSettingsModule;
import template.rip.module.setting.settings.KeybindSetting;

import java.util.HashMap;
import java.util.Optional;
import java.util.TreeSet;

public class ModuleManager {

    private final HashMap<Class<? extends Module>, Module> class2module = new HashMap<>();
    private final HashMap<Module.Category, TreeSet<Module>> category2Module = new HashMap<>();
    private final TreeSet<Module> modules = new TreeSet<>(Module::compare);

    public boolean binding = false;
    public boolean typing = false;

    public ModuleManager() {
        for (Module.Category category : Module.Category.values()) {
            category2Module.put(category, new TreeSet<>(Module::compare));
        }
    }

    private boolean isModuleEnabled(@Nullable Module module) {
        return module != null && module.isEnabled();
    }

    public boolean isModuleEnabled(Class<? extends Module> clazz) {
        return isModuleEnabled(class2module.get(clazz));
    }

    public boolean isModuleDisabled(Class<? extends Module> clazz) {
        return !isModuleEnabled(clazz);
    }

    @Nullable
    public <T extends Module> T getModule(Class<T> moduleClass) {
        return moduleClass.cast(class2module.get(moduleClass));
    }

    @NotNull
    public <T extends Module> Optional<T> getOptModule(Class<T> moduleClass) {
        return Optional.ofNullable(moduleClass.cast(class2module.get(moduleClass)));
    }

    @SuppressWarnings("unchecked")
    public TreeSet<Module> getModules() {
        synchronized (modules) {
            return (TreeSet<Module>) modules.clone();
        }
    }

    @SuppressWarnings("unchecked")
    public TreeSet<Module> getModulesByCategory(@Nullable Module.Category category) {
        synchronized (category2Module) {
            return (TreeSet<Module>) category2Module
                    .getOrDefault(category, new TreeSet<>(Module::compare))
                    .clone();
        }
    }

    public void addModule(Module module) {
        synchronized (category2Module) {
            synchronized (class2module) {
                synchronized (modules) {
                    category2Module.get(module.getCategory()).add(module);
                    class2module.put(module.getClass(), module);
                    modules.add(module);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameInput(HandleInputEvent.Pre event) {
        if (!AchillesMenu.isClientEnabled()) {
            return;
        }

        if (typing && isModuleEnabled(AchillesSettingsModule.class)) {
            event.cancel();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameKey(KeyBindingEvent event) {
        if (!AchillesMenu.isClientEnabled()) {
            return;
        }

        if (typing && isModuleEnabled(AchillesSettingsModule.class)) {
            event.setPressed(false);
            event.key.wasPressed();
        }
    }

    @EventHandler
    private void onMousePress(MousePressEvent event) {
        handlePress(new KeyPressEvent(event.button, 0, event.action, 0));
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent event) {
        handlePress(event);
    }

    /**
     * Handles module and setting keybinds. This used to live in the login
     * screen, which made all keybinds depend on authentication UI state.
     */
    private void handlePress(KeyPressEvent event) {
        if (event.key == 0 || !AchillesMenu.isClientEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Screen current = client.currentScreen;
        if (current instanceof CreativeInventoryScreen) {
            return;
        }

        if (current != null && current.getFocused() instanceof TextFieldWidget) {
            return;
        }

        if (binding || typing && isModuleEnabled(AchillesSettingsModule.class)) {
            return;
        }

        if (KeyUtils.isKeyPressed(GLFW.GLFW_KEY_F3)) {
            return;
        }

        boolean keyPress = event.action == GLFW.GLFW_PRESS;
        TreeSet<Module> registeredModules = getModules();

        registeredModules.stream()
                .filter(module -> module.getKey() == event.key && (module.isHold() || keyPress))
                .forEach(Module::toggle);

        registeredModules.forEach(module -> {
            updateFocusedPosition(module, client);
            if (module.isEnabled()) {
                module.settings.stream()
                        .filter(setting -> setting instanceof KeybindSetting keybind && keybind.getCode() == event.key)
                        .forEach(setting -> ((KeybindSetting) setting).onPress(keyPress));
            }
        });
    }

    private void updateFocusedPosition(Module module, MinecraftClient client) {
        if (!module.isFocused) {
            return;
        }

        long window = client.getWindow().getHandle();
        float distance = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT) ? 5 : 1;

        if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
            module.updatedPos.x = distance;
        } else if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
            module.updatedPos.x = -distance;
        } else if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_UP)) {
            module.updatedPos.y = -distance;
        } else if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_DOWN)) {
            module.updatedPos.y = distance;
        }
    }
}
