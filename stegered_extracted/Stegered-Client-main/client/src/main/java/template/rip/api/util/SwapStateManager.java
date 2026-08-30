package template.rip.api.util;

import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import template.rip.Template;
import template.rip.api.event.events.HandleInputEvent;
import template.rip.api.event.events.PacketEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.event.orbit.EventPriority;
import template.rip.module.Module;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages silent hotbar slot swaps for modules, with scheduled restoration.
 *
 * <p>Ported from the ECHO codebase. A module claims a swap by calling
 * {@link #swapToIfNeeded(Module, int, boolean, int, boolean)}; the manager
 * switches the client slot (optionally through simulated hotbar key presses
 * so vanilla sends the {@code ServerboundSetCarriedItemC2SPacket} itself) and
 * restores the original slot either after a delay or when the owner calls
 * {@link #cancel(Module, boolean)}. Nested swaps restore to the root slot,
 * and the stack is cleared when the server respawns or repositions the player.
 *
 * <p>All event handlers are static; subscribe the class once on the bus:
 * {@code EVENTBUS.subscribe(SwapStateManager.class)}.
 */
public final class SwapStateManager {

    private enum RestorePhase {
        EARLY,
        POST
    }

    private static final Deque<SwapState> stack = new ArrayDeque<>();

    private SwapStateManager() {
    }

    // -- Public API --

    public static boolean swapTo(Module owner, int targetSlot, boolean inputSimulation) {
        return swapTo(owner, targetSlot, inputSimulation, 0, true, null);
    }

    public static boolean swapTo(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks) {
        return swapTo(owner, targetSlot, inputSimulation, restoreDelayTicks, true, null);
    }

    public static boolean swapTo(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks, boolean restoreOnEnd) {
        return swapTo(owner, targetSlot, inputSimulation, restoreDelayTicks, restoreOnEnd, null);
    }

    public static boolean swapTo(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks, Runnable onEnd) {
        return swapTo(owner, targetSlot, inputSimulation, restoreDelayTicks, true, onEnd);
    }

    public static boolean swapTo(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks, boolean restoreOnEnd, Runnable onEnd) {
        if (owner == null || Template.mc.player == null) {
            return false;
        }
        if (targetSlot < 0 || targetSlot > 8) {
            return false;
        }

        SwapState top = stack.peek();
        if (top != null && top.owner == owner) {
            top.targetSlot = targetSlot;
            top.inputSimulation = inputSimulation;
            top.restoreOnEnd = restoreOnEnd;
            top.restoreTick = restoreDelayTicks < 0 ? Long.MAX_VALUE : Template.mc.player.age + restoreDelayTicks;
            top.restorePhase = restoreDelayTicks <= 0 ? RestorePhase.POST : RestorePhase.EARLY;
            top.onEnd = onEnd;
            setInvSlot(targetSlot, inputSimulation);
            return true;
        }

        if (isActive(owner)) {
            return false;
        }

        int previousSlot = getSelectedSlot();
        if (previousSlot == targetSlot) {
            return false;
        }

        SwapState rootState = stack.peekLast();
        int rootSlot = rootState != null ? rootState.rootSlot : previousSlot;
        boolean rootInputSimulation = rootState != null ? rootState.rootInputSimulation : inputSimulation;

        long restoreTick = restoreDelayTicks < 0 ? Long.MAX_VALUE : Template.mc.player.age + restoreDelayTicks;
        RestorePhase restorePhase = restoreDelayTicks <= 0 ? RestorePhase.POST : RestorePhase.EARLY;

        stack.push(new SwapState(owner, rootSlot, rootInputSimulation, targetSlot, inputSimulation, restoreOnEnd, restoreTick, restorePhase, onEnd));
        setInvSlot(targetSlot, inputSimulation);
        return true;
    }

    public static boolean swapToIfNeeded(Module owner, int targetSlot, boolean inputSimulation) {
        return swapToIfNeeded(owner, targetSlot, inputSimulation, 0, true, null);
    }

    public static boolean swapToIfNeeded(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks) {
        return swapToIfNeeded(owner, targetSlot, inputSimulation, restoreDelayTicks, true, null);
    }

    public static boolean swapToIfNeeded(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks, boolean restoreOnEnd) {
        return swapToIfNeeded(owner, targetSlot, inputSimulation, restoreDelayTicks, restoreOnEnd, null);
    }

    public static boolean swapToIfNeeded(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks, Runnable onEnd) {
        return swapToIfNeeded(owner, targetSlot, inputSimulation, restoreDelayTicks, true, onEnd);
    }

    public static boolean swapToIfNeeded(Module owner, int targetSlot, boolean inputSimulation, int restoreDelayTicks, boolean restoreOnEnd, Runnable onEnd) {
        if (owner == null || Template.mc.player == null) {
            return false;
        }
        if (targetSlot < 0 || targetSlot > 8) {
            return false;
        }

        int selectedSlot = getSelectedSlot();
        if (selectedSlot == targetSlot && !isActive(owner)) {
            return true;
        }

        return swapTo(owner, targetSlot, inputSimulation, restoreDelayTicks, restoreOnEnd, onEnd);
    }

    public static boolean isActive(Module owner) {
        if (owner == null) {
            return false;
        }
        for (SwapState state : stack) {
            if (state.owner == owner) {
                return true;
            }
        }
        return false;
    }

    public static boolean isActive(Class<? extends Module> ownerClass) {
        if (ownerClass == null) {
            return false;
        }
        for (SwapState state : stack) {
            if (ownerClass.isInstance(state.owner)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOwnerActive(Module owner) {
        return isActive(owner);
    }

    public static boolean isOwnerActive(Class<? extends Module> ownerClass) {
        return isActive(ownerClass);
    }

    public static boolean hasActiveSwaps() {
        return !stack.isEmpty();
    }

    public static boolean updateSlot(Module owner, int targetSlot) {
        if (owner == null) {
            return false;
        }
        SwapState state = stack.peek();
        if (state == null || state.owner != owner) {
            return false;
        }
        state.targetSlot = targetSlot;
        return true;
    }

    public static int getActiveTargetSlot(Module owner) {
        if (owner == null) {
            return -1;
        }
        SwapState state = stack.peek();
        if (state == null || state.owner != owner) {
            return -1;
        }
        return state.targetSlot;
    }

    public static int getSelectedSlot() {
        if (Template.mc.player == null) {
            return -1;
        }
        SwapState state = stack.peek();
        return state != null ? state.targetSlot : Template.mc.player.getInventory().selectedSlot;
    }

    public static void cancel(Module owner) {
        cancel(owner, true);
    }

    public static void cancel(Module owner, boolean restore) {
        if (owner == null || stack.isEmpty()) {
            return;
        }
        if (!isActive(owner)) {
            return;
        }

        if (restore) {
            restoreRootAndClear(stack.peekLast());
            return;
        }

        while (!stack.isEmpty()) {
            SwapState state = stack.pop();
            endState(state, false, true);
            if (state.owner == owner) {
                break;
            }
        }
    }

    public static void clear() {
        stack.clear();
    }

    // -- Event hooks --

    @EventHandler(priority = EventPriority.LOWEST)
    public static void onHandleInputEarly(HandleInputEvent.Pre event) {
        processDueRestores(RestorePhase.EARLY);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public static void onHandleInputPost(HandleInputEvent.Post event) {
        processDueRestores(RestorePhase.POST);
    }

    @EventHandler
    public static void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerRespawnS2CPacket || event.packet instanceof PlayerPositionLookS2CPacket) {
            clear();
        }
    }

    // -- Internals --

    private static void processDueRestores(RestorePhase phase) {
        if (Template.mc.player == null) {
            clear();
            return;
        }

        while (!stack.isEmpty()) {
            SwapState state = stack.peek();
            if (!state.isDue(Template.mc.player.age, phase)) {
                break;
            }

            stack.pop();
            if (state.restoreOnEnd) {
                restoreRootAndClear(state);
                runEnd(state);
                return;
            }

            endState(state, false, false);
        }
    }

    private static void restoreRootAndClear(SwapState fallbackRoot) {
        SwapState root = stack.peekLast();
        if (root == null) {
            root = fallbackRoot;
        }

        if (root != null && Template.mc.player != null) {
            setInvSlot(root.rootSlot, root.rootInputSimulation);
        }

        while (!stack.isEmpty()) {
            SwapState state = stack.pop();
            runEnd(state);
        }
    }

    private static void endState(SwapState state, boolean restore, boolean forceRestore) {
        if (state == null) {
            return;
        }

        if (restore && Template.mc.player != null && (forceRestore || state.restoreOnEnd)) {
            setInvSlot(state.rootSlot, state.rootInputSimulation);
        }

        runEnd(state);
    }

    private static void runEnd(SwapState state) {
        if (state.onEnd == null) {
            return;
        }
        try {
            state.onEnd.run();
        } catch (Throwable error) {
            error.printStackTrace();
        }
    }

    /**
     * Switches the selected hotbar slot. When {@code inputSimulation} is set,
     * the slot's hotbar key is pressed through the vanilla key path so the
     * client sends {@code ServerboundSetCarriedItemC2SPacket} on its own
     * timing; otherwise the slot is set directly and synced immediately.
     */
    private static void setInvSlot(int slot, boolean inputSimulation) {
        if (slot < 0 || slot > 8) {
            return;
        }
        if (Template.mc.player.getInventory().selectedSlot == slot) {
            return;
        }

        if (inputSimulation) {
            if (Template.mc.options != null && Template.mc.options.hotbarKeys != null
                    && slot < Template.mc.options.hotbarKeys.length) {
                net.minecraft.client.option.KeyBinding hotbarKey = Template.mc.options.hotbarKeys[slot];
                if (hotbarKey != null) {
                    hotbarKey.setPressed(true);
                    hotbarKey.timesPressed++;
                    hotbarKey.setPressed(false);
                }
            }
        } else {
            Template.mc.player.getInventory().selectedSlot = slot;
            if (Template.mc.interactionManager != null) {
                Template.mc.interactionManager.syncSelectedSlot();
            }
        }
    }

    private static final class SwapState {
        private final Module owner;
        private final int rootSlot;
        private final boolean rootInputSimulation;
        private int targetSlot;
        private boolean inputSimulation;
        private boolean restoreOnEnd;
        private long restoreTick;
        private RestorePhase restorePhase;
        private Runnable onEnd;

        private SwapState(Module owner, int rootSlot, boolean rootInputSimulation, int targetSlot,
                          boolean inputSimulation, boolean restoreOnEnd, long restoreTick,
                          RestorePhase restorePhase, Runnable onEnd) {
            this.owner = owner;
            this.rootSlot = rootSlot;
            this.rootInputSimulation = rootInputSimulation;
            this.targetSlot = targetSlot;
            this.inputSimulation = inputSimulation;
            this.restoreOnEnd = restoreOnEnd;
            this.restoreTick = restoreTick;
            this.restorePhase = restorePhase;
            this.onEnd = onEnd;
        }

        private boolean isDue(long currentTick, RestorePhase phase) {
            return restoreTick != Long.MAX_VALUE
                    && currentTick >= restoreTick
                    && phase.ordinal() >= restorePhase.ordinal();
        }
    }
}
