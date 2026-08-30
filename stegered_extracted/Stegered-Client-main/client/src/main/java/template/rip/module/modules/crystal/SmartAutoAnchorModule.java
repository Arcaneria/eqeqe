package template.rip.module.modules.crystal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import template.rip.Template;
import template.rip.api.event.events.InteractBlockEvent;
import template.rip.api.event.events.ItemUseEvent;
import template.rip.api.event.events.TickEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.event.orbit.EventPriority;
import template.rip.api.notification.Notification;
import template.rip.api.object.Description;
import template.rip.api.util.BlockUtils;
import template.rip.api.util.InvUtils;
import template.rip.api.util.KeyUtils;
import template.rip.api.util.PlayerUtils;
import template.rip.module.Module;
import template.rip.module.setting.settings.BooleanSetting;
import template.rip.module.setting.settings.DividerSetting;
import template.rip.module.setting.settings.MinMaxNumberSetting;
import template.rip.module.setting.settings.ModeSetting;
import template.rip.module.setting.settings.NumberSetting;

/**
 * Automates an anchor cycle while preserving the player's real point of view.
 *
 * <p>The player places the initial anchor and keeps right click held. After a
 * short configurable delay (two ticks by default), the module charges the
 * anchor and only then starts watching the player's real POV. A sufficiently
 * large manual look movement selects safe mode and places glowstone where the
 * player aims; otherwise the normal path detonates with a totem when available
 * or a configurable fallback hotbar slot. Strict timing prevents multiple
 * automatic slot changes or interactions from being emitted in one tick.</p>
 */
public final class SmartAutoAnchorModule extends Module {

    private static final int MAX_STAGE_TRANSITIONS_PER_TICK = 16;

    private enum Stage {
        IDLE,
        WAIT_ANCHOR,
        SWITCH_GLOWSTONE,
        CHARGE_ANCHOR,
        CONFIRM_CHARGE,
        WATCH_POV,
        PLACE_SAFE_BLOCK,
        CONFIRM_SAFE_BLOCK,
        SWITCH_DETONATOR,
        WAIT_ANCHOR_AIM,
        FINISH
    }

    public enum ServerTiming {
        Strict,
        Fast
    }

    public final DividerSetting smartDivider = new DividerSetting(this, false, "Smart Detection");
    public final NumberSetting povThreshold = new NumberSetting(
            this,
            Description.of("Yaw/pitch movement in degrees that selects the safe-anchor path."),
            35,
            5,
            180,
            1,
            "POV Threshold"
    );
    public final NumberSetting decisionWindow = new NumberSetting(
            this,
            Description.of("Ticks after charging in which manual POV movement can select the safe path. Set to 0 for immediate normal detonation."),
            6,
            0,
            20,
            1,
            "Decision Window"
    );

    public final DividerSetting timingDivider = new DividerSetting(this, false, "Timing");
    public final ModeSetting<ServerTiming> serverTiming = new ModeSetting<>(
            this,
            Description.of("Strict prevents multiple slot switches or anchor interactions in one tick. Fast keeps maximum zero-delay speed for environments without action-rate checks."),
            ServerTiming.Strict,
            "Server Timing"
    );
    public final NumberSetting strictActionGap = new NumberSetting(
            this,
            Description.of("Minimum ticks between anchor interactions while Server Timing is Strict."),
            2,
            1,
            10,
            1,
            "Strict Action Gap"
    );
    public final NumberSetting chargeDelay = new NumberSetting(
            this,
            Description.of("Exact delay after placement before charging the anchor, in ticks."),
            2,
            0,
            10,
            1,
            "Charge Delay"
    );
    public final MinMaxNumberSetting safePlaceDelays = new MinMaxNumberSetting(
            this,
            Description.of("Delay before placing the neighboring glowstone block, in ticks."),
            1,
            2,
            0,
            10,
            1,
            "Safe Place Delays"
    );
    public final MinMaxNumberSetting detonationDelays = new MinMaxNumberSetting(
            this,
            Description.of("Delay after looking back at the anchor before detonation, in ticks."),
            1,
            2,
            0,
            10,
            1,
            "Detonation Delays"
    );
    public final MinMaxNumberSetting slotDelays = new MinMaxNumberSetting(
            this,
            Description.of("Delay before automatic hotbar switches, in ticks."),
            1,
            2,
            0,
            10,
            1,
            "Slot Delays"
    );

