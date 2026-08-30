package hack.echo.client.features.impl.combat;

import hack.echo.client.event.EventSubscribe;
import hack.echo.client.event.impl.EventPerformUseItemOn;
import hack.echo.client.event.impl.EventStartUseItem;
import hack.echo.client.event.impl.EventTick;
import hack.echo.client.features.Category;
import hack.echo.client.features.Feature;
import hack.echo.client.features.FeatureInfo;
import hack.echo.client.features.settings.impl.BoolSetting;
import hack.echo.client.features.settings.impl.IntSetting;
import hack.echo.client.features.settings.impl.ModeSetting;
import hack.echo.client.features.settings.impl.RangeSetting;
import hack.echo.client.mixin.accessors.KeyMappingAccessor;
import hack.echo.client.utils.blocks.BlockUtils;
import hack.echo.client.utils.inventory.InventoryUtils;
import hack.echo.client.utils.strings.Concat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

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
 *
 * <p>Ported from Stegered's SmartAutoAnchorModule (template.rip) onto Echo's
 * feature/event/setting API, keeping the AutoAnchor name.</p>
 */
public class AutoAnchor extends Feature {

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

    private static final CharSequence SERVER_TIMING_STRICT = Concat.of("Strict");
    private static final CharSequence SERVER_TIMING_FAST = Concat.of("Fast");

    public AutoAnchor() {
        super(new FeatureInfo(
                Concat.of("Auto Anchor"),
                Concat.of("Smart anchor cycling that watches your POV"),
                Category.COMBAT
        ));
    }

    public final IntSetting povThreshold = new IntSetting(
            Concat.of("POV Threshold"),
            35, 5, 180,
            Concat.of(" degrees")
    );
    public final IntSetting decisionWindow = new IntSetting(
            Concat.of("Decision Window"),
            6, 0, 20,
            Concat.of(" ticks")
    );

    public final ModeSetting serverTiming = new ModeSetting(
            Concat.of("Server Timing"),
            SERVER_TIMING_STRICT,
            SERVER_TIMING_STRICT, SERVER_TIMING_FAST
    );
    public final IntSetting strictActionGap = new IntSetting(
            Concat.of("Strict Action Gap"),
            2, 1, 10,
            Concat.of(" ticks"),
            p -> serverTiming.is(SERVER_TIMING_STRICT)
    );
    public final IntSetting chargeDelay = new IntSetting(
            Concat.of("Charge Delay"),
            2, 0, 10,
            Concat.of(" ticks")
    );
    public final RangeSetting safePlaceDelays = new RangeSetting(
            Concat.of("Safe Place Delays"),
            1f, 2f, 0f, 10f, 1f,
            Concat.of(" ticks")
    );
    public final RangeSetting detonationDelays = new RangeSetting(
            Concat.of("Detonation Delays"),
            1f, 2f, 0f, 10f, 1f,
            Concat.of(" ticks")
    );
    public final RangeSetting slotDelays = new RangeSetting(
            Concat.of("Slot Delays"),
            1f, 2f, 0f, 10f, 1f,
            Concat.of(" ticks")
    );

    public final BoolSetting preferTotem = new BoolSetting(Concat.of("Prefer Totem"), true);
    public final IntSetting fallbackDetonationSlot = new IntSetting(
            Concat.of("Fallback Detonation Slot"),
            5, 1, 9
    );
    public final BoolSetting restoreSlotOnCancel = new BoolSetting(Concat.of("Restore Slot On Cancel"), true);
    public final IntSetting confirmationTimeout = new IntSetting(
            Concat.of("Confirmation Timeout"),
            40, 10, 100,
            Concat.of(" ticks")
    );

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

    @Override
    public void onEnable() {
        super.onEnable();
        resetCycle(false, false);
    }

    @Override
    public void onDisable() {
        resetCycle(true, false);
        super.onDisable();
    }

    @Override
    public String getInfo() {
        return switch (stage) {
            case IDLE -> "";
            case WATCH_POV -> "Watching";
            case PLACE_SAFE_BLOCK, CONFIRM_SAFE_BLOCK -> "Safe";
            case SWITCH_DETONATOR, WAIT_ANCHOR_AIM, FINISH -> safePath ? "Safe" : "Normal";
            default -> "Active";
        };
    }