    public final DividerSetting behaviorDivider = new DividerSetting(this, false, "Behavior");
    public final BooleanSetting preferTotem = new BooleanSetting(
            this,
            Description.of("Uses a hotbar totem for detonation when one is available. A totem is never required."),
            true,
            "Prefer Totem"
    );
    public final NumberSetting fallbackDetonationSlot = new NumberSetting(
            this,
            Description.of("Hotbar slot used for detonation when no totem is available or Prefer Totem is disabled."),
            5,
            1,
            9,
            1,
            "Fallback Detonation Slot"
    );
    public final BooleanSetting restoreSlotOnCancel = new BooleanSetting(
            this,
            Description.of("Returns to the original anchor slot only when a cycle is cancelled. Successful detonations leave the chosen detonation slot selected."),
            true,
            "Restore Slot On Cancel"
    );
    public final NumberSetting confirmationTimeout = new NumberSetting(
            this,
            Description.of("Maximum ticks to wait for the server to confirm a placement or charge."),
            40,
            10,
            100,
            1,
            "Confirmation Timeout"
    ).setAdvanced();

    private Stage stage = Stage.IDLE;

    private boolean pendingInitialPlacement;
    private BlockPos pendingPrimaryCandidate;
    private BlockPos pendingSecondaryCandidate;
    private BlockPos anchorCandidatePrimary;
    private BlockPos anchorCandidateSecondary;
    private BlockPos anchorPos;
    private BlockPos safeBlockPos;

    private int pendingOriginalSlot = -1;
    private int originalAnchorSlot = -1;
    private int stageTicks;
    private int watchTicks;
    private int slotCooldown;
    private int actionCooldown;
    private int serverActionCooldown;

    private float watchYaw;
    private float watchPitch;
    private boolean safePath;
    private boolean suppressUseUntilRelease;
    private boolean interactionPerformedThisTick;
    private boolean slotChangedThisTick;

    public SmartAutoAnchorModule(Category category, Description description, String name) {
        super(category, description, name);
        smartDivider.addSetting(povThreshold, decisionWindow);
        strictActionGap.addConditionMode(serverTiming, ServerTiming.Strict);
        timingDivider.addSetting(serverTiming, strictActionGap, chargeDelay, safePlaceDelays, detonationDelays, slotDelays);
        behaviorDivider.addSetting(preferTotem, fallbackDetonationSlot, restoreSlotOnCancel, confirmationTimeout);
    }

    @Override
    public void onEnable() {
        resetCycle(false, false);
        disableRegularAutoAnchor();
    }

    @Override
    public void onDisable() {
        resetCycle(true, false);
    }

    @Override
    public String getSuffix() {
        return switch (stage) {
            case IDLE -> "";
            case WATCH_POV -> " Watching";
            case PLACE_SAFE_BLOCK, CONFIRM_SAFE_BLOCK -> " Safe";
            case SWITCH_DETONATOR, WAIT_ANCHOR_AIM, FINISH -> safePath ? " Safe" : " Normal";
            default -> " Active";
        };
    }

    /**
     * Captures the intended anchor position before vanilla consumes the item.
     */
    @EventHandler
    private void onInteractBlockPre(InteractBlockEvent.Pre event) {
        pendingInitialPlacement = false;

        if (stage != Stage.IDLE
                || suppressUseUntilRelease
                || !event.check
                || event.hand != Hand.MAIN_HAND
                || !isUseHeld()
                || !nullCheck()
                || mc.currentScreen != null
                || !mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR)
                || event.blockHitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockHitResult hit = event.blockHitResult;
        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = mc.world.getBlockState(clickedPos);

        // Clicking an existing anchor is a detonation/air-place action, not the
        // start of a new smart cycle.
        if (clickedState.isOf(Blocks.RESPAWN_ANCHOR)) {
            return;
        }

        BlockPos adjacentPos = clickedPos.offset(hit.getSide());
        pendingPrimaryCandidate = clickedState.isReplaceable() ? clickedPos : adjacentPos;
        pendingSecondaryCandidate = pendingPrimaryCandidate.equals(clickedPos) ? adjacentPos : clickedPos;
        pendingOriginalSlot = mc.player.getInventory().selectedSlot;
        pendingInitialPlacement = true;
    }

    /**
     * Starts only after vanilla reports that the player's initial interaction
     * was accepted. No placement packet or rotation is synthesized here.
     */
    @EventHandler
    private void onInteractBlockPost(InteractBlockEvent.Post event) {
        if (!pendingInitialPlacement || stage != Stage.IDLE || event.hand != Hand.MAIN_HAND) {
            return;
        }

        pendingInitialPlacement = false;
        if (!event.check || event.actionResult == null || !event.actionResult.isAccepted() || !isUseHeld()) {
            clearPendingPlacement();
            return;
        }

        beginCycle();
    }

    /**
     * Vanilla item use is suppressed after the initial placement so holding RMB
     * cannot double-charge, place twice, or detonate before the state machine is
     * ready. Direct interactions below still use Minecraft's normal interaction
     * manager and the player's current crosshair target.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onItemUse(ItemUseEvent.Pre event) {
        if (stage != Stage.IDLE || suppressUseUntilRelease) {
            event.cancel();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        disableRegularAutoAnchor();
        interactionPerformedThisTick = false;
        slotChangedThisTick = false;
        if (serverActionCooldown > 0) {
            serverActionCooldown--;
        }

        if (stage == Stage.IDLE) {
            if (!isUseHeld()) {
                suppressUseUntilRelease = false;
                clearPendingPlacement();
            }
            return;
        }

        if (!nullCheck() || mc.currentScreen != null || !isUseHeld()) {
            resetCycle(true, false);
            return;
        }

        // Process consecutive zero-delay transitions in one client tick. A
        // stage that must wait for input, a timer, or server confirmation leaves
        // the state unchanged and stops the loop. The hard cap prevents a bad
        // transition from ever locking the render/game thread.
        for (int transitions = 0; transitions < MAX_STAGE_TRANSITIONS_PER_TICK && stage != Stage.IDLE; transitions++) {
            Stage previousStage = stage;
            tickCurrentStage();
            if (stage == previousStage
                    || (serverTiming.is(ServerTiming.Strict)
                        && (interactionPerformedThisTick || slotChangedThisTick))) {
                break;
            }
        }
    }

    private void tickCurrentStage() {
        switch (stage) {
            case WAIT_ANCHOR -> tickWaitAnchor();
            case SWITCH_GLOWSTONE -> tickSwitchGlowstone();
            case CHARGE_ANCHOR -> tickChargeAnchor();
            case CONFIRM_CHARGE -> tickConfirmCharge();
            case WATCH_POV -> tickWatchPov();
            case PLACE_SAFE_BLOCK -> tickPlaceSafeBlock();
            case CONFIRM_SAFE_BLOCK -> tickConfirmSafeBlock();
            case SWITCH_DETONATOR -> tickSwitchDetonator();
            case WAIT_ANCHOR_AIM -> tickWaitAnchorAim();
            case FINISH -> tickFinish();
            case IDLE -> {
                // Handled by onTick.
            }
        }
    }

    private void beginCycle() {
        if (InvUtils.getItemSlot(Items.GLOWSTONE) == -1) {
            notifyFailure("A glowstone stack is required in the hotbar.");
            suppressUseUntilRelease = isUseHeld();
            clearPendingPlacement();
            return;
        }
        disableRegularAutoAnchor();
        anchorCandidatePrimary = pendingPrimaryCandidate;
        anchorCandidateSecondary = pendingSecondaryCandidate;
        originalAnchorSlot = pendingOriginalSlot;
        anchorPos = null;
        safeBlockPos = null;
        safePath = false;
        watchTicks = 0;
        slotCooldown = slotDelays.getRandomInt();
        actionCooldown = chargeDelay.getIValue();
        mc.options.useKey.timesPressed = 0;
        clearPendingPlacement();
        transition(Stage.WAIT_ANCHOR);
    }

    private void tickWaitAnchor() {
        anchorPos = findPlacedAnchor();
        if (anchorPos != null) {
            int glowstoneSlot = InvUtils.getItemSlot(Items.GLOWSTONE);
            if (glowstoneSlot == -1) {
                abortCycle("Glowstone is no longer available in the hotbar.");
                return;
            }

            // The initial glowstone switch is immediate so the default charge
            // delay is measured from anchor confirmation, not from slot timing.
            slotCooldown = 0;
            switchToSlot(glowstoneSlot);
            actionCooldown = chargeDelay.getIValue();
            transition(Stage.CHARGE_ANCHOR);
            return;
        }

        if (++stageTicks > confirmationTimeout.getIValue()) {
            cancelCycle();
        }
    }

    private void tickSwitchGlowstone() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }

        int glowstoneSlot = InvUtils.getItemSlot(Items.GLOWSTONE);
        if (glowstoneSlot == -1) {
            abortCycle("Glowstone is no longer available in the hotbar.");
            return;
        }

        if (switchToSlot(glowstoneSlot)) {
            actionCooldown = chargeDelay.getIValue();
            transition(Stage.CHARGE_ANCHOR);
        }
    }

    private void tickChargeAnchor() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }
        if (BlockUtils.isAnchorCharged(anchorPos)) {
            beginWatching();
            return;
        }
        if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
            transition(Stage.SWITCH_GLOWSTONE);
            return;
        }

        BlockHitResult hit = currentAnchorHit();
        if (hit == null) {
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        if (interact(hit)) {
            transition(Stage.CONFIRM_CHARGE);
        } else {
            actionCooldown = chargeDelay.getIValue();
        }
    }

    private void tickConfirmCharge() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }
        if (BlockUtils.isAnchorCharged(anchorPos)) {
            beginWatching();
            return;
        }

        if (++stageTicks > confirmationTimeout.getIValue()) {
            actionCooldown = chargeDelay.getIValue();
            transition(Stage.CHARGE_ANCHOR);
        }
    }

    private void beginWatching() {
        // Only real camera movement after the charge selects safe mode.
        watchYaw = mc.gameRenderer.getCamera().getYaw();
        watchPitch = mc.gameRenderer.getCamera().getPitch();
        watchTicks = 0;
        safePath = false;
        transition(Stage.WATCH_POV);
    }

    private void tickWatchPov() {
        if (!anchorCharged()) {
            cancelCycle();
            return;
        }

        if (povMovement() >= povThreshold.getValue()) {
            safePath = true;
            actionCooldown = safePlaceDelays.getRandomInt();
            transition(Stage.PLACE_SAFE_BLOCK);
            return;
        }

        if (++watchTicks >= decisionWindow.getIValue()) {
            safePath = false;
            slotCooldown = slotDelays.getRandomInt();
            transition(Stage.SWITCH_DETONATOR);
        }
    }

    private void tickPlaceSafeBlock() {
        if (!anchorCharged()) {
            cancelCycle();
            return;
        }

        int glowstoneSlot = InvUtils.getItemSlot(Items.GLOWSTONE);
        if (glowstoneSlot == -1) {
            abortCycle("Safe mode needs another glowstone after charging the anchor.");
            return;
        }
        if (!switchToSlot(glowstoneSlot)) {
            return;
        }

        BlockHitResult hit = currentBlockHit();
        BlockPos placementPos = validSafePlacement(hit);
        if (placementPos == null) {
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        safeBlockPos = placementPos;
        if (interact(hit)) {
            transition(Stage.CONFIRM_SAFE_BLOCK);
        } else {
            safeBlockPos = null;
            actionCooldown = safePlaceDelays.getRandomInt();
        }
    }

    private void tickConfirmSafeBlock() {
        if (!anchorCharged()) {
            cancelCycle();
            return;
        }
        if (safeBlockPos != null && mc.world.getBlockState(safeBlockPos).isOf(Blocks.GLOWSTONE)) {
            slotCooldown = slotDelays.getRandomInt();
            transition(Stage.SWITCH_DETONATOR);
            return;
        }

        if (++stageTicks > confirmationTimeout.getIValue()) {
            safeBlockPos = null;
            actionCooldown = safePlaceDelays.getRandomInt();
            transition(Stage.PLACE_SAFE_BLOCK);
        }
    }

    private void tickSwitchDetonator() {
        if (!anchorCharged()) {
            cancelCycle();
            return;
        }

        int detonationSlot = getDetonationSlot();
        if (detonationSlot == -1) {
            abortCycle("No non-glowstone hotbar slot is available for detonation.");
            return;
        }

        if (switchToSlot(detonationSlot)) {
            actionCooldown = detonationDelays.getRandomInt();
            transition(Stage.WAIT_ANCHOR_AIM);
        }
    }

    private void tickWaitAnchorAim() {
        if (!anchorCharged()) {
            cancelCycle();
            return;
        }

        int detonationSlot = getDetonationSlot();
        if (detonationSlot == -1) {
            abortCycle("No non-glowstone hotbar slot is available for detonation.");
            return;
        }
        if (!switchToSlot(detonationSlot)) {
            return;
        }

        BlockHitResult hit = currentAnchorHit();
        if (hit == null) {
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        if (interact(hit)) {
            transition(Stage.FINISH);
        } else {
            actionCooldown = detonationDelays.getRandomInt();
        }
    }

    private void tickFinish() {
        if (!anchorCharged()) {
            // A successful cycle intentionally leaves the chosen detonator
            // selected. Keep suppressing held RMB until release so it cannot
            // interact with an unrelated block immediately after the explosion.
            resetCycle(false, isUseHeld());
            return;
        }
        if (++stageTicks >= 10) {
            abortCycle("The server accepted the interaction, but the anchor did not detonate.");
        }
    }

    private BlockPos findPlacedAnchor() {
        if (anchorCandidatePrimary != null && mc.world.getBlockState(anchorCandidatePrimary).isOf(Blocks.RESPAWN_ANCHOR)) {
            return anchorCandidatePrimary;
        }
        if (anchorCandidateSecondary != null && mc.world.getBlockState(anchorCandidateSecondary).isOf(Blocks.RESPAWN_ANCHOR)) {
            return anchorCandidateSecondary;
        }
        return null;
    }

    private BlockHitResult currentBlockHit() {
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        return null;
    }

    private BlockHitResult currentAnchorHit() {
        BlockHitResult hit = currentBlockHit();
        return hit != null && anchorPos != null && hit.getBlockPos().equals(anchorPos) ? hit : null;
    }

    /**
     * Mirrors AutoAnchor's NeighborBlock behavior: the player's live crosshair
     * chooses the support block, while the normal vanilla placement context
     * decides the exact glowstone position. The charged anchor itself is the
     * only forbidden target; no artificial neighbor-distance restriction is
     * applied.
     */
    private BlockPos validSafePlacement(BlockHitResult hit) {
        if (hit == null
                || anchorPos == null
                || hit.getBlockPos().equals(anchorPos)
                || !mc.player.getMainHandStack().isOf(Items.GLOWSTONE)
                || !(mc.player.getMainHandStack().getItem() instanceof BlockItem blockItem)) {
            return null;
        }

        ItemPlacementContext context = new ItemPlacementContext(
                mc.player,
                Hand.MAIN_HAND,
                mc.player.getMainHandStack(),
                hit
        );
        BlockPos placementPos = context.getBlockPos();
        BlockState existingState = mc.world.getBlockState(placementPos);
        BlockState glowstoneState = blockItem.getBlock().getPlacementState(context);

        if (placementPos.equals(anchorPos)
                || !existingState.isReplaceable()
                || glowstoneState == null
                || !blockItem.canPlace(context, glowstoneState)) {
            return null;
        }

        return placementPos;
    }