    /**
     * Captures the intended anchor position before vanilla consumes the item.
     */
    @EventSubscribe
    private void onInteractBlockPre(EventPerformUseItemOn.Pre event) {
        pendingInitialPlacement = false;

        if (stage != Stage.IDLE
                || suppressUseUntilRelease
                || event.getHand() != InteractionHand.MAIN_HAND
                || !isUseHeld()
                || isNull()
                || mc.screen != null
                || !mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
            return;
        }

        BlockHitResult hit = event.getHitResult();
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = mc.level.getBlockState(clickedPos);

        // Clicking an existing anchor is a detonation/air-place action, not the
        // start of a new smart cycle.
        if (clickedState.is(Blocks.RESPAWN_ANCHOR)) {
            return;
        }

        BlockPos adjacentPos = clickedPos.relative(hit.getDirection());
        pendingPrimaryCandidate = clickedState.canBeReplaced() ? clickedPos : adjacentPos;
        pendingSecondaryCandidate = pendingPrimaryCandidate.equals(clickedPos) ? adjacentPos : clickedPos;
        pendingOriginalSlot = mc.player.getInventory().getSelectedSlot();
        pendingInitialPlacement = true;
    }

    /**
     * Starts only after vanilla reports that the player's initial interaction
     * was accepted (client-side prediction shows the anchor at one of the
     * captured candidates). No placement packet or rotation is synthesized.
     */
    @EventSubscribe
    private void onInteractBlockPost(EventPerformUseItemOn.Post event) {
        if (!pendingInitialPlacement || stage != Stage.IDLE || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        pendingInitialPlacement = false;
        if (!isUseHeld() || (!anchorAt(pendingPrimaryCandidate) && !anchorAt(pendingSecondaryCandidate))) {
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
    @EventSubscribe(priority = EventSubscribe.Priority.HIGHEST)
    private void onItemUse(EventStartUseItem.Pre event) {
        if (stage != Stage.IDLE || suppressUseUntilRelease) {
            event.cancel();
        }
    }

    @EventSubscribe
    private void onTick(EventTick event) {
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

        if (isNull() || mc.screen != null || !isUseHeld()) {
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
                    || (isStrictTiming() && (interactionPerformedThisTick || slotChangedThisTick))) {
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
        if (getGlowstoneSlot() == -1) {
            suppressUseUntilRelease = isUseHeld();
            clearPendingPlacement();
            return;
        }
        anchorCandidatePrimary = pendingPrimaryCandidate;
        anchorCandidateSecondary = pendingSecondaryCandidate;
        originalAnchorSlot = pendingOriginalSlot;
        anchorPos = null;
        safeBlockPos = null;
        safePath = false;
        watchTicks = 0;
        slotCooldown = randomRange(slotDelays);
        actionCooldown = chargeDelay.getValue();
        ((KeyMappingAccessor) mc.options.keyUse).setClickCount(0);
        clearPendingPlacement();
        transition(Stage.WAIT_ANCHOR);
    }

    private void tickWaitAnchor() {
        anchorPos = findPlacedAnchor();
        if (anchorPos != null) {
            int glowstoneSlot = getGlowstoneSlot();
            if (glowstoneSlot == -1) {
                cancelCycle();
                return;
            }

            // The initial glowstone switch is immediate so the default charge
            // delay is measured from anchor confirmation, not from slot timing.
            slotCooldown = 0;
            switchToSlot(glowstoneSlot);
            actionCooldown = chargeDelay.getValue();
            transition(Stage.CHARGE_ANCHOR);
            return;
        }

        if (++stageTicks > confirmationTimeout.getValue()) {
            cancelCycle();
        }
    }

    private void tickSwitchGlowstone() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }

        int glowstoneSlot = getGlowstoneSlot();
        if (glowstoneSlot == -1) {
            cancelCycle();
            return;
        }

        if (switchToSlot(glowstoneSlot)) {
            actionCooldown = chargeDelay.getValue();
            transition(Stage.CHARGE_ANCHOR);
        }
    }

    private void tickChargeAnchor() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }
        if (isAnchorCharged()) {
            beginWatching();
            return;
        }
        if (!mc.player.getMainHandItem().is(Items.GLOWSTONE)) {
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
            actionCooldown = chargeDelay.getValue();
        }
    }