    private boolean interact(BlockHitResult hit) {
        boolean strict = serverTiming.is(ServerTiming.Strict);
        if (strict && serverActionCooldown > 0) {
            return false;
        }

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        interactionPerformedThisTick = true;
        if (strict) {
            serverActionCooldown = strictActionGap.getIValue();
        }

        if (!result.isAccepted()) {
            return false;
        }
        if (PlayerUtils.shouldSwingHand(result)) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return true;
    }

    private int getDetonationSlot() {
        if (preferTotem.isEnabled()) {
            int totemSlot = InvUtils.getItemSlot(Items.TOTEM_OF_UNDYING);
            if (totemSlot != -1) {
                return totemSlot;
            }
        }

        int configuredSlot = fallbackDetonationSlot.getIValue() - 1;
        if (isValidDetonationSlot(configuredSlot)) {
            return configuredSlot;
        }

        // Empty slots and ordinary items can detonate a charged anchor. Avoid
        // glowstone (which adds another charge) and anchors (which can conflict
        // with AirAnchor) when recovering from an invalid configured slot.
        for (int slot = 0; slot < 9; slot++) {
            if (isValidDetonationSlot(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isValidDetonationSlot(int slot) {
        return slot >= 0
                && slot <= 8
                && !mc.player.getInventory().getStack(slot).isOf(Items.GLOWSTONE)
                && !mc.player.getInventory().getStack(slot).isOf(Items.RESPAWN_ANCHOR);
    }

    private boolean switchToSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        if (mc.player.getInventory().selectedSlot == slot) {
            return true;
        }
        if (slotCooldown > 0) {
            slotCooldown--;
            return false;
        }

        mc.player.getInventory().selectedSlot = slot;
        mc.interactionManager.syncSelectedSlot();
        slotChangedThisTick = true;
        slotCooldown = slotDelays.getRandomInt();
        return true;
    }

    private double povMovement() {
        float cameraYaw = mc.gameRenderer.getCamera().getYaw();
        float cameraPitch = mc.gameRenderer.getCamera().getPitch();
        float yawMovement = Math.abs(MathHelper.wrapDegrees(cameraYaw - watchYaw));
        float pitchMovement = Math.abs(cameraPitch - watchPitch);
        return Math.hypot(yawMovement, pitchMovement);
    }

    private boolean anchorExists() {
        return anchorPos != null && mc.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    private boolean anchorCharged() {
        return anchorExists() && BlockUtils.isAnchorCharged(anchorPos);
    }

    private boolean isUseHeld() {
        return mc != null
                && mc.getWindow() != null
                && KeyUtils.isKeyPressed(mc.options.useKey.boundKey.getCode());
    }

    private void disableRegularAutoAnchor() {
        if (Template.moduleManager == null) {
            return;
        }
        AutoAnchorRewriteModule autoAnchor = Template.moduleManager.getModule(AutoAnchorRewriteModule.class);
        if (autoAnchor != null && autoAnchor.isEnabled()) {
            autoAnchor.setEnabled(false);
        }
    }

    private void abortCycle(String reason) {
        notifyFailure(reason);
        resetCycle(true, isUseHeld());
    }

    private void cancelCycle() {
        resetCycle(true, isUseHeld());
    }

    private void notifyFailure(String reason) {
        if (Template.notificationManager() != null) {
            Template.notificationManager().addNotification(
                    new Notification("Smart Auto Anchor", 3500, reason)
            );
        }
    }

    private void transition(Stage nextStage) {
        stage = nextStage;
        stageTicks = 0;
    }

    private void resetCycle(boolean restoreSlot, boolean suppressUntilRelease) {
        if (restoreSlot
                && restoreSlotOnCancel.isEnabled()
                && mc.player != null
                && mc.interactionManager != null
                && originalAnchorSlot >= 0
                && originalAnchorSlot <= 8) {
            mc.player.getInventory().selectedSlot = originalAnchorSlot;
            mc.interactionManager.syncSelectedSlot();
        }

        stage = Stage.IDLE;
        stageTicks = 0;
        watchTicks = 0;
        slotCooldown = 0;
        actionCooldown = 0;
        serverActionCooldown = 0;
        safePath = false;
        suppressUseUntilRelease = suppressUntilRelease;
        interactionPerformedThisTick = false;
        slotChangedThisTick = false;
        anchorCandidatePrimary = null;
        anchorCandidateSecondary = null;
        anchorPos = null;
        safeBlockPos = null;
        originalAnchorSlot = -1;
        clearPendingPlacement();
    }

    private void clearPendingPlacement() {
        pendingInitialPlacement = false;
        pendingPrimaryCandidate = null;
        pendingSecondaryCandidate = null;
        pendingOriginalSlot = -1;
    }
}