    private void tickConfirmCharge() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }
        if (isAnchorCharged()) {
            beginWatching();
            return;
        }

        if (++stageTicks > confirmationTimeout.getValue()) {
            actionCooldown = chargeDelay.getValue();
            transition(Stage.CHARGE_ANCHOR);
        }
    }

    private void beginWatching() {
        // Only real POV movement after the charge selects safe mode.
        watchYaw = mc.player.getYRot();
        watchPitch = mc.player.getXRot();
        watchTicks = 0;
        safePath = false;
        transition(Stage.WATCH_POV);
    }

    private void tickWatchPov() {
        if (!isAnchorCharged()) {
            cancelCycle();
            return;
        }

        if (povMovement() >= povThreshold.getValue()) {
            safePath = true;
            actionCooldown = randomRange(safePlaceDelays);
            transition(Stage.PLACE_SAFE_BLOCK);
            return;
        }

        if (++watchTicks >= decisionWindow.getValue()) {
            safePath = false;
            slotCooldown = randomRange(slotDelays);
            transition(Stage.SWITCH_DETONATOR);
        }
    }

    private void tickPlaceSafeBlock() {
        if (!isAnchorCharged()) {
            cancelCycle();
            return;
        }

        int glowstoneSlot = getGlowstoneSlot();
        if (glowstoneSlot == -1) {
            cancelCycle();
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
            actionCooldown = randomRange(safePlaceDelays);
        }
    }

    private void tickConfirmSafeBlock() {
        if (!isAnchorCharged()) {
            cancelCycle();
            return;
        }
        if (safeBlockPos != null && mc.level.getBlockState(safeBlockPos).is(Blocks.GLOWSTONE)) {
            slotCooldown = randomRange(slotDelays);
            transition(Stage.SWITCH_DETONATOR);
            return;
        }

        if (++stageTicks > confirmationTimeout.getValue()) {
            safeBlockPos = null;
            actionCooldown = randomRange(safePlaceDelays);
            transition(Stage.PLACE_SAFE_BLOCK);
        }
    }

    private void tickSwitchDetonator() {
        if (!isAnchorCharged()) {
            cancelCycle();
            return;
        }

        int detonationSlot = getDetonationSlot();
        if (detonationSlot == -1) {
            cancelCycle();
            return;
        }

        if (switchToSlot(detonationSlot)) {
            actionCooldown = randomRange(detonationDelays);
            transition(Stage.WAIT_ANCHOR_AIM);
        }
    }

    private void tickWaitAnchorAim() {
        if (!isAnchorCharged()) {
            cancelCycle();
            return;
        }

        int detonationSlot = getDetonationSlot();
        if (detonationSlot == -1) {
            cancelCycle();
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
            actionCooldown = randomRange(detonationDelays);
        }
    }

    private void tickFinish() {
        if (!isAnchorCharged()) {
            // A successful cycle intentionally leaves the chosen detonator
            // selected. Keep suppressing held RMB until release so it cannot
            // interact with an unrelated block immediately after the explosion.
            resetCycle(false, isUseHeld());
            return;
        }
        if (++stageTicks >= 10) {
            resetCycle(true, isUseHeld());
        }
    }

    private BlockPos findPlacedAnchor() {
        if (anchorCandidatePrimary != null && mc.level.getBlockState(anchorCandidatePrimary).is(Blocks.RESPAWN_ANCHOR)) {
            return anchorCandidatePrimary;
        }
        if (anchorCandidateSecondary != null && mc.level.getBlockState(anchorCandidateSecondary).is(Blocks.RESPAWN_ANCHOR)) {
            return anchorCandidateSecondary;
        }
        return null;
    }

    private boolean anchorAt(BlockPos pos) {
        return pos != null && mc.level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR);
    }

    private BlockHitResult currentBlockHit() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        return null;
    }

    private BlockHitResult currentAnchorHit() {
        BlockHitResult hit = currentBlockHit();
        return hit != null && anchorPos != null && hit.getBlockPos().equals(anchorPos) ? hit : null;
    }

    /**
     * Mirrors the neighbor-block behavior: the player's live crosshair chooses
     * the support block, while the normal vanilla placement context decides the
     * exact glowstone position. The charged anchor itself is the only forbidden
     * target; no artificial neighbor-distance restriction is applied.
     */
    private BlockPos validSafePlacement(BlockHitResult hit) {
        if (hit == null
                || anchorPos == null
                || hit.getBlockPos().equals(anchorPos)
                || !mc.player.getMainHandItem().is(Items.GLOWSTONE)
                || !(mc.player.getMainHandItem().getItem() instanceof BlockItem blockItem)) {
            return null;
        }

        BlockPlaceContext context = new BlockPlaceContext(
                mc.player,
                InteractionHand.MAIN_HAND,
                mc.player.getMainHandItem(),
                hit
        );
        BlockPos placementPos = context.getClickedPos();
        BlockState existingState = mc.level.getBlockState(placementPos);
        BlockState glowstoneState = blockItem.getBlock().getStateForPlacement(context);

        if (placementPos.equals(anchorPos)
                || !existingState.canBeReplaced()
                || glowstoneState == null
                || !canPlaceGlowstone(blockItem, context, placementPos, glowstoneState)) {
            return null;
        }

        return placementPos;
    }

    private boolean interact(BlockHitResult hit) {
        boolean strict = isStrictTiming();
        if (strict && serverActionCooldown > 0) {
            return false;
        }

        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        interactionPerformedThisTick = true;
        if (strict) {
            serverActionCooldown = strictActionGap.getValue();
        }

        if (!result.consumesAction()) {
            return false;
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private int getDetonationSlot() {
        if (preferTotem.getValue()) {
            int totemSlot = InventoryUtils.findItemWithPredicateInHotbar(
                    stack -> stack.is(Items.TOTEM_OF_UNDYING));
            if (totemSlot != -1) {
                return totemSlot;
            }
        }

        int configuredSlot = fallbackDetonationSlot.getValue() - 1;
        if (isValidDetonationSlot(configuredSlot)) {
            return configuredSlot;
        }

        // Empty slots and ordinary items can detonate a charged anchor. Avoid
        // glowstone (which adds another charge) and anchors when recovering
        // from an invalid configured slot.
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
                && !mc.player.getInventory().getItem(slot).is(Items.GLOWSTONE)
                && !mc.player.getInventory().getItem(slot).is(Items.RESPAWN_ANCHOR);
    }

    private boolean switchToSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            return true;
        }
        if (slotCooldown > 0) {
            slotCooldown--;
            return false;
        }

        mc.player.getInventory().setSelectedSlot(slot);
        slotChangedThisTick = true;
        slotCooldown = randomRange(slotDelays);
        return true;
    }

    private int getGlowstoneSlot() {
        return InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.GLOWSTONE));
    }

    private double povMovement() {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        float yawMovement = Math.abs(Mth.wrapDegrees(yaw - watchYaw));
        float pitchMovement = Math.abs(pitch - watchPitch);
        return Math.hypot(yawMovement, pitchMovement);
    }

    private boolean anchorExists() {
        return anchorPos != null && mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR);
    }

    private boolean isAnchorCharged() {
        return anchorExists() && BlockUtils.isRespawnAnchorCharged(anchorPos);
    }

    private boolean isStrictTiming() {
        return serverTiming.is(SERVER_TIMING_STRICT);
    }

    private boolean isUseHeld() {
        if (mc == null || mc.getWindow() == null || mc.options == null) {
            return false;
        }
        InputConstants.Key key = ((KeyMappingAccessor) mc.options.keyUse).getKey();
        if (key == null) {
            return false;
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return GLFW.glfwGetKey(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
    }

    private int randomRange(RangeSetting setting) {
        return (int) (setting.getMinValue() + Math.random() * (setting.getMaxValue() - setting.getMinValue() + 1));
    }

    /**
     * Mirror of BlockItem.canPlace, which became protected in newer versions.
     */
    private static boolean canPlaceGlowstone(BlockItem blockItem, BlockPlaceContext context, BlockPos pos, BlockState state) {
        if (!context.getLevel().isInsideBuildHeight(pos)) return false;
        if (!blockItem.getBlock().isEnabled(context.getLevel().enabledFeatures())) return false;
        return state.canSurvive(context.getLevel(), pos);
    }

    private void cancelCycle() {
        resetCycle(true, isUseHeld());
    }

    private void transition(Stage nextStage) {
        stage = nextStage;
        stageTicks = 0;
    }

    private void resetCycle(boolean restoreSlot, boolean suppressUntilRelease) {
        if (restoreSlot
                && restoreSlotOnCancel.getValue()
                && mc.player != null
                && originalAnchorSlot >= 0
                && originalAnchorSlot <= 8) {
            mc.player.getInventory().setSelectedSlot(originalAnchorSlot);
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
