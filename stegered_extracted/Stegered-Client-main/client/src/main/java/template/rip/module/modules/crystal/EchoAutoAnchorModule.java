package template.rip.module.modules.crystal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import template.rip.Template;
import template.rip.api.anchor.AnchorAimSearch;
import template.rip.api.anchor.RotationConvergenceTracker;
import template.rip.api.event.events.HandleInputEvent;
import template.rip.api.event.events.ItemUseEvent;
import template.rip.api.event.events.MouseUpdateEvent;
import template.rip.api.event.events.TickEvent;
import template.rip.api.event.events.WorldRenderEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.event.orbit.EventPriority;
import template.rip.api.blockesp.WorldRenderContext;
import template.rip.api.font.JColor;
import template.rip.api.object.Description;
import template.rip.api.object.ExplosionImpl;
import template.rip.api.rotation.Rotation;
import template.rip.api.rotation.RotationUtils;
import template.rip.api.util.BlockUtils;
import template.rip.api.util.DamageUtils;
import template.rip.api.util.InvUtils;
import template.rip.api.util.KeyUtils;
import template.rip.api.util.PlayerUtils;
import template.rip.api.util.RenderUtils;
import template.rip.api.util.SwapStateManager;
import template.rip.module.Module;
import template.rip.module.modules.client.AchillesSettingsModule;
import template.rip.module.setting.settings.BooleanSetting;
import template.rip.module.setting.settings.ColorSetting;
import template.rip.module.setting.settings.DividerSetting;
import template.rip.module.setting.settings.KeybindSetting;
import template.rip.module.setting.settings.MinMaxNumberSetting;
import template.rip.module.setting.settings.ModeSetting;
import template.rip.module.setting.settings.NumberSetting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ECHO's Auto Anchor, ported with its full bypass suite.
 *
 * <p>Picks an anchor placement near the closest target, places it, and runs the
 * charge/explode sequence. Optional safety-block step drops a glowstone between
 * us and the anchor before charging so we eat less of our own explosion.
 *
 * <p>The interesting bits live in helper classes:
 * <ul>
 *   <li>{@link AnchorAimSearch} -- finds an aim point on the anchor that
 *       raycasts cleanly (works even when an adjacent block occludes the
 *       eye-side face).</li>
 *   <li>{@link RotationConvergenceTracker} -- per-tick rotation delta so we
 *       only fire interactions on a tick where Grim's {@code deltaX > 2}
 *       gate is closed.</li>
 *   <li>{@link SwapStateManager} -- silent slot swaps with scheduled
 *       restoration, so the client never visibly juggles hotbar slots.</li>
 * </ul>
 */
public final class EchoAutoAnchorModule extends Module {

    private static final float ROTATION_SETTLE_THRESHOLD = 1.7f;
    private static final int VANILLA_PLACE_COOLDOWN_TICKS = 4;
    private static final int MAX_ANCHOR_CHARGES = 4;

    public enum Mode {
        Click,
        Hold
    }

    private enum AnchorStep { PLACE_SAFETY, CHARGE, EXPLODE }

    // -- Settings --

    public final DividerSetting activationDivider = new DividerSetting(this, false, "Activation");
    public final ModeSetting<Mode> mode = new ModeSetting<>(this, Description.of("Click fires one full cycle per key press; Hold repeats while the key is down."), Mode.Click, "Mode");
    public final KeybindSetting activateKey = new KeybindSetting(
            this, -1, false,
            Description.of("If this matches your Use Key, start the sequence while holding a respawn anchor so vanilla use packets are not duplicated."),
            "Activate Key"
    );
    public final BooleanSetting repeat = new BooleanSetting(
            this,
            Description.of("Repeats the cycle while the key is held."),
            true,
            "Repeat"
    );

    public final DividerSetting aimDivider = new DividerSetting(this, false, "Aiming");
    public final BooleanSetting autoAim = new BooleanSetting(
            this,
            Description.of("Rotates toward the anchor/placement automatically."),
            true,
            "Auto Aim"
    );
    public final BooleanSetting silentAim = new BooleanSetting(
            this,
            Description.of("Aims through the rotation manager so your real view never moves. The convergence tracker only fires interactions once the spoofed rotation settles, keeping Grim's DuplicateRotPlace gate closed."),
            false,
            "Silent Aim"
    );
    public final NumberSetting aimSpeed = new NumberSetting(
            this,
            Description.of("Rotation speed in degrees per second."),
            120,
            0,
            600,
            1,
            "Aim Speed"
    );

    public final DividerSetting renderDivider = new DividerSetting(this, false, "Rendering");
    public final BooleanSetting renderPosition = new BooleanSetting(
            this,
            Description.of("Renders the best anchor placement position."),
            true,
            "Render Position"
    );
    public final ColorSetting fillColor = new ColorSetting(
            this,
            new JColor(66, 135, 245, 55),
            true,
            "Fill Color"
    );
    public final ColorSetting outlineColor = new ColorSetting(
            this,
            new JColor(255, 255, 255, 255),
            true,
            "Outline Color"
    );

    public final DividerSetting safetyDivider = new DividerSetting(this, false, "Safety");
    public final BooleanSetting canPlaceLegit = new BooleanSetting(
            this,
            Description.of("Positions where you can only place legit. When enabled, interactions require a raycast that hits the exact block face; when disabled, a looser ray-intersects-block check is used."),
            true,
            "Can Place Legit"
    );
    public final BooleanSetting safeAnchor = new BooleanSetting(
            this,
            Description.of("Place a safety block at your feet between you and the anchor before charging."),
            false,
            "Safe Anchor"
    );
    public final NumberSetting safetyTimeout = new NumberSetting(
            this,
            Description.of("Give up on placing the safety block after this long (ms) and proceed to charging anyway."),
            200,
            0,
            2000,
            1,
            "Safety Timeout"
    );
    public final NumberSetting minDamage = new NumberSetting(
            this,
            Description.of("Skip the safety block if the anchor wouldn't deal at least this much damage to you."),
            6.0,
            0.0,
            20.0,
            0.5,
            "Min Damage"
    );

    public final DividerSetting sequenceDivider = new DividerSetting(this, false, "Sequence");
    public final MinMaxNumberSetting charges = new MinMaxNumberSetting(
            this,
            Description.of("How many charges to add before detonating."),
            1,
            1,
            1,
            MAX_ANCHOR_CHARGES,
            1,
            "Charges"
    );
    public final MinMaxNumberSetting chargeDelay = new MinMaxNumberSetting(
            this,
            Description.of("Randomized delay between charges, in ticks. Randomness prevents identical packet deltas across cycles."),
            1,
            1,
            0,
            20,
            1,
            "Charge Delay"
    );
    public final NumberSetting explodeSlot = new NumberSetting(
            this,
            Description.of("Hotbar slot used to detonate the anchor."),
            1,
            1,
            9,
            1,
            "Explode Slot"
    );
    public final MinMaxNumberSetting explodeDelay = new MinMaxNumberSetting(
            this,
            Description.of("Randomized delay between the last charge and detonation, in ticks."),
            1,
            1,
            0,
            20,
            1,
            "Explode Delay"
    );

    // -- State --

    private boolean clickQueued;
    private boolean keyWasDown;
    private boolean wasAiming;
    private boolean restoringSwap;
    private boolean holdCycleCompleted;
    private boolean silentRotationActive;
    private int placeCooldown;
    private AnchorStep anchorStep;
    private BlockPos activeAnchorPos;
    private BlockPos pendingUseKeyAnchorPos;
    private int remainingCharges;
    private int nextAnchorActionTick;

    /** Aim point picked once per sequence; revalidated each call so a freshly
     *  placed safety block can force a re-pick onto an unobstructed face. */
    private Vec3d cachedAnchorAim;

    /** Wall-clock at PLACE_SAFETY entry; used by the safety timeout. */
    private long safetyStepStartMillis;

    private final RotationConvergenceTracker rotation = new RotationConvergenceTracker();

    private long lastNanoTime;

    public EchoAutoAnchorModule(Category category, Description description, String name) {
        super(category, description, name);
        activationDivider.addSetting(mode, activateKey, repeat);
        aimDivider.addSetting(autoAim, silentAim, aimSpeed);
        renderDivider.addSetting(renderPosition, fillColor, outlineColor);
        safetyDivider.addSetting(canPlaceLegit, safeAnchor, safetyTimeout, minDamage);
        sequenceDivider.addSetting(charges, chargeDelay, explodeSlot, explodeDelay);

        repeat.addConditionMode(mode, Mode.Hold);
        silentAim.addConditionBoolean(autoAim, true);
        aimSpeed.addConditionBoolean(autoAim, true);
        fillColor.addConditionBoolean(renderPosition, true);
        outlineColor.addConditionBoolean(renderPosition, true);
        safetyTimeout.addConditionBoolean(safeAnchor, true);
        minDamage.addConditionBoolean(safeAnchor, true);
    }

    @Override
    public void onEnable() {
        clickQueued = false;
        keyWasDown = false;
        wasAiming = false;
        restoringSwap = false;
        holdCycleCompleted = false;
        silentRotationActive = false;
        placeCooldown = 0;
        rotation.reset();
        resetAnchorSequence();
        disableSiblingAnchorModules();
    }

    @Override
    public void onDisable() {
        clickQueued = false;
        keyWasDown = false;
        wasAiming = false;
        restoringSwap = false;
        holdCycleCompleted = false;
        silentRotationActive = false;
        placeCooldown = 0;
        resetAnchorSequence();
        SwapStateManager.cancel(this, true);
        disengageRotations();
    }

    // -- Event handlers --

    /**
     * Same-use-key flow: when the Activate Key matches the Use Key, the user
     * drives placement by looking at a block. We cancel the vanilla use so the
     * sequence owns every interaction and packets are never duplicated.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onItemUse(ItemUseEvent.Pre event) {
        if (!shouldHandleSameUseKey()) {
            return;
        }
        if (mc.player == null || mc.currentScreen != null) {
            return;
        }
        if (mode.is(Mode.Hold) && !KeyUtils.isKeyPressed(activateKey.getCode()) && !isAnchorSequenceActive()) {
            return;
        }

        boolean startFromAnchor = !isAnchorSequenceActive() && isHoldingRespawnAnchor();
        if (startFromAnchor) {
            pendingUseKeyAnchorPos = anchorPosOf(mc.crosshairTarget);
        }

        if (!startFromAnchor && !isAnchorSequenceActive() && !SwapStateManager.isOwnerActive(this) && !restoringSwap) {
            return;
        }

        if (!mode.is(Mode.Hold) && startFromAnchor) {
            clickQueued = true;
            if (!isAnchorSequenceActive()) {
                restoringSwap = false;
                placeCooldown = 0;
            }
        }

        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        rotation.update();

        if (placeCooldown > 0) {
            placeCooldown--;
        }
        if (!SwapStateManager.isOwnerActive(this)) {
            restoringSwap = false;
        }

        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            clickQueued = false;
            resetAnchorSequence();
            handleInactive();
            keyWasDown = false;
            return;
        }

        boolean sameUseKey = shouldHandleSameUseKey();
        boolean keyDown = KeyUtils.isKeyPressed(activateKey.getCode());
        boolean holdMode = mode.is(Mode.Hold);
        if (holdMode && !keyDown) {
            holdCycleCompleted = false;
        }

        if (!holdMode && keyDown && !keyWasDown && (!sameUseKey || isHoldingRespawnAnchor())) {
            clickQueued = true;
            if (!isAnchorSequenceActive()) {
                restoringSwap = false;
                placeCooldown = 0;
            }
        }

        boolean active = holdMode ? keyDown : clickQueued || isAnchorSequenceActive();
        if (!active) {
            resetAnchorSequence();
            handleInactive();
            keyWasDown = keyDown;
            return;
        }

        if (holdMode && !repeat.isEnabled() && holdCycleCompleted && !isAnchorSequenceActive()) {
            handleInactive();
            keyWasDown = keyDown;
            return;
        }

        if (sameUseKey && !isAnchorSequenceActive() && !isHoldingRespawnAnchor()) {
            if (!holdMode) {
                clickQueued = false;
            }
            if (!SwapStateManager.isOwnerActive(this)) {
                handleInactive();
            }
            keyWasDown = keyDown;
            return;
        }

        if (isAnchorSequenceActive()) {
            continueAnchorSequence();
            keyWasDown = keyDown;
            return;
        }

        BlockPos existingAnchor = consumePendingUseKeyAnchorPos();
        if (existingAnchor == null) {
            BlockHitResult hit = currentBlockHit();
            if (hit != null && isRespawnAnchor(hit.getBlockPos())) {
                existingAnchor = hit.getBlockPos();
            }
        }

        if (existingAnchor != null) {
            if (!isAnchorWithinInteractionRange(existingAnchor)) {
                if (!holdMode) {
                    clickQueued = false;
                }
                handleInactive();
                keyWasDown = keyDown;
                return;
            }
            beginAnchorSequence(existingAnchor, rollChargeCount());
            keyWasDown = keyDown;
            return;
        }

        keyWasDown = keyDown;
        runFreshAnchorPlacement(holdMode, sameUseKey, keyDown);
    }

    /** Handles the "no active sequence yet, no anchor under cursor" branch -- find a spot, swap, place. */
    private void runFreshAnchorPlacement(boolean holdMode, boolean useHeldAnchorSlot, boolean keyDown) {
        // Same-use-key flow: the user drives placement by looking at a block.
        // Auto-aim is reserved for charging/exploding, so we force the manual
        // path here even when Auto Aim is enabled.
        boolean useAutoAim = autoAim.isEnabled() && !useHeldAnchorSlot;
        BlockUtils.PlacementHit autoAimHit = null;
        BlockHitResult manualHit = null;
        boolean useAutoAimPlacement = false;

        if (useAutoAim) {
            autoAimHit = findBestPlacementHit();
            if (autoAimHit != null) {
                useAutoAimPlacement = true;
            } else {
                manualHit = currentBlockHit();
            }
            if (manualHit == null && !useAutoAimPlacement) {
                if (!holdMode) {
                    clickQueued = false;
                }
                handleInactive();
                return;
            }
        } else {
            manualHit = currentBlockHit();
            if (manualHit == null) {
                if (!holdMode) {
                    clickQueued = false;
                }
                handleInactive();
                return;
            }
        }

        int anchorSlot = useHeldAnchorSlot
                ? mc.player.getInventory().selectedSlot
                : InvUtils.getItemSlot(Items.RESPAWN_ANCHOR);
        if (anchorSlot == -1) {
            clickQueued = false;
            handleInactive();
            return;
        }

        restoringSwap = false;
        if (!useHeldAnchorSlot && !SwapStateManager.swapToIfNeeded(this, anchorSlot, false, -1, false)) {
            return;
        }
        if (placeCooldown > 0) {
            return;
        }
        if (!mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR)) {
            return;
        }

        BlockHitResult hitResult;
        BlockPos placedAnchorPos;
        if (useAutoAimPlacement) {
            if (autoAimHit == null || !isReadyToPlace(autoAimHit)) {
                return;
            }
            hitResult = autoAimHit.hitResult();
            placedAnchorPos = autoAimHit.placementPos();
        } else {
            hitResult = manualHit;
            placedAnchorPos = placedAnchorPosOf(hitResult);
            if (!isManualAnchorPlacementValid(placedAnchorPos)) {
                if (!holdMode) {
                    clickQueued = false;
                }
                handleInactive();
                return;
            }
        }

        ActionResult result = BlockUtils.interactWithBlock(hitResult, true);
        placeCooldown = VANILLA_PLACE_COOLDOWN_TICKS;
        if (result.isAccepted()) {
            rotation.markInteraction(silentRotationActive);
        }

        if (result.isAccepted() && placedAnchorPos != null) {
            beginAnchorSequence(placedAnchorPos, rollChargeCount());
        } else if (!holdMode) {
            finishAnchorSequence();
        }

        if (holdMode && !result.isAccepted()) {
            placeCooldown = Math.max(placeCooldown, 1);
        }
    }

    @EventHandler
    private void onMouseUpdate(MouseUpdateEvent.Post event) {
        if (!autoAim.isEnabled() || mc.player == null || mc.world == null || mc.currentScreen != null
                || !isActionActive() || (shouldHandleSameUseKey() && !isAnchorSequenceActive())) {
            disengageRotations();
            return;
        }

        Vec3d aimPoint = findActiveAimPoint();
        if (aimPoint == null) {
            disengageRotations();
            return;
        }

        if (aimAt(aimPoint)) {
            wasAiming = true;
        } else {
            disengageRotations();
        }
    }

    /**
     * When aiming silently, movement input is remapped to the spoofed rotation
     * so the server never sees us move "sideways" relative to the look
     * direction we send. Skipped when the client's own Silent move fix is
     * already active to avoid double remapping.
     */
    @EventHandler
    private void onHandleInput(HandleInputEvent.Pre event) {
        if (!autoAim.isEnabled() || !wasAiming || !silentAim.isEnabled() || !silentRotationActive) {
            return;
        }
        if (mc.player == null || mc.player.input == null) {
            return;
        }

        AchillesSettingsModule acm = Template.moduleManager.getModule(AchillesSettingsModule.class);
        if (acm != null && acm.moveFixMode.is(AchillesSettingsModule.moveFixModeEnum.Silent)) {
            return;
        }

        if (mc.player.input.movementForward == 0 && mc.player.input.movementSideways == 0) {
            return;
        }

        float realYaw = mc.gameRenderer.getCamera().getYaw();
        float fakeYaw = RotationConvergenceTracker.serverYaw();

        double moveX = mc.player.input.movementSideways * Math.cos(Math.toRadians(realYaw)) - mc.player.input.movementForward * Math.sin(Math.toRadians(realYaw));
        double moveZ = mc.player.input.movementForward * Math.cos(Math.toRadians(realYaw)) + mc.player.input.movementSideways * Math.sin(Math.toRadians(realYaw));

        double minDist = Double.MAX_VALUE;
        double bestForward = 0;
        double bestStrafe = 0;

        for (double forward = -1; forward <= 1; forward++) {
            for (double strafe = -1; strafe <= 1; strafe++) {
                double newMoveX = strafe * Math.cos(Math.toRadians(fakeYaw)) - forward * Math.sin(Math.toRadians(fakeYaw));
                double newMoveZ = forward * Math.cos(Math.toRadians(fakeYaw)) + strafe * Math.sin(Math.toRadians(fakeYaw));

                double deltaX = newMoveX - moveX;
                double deltaZ = newMoveZ - moveZ;

                double dist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

                if (minDist > dist) {
                    minDist = dist;
                    bestForward = forward;
                    bestStrafe = strafe;
                }
            }
        }
        mc.player.input.movementForward = (float) Math.round(bestForward);
        mc.player.input.movementSideways = (float) Math.round(bestStrafe);
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent event) {
        if (!renderPosition.isEnabled() || mc.player == null || mc.world == null) {
            return;
        }

        boolean renderFill = fillColor.getValue().getAlpha() > 0;
        boolean renderOutline = outlineColor.getValue().getAlpha() > 0;
        if (!renderFill && !renderOutline) {
            return;
        }

        BlockUtils.PlacementHit bestHit = findBestPlacementHit();
        if (bestHit == null) {
            return;
        }

        Box box = new Box(bestHit.placementPos());
        if (renderFill) {
            RenderUtils.Render3D.renderBox(box, fillColor.getValue(), fillColor.getValue().getAlpha(), event.context);
        }
        if (renderOutline) {
            drawBoxOutline(box, outlineColor.getValue(), event.context);
        }
    }

    // -- Sequence state machine --

    private void beginAnchorSequence(BlockPos anchorPos, int chargeCount) {
        activeAnchorPos = anchorPos;
        remainingCharges = Math.max(0, chargeCount - getAnchorCharge(anchorPos));
        anchorStep = safeAnchor.isEnabled() ? AnchorStep.PLACE_SAFETY : AnchorStep.CHARGE;
        nextAnchorActionTick = mc.player.age + (anchorStep == AnchorStep.CHARGE ? rollTickDelay(chargeDelay) : 0);
        restoringSwap = false;
        cachedAnchorAim = AnchorAimSearch.findClearAimPoint(anchorPos, Template.getTickDelta());
        safetyStepStartMillis = System.currentTimeMillis();
    }

    private void continueAnchorSequence() {
        if (!isRespawnAnchor(activeAnchorPos) || !isAnchorWithinInteractionRange(activeAnchorPos)) {
            finishAnchorSequence();
            return;
        }
        if (mc.player.age < nextAnchorActionTick) {
            return;
        }

        switch (anchorStep) {
            case PLACE_SAFETY -> placeSafetyBlock();
            case CHARGE -> chargeActiveAnchor();
            case EXPLODE -> explodeActiveAnchor();
        }
    }

    private void finishAnchorSequence() {
        resetAnchorSequence();
        if (mode.is(Mode.Hold) && KeyUtils.isKeyPressed(activateKey.getCode())) {
            if (!repeat.isEnabled()) {
                holdCycleCompleted = true;
                scheduleSwapBack(1);
                disengageRotations();
            } else if (shouldHandleSameUseKey()) {
                scheduleSwapBack(1);
            }
            return;
        }
        holdCycleCompleted = false;
        clickQueued = false;
        scheduleSwapBack(1);
    }

    private void resetAnchorSequence() {
        anchorStep = null;
        activeAnchorPos = null;
        pendingUseKeyAnchorPos = null;
        remainingCharges = 0;
        nextAnchorActionTick = 0;
        cachedAnchorAim = null;
    }

    private boolean isAnchorSequenceActive() {
        return anchorStep != null && activeAnchorPos != null;
    }

    private void advanceToChargeStep() {
        anchorStep = AnchorStep.CHARGE;
        nextAnchorActionTick = mc.player.age + rollTickDelay(chargeDelay);
    }

    private void beginExplodeStepOrFinish() {
        if (getAnchorCharge(activeAnchorPos) <= 0) {
            finishAnchorSequence();
            return;
        }
        anchorStep = AnchorStep.EXPLODE;
        nextAnchorActionTick = mc.player.age + rollTickDelay(explodeDelay);
    }

    // -- Step: PLACE_SAFETY --

    private void placeSafetyBlock() {
        // Build the safety candidate list ONCE per tick instead of three times.
        List<BlockPos> ranked = rankedSafetyCandidates();

        if (!shouldPlaceSafety(ranked)) {
            advanceToChargeStep();
            return;
        }

        int timeoutMs = safetyTimeout.getIValue();
        if (timeoutMs > 0 && System.currentTimeMillis() - safetyStepStartMillis >= timeoutMs) {
            advanceToChargeStep();
            return;
        }

        boolean useAutoAim = autoAim.isEnabled();
        BlockHitResult hitResult;

        if (useAutoAim) {
            BlockUtils.PlacementHit placementHit = pickSafetyPlacement(ranked);
            if (placementHit == null) {
                return;
            }
            if (!isReadyToPlace(placementHit)) {
                return;
            }
            hitResult = placementHit.hitResult();
        } else {
            BlockHitResult playerHit = currentBlockHit();
            if (playerHit == null) {
                return;
            }
            BlockPos lookedAt = playerHit.getBlockPos();
            if (lookedAt.equals(activeAnchorPos)) {
                return;
            }

            BlockPos placement = lookedAt.offset(playerHit.getSide());
            if (placement.equals(activeAnchorPos)) {
                return;
            }
            if (!BlockUtils.isValidPlacement(placement)) {
                return;
            }
            if (BlockUtils.collidesWithPlayer(placement)) {
                return;
            }
            if (BlockUtils.collidesWithBlockingEntity(placement)) {
                return;
            }
            hitResult = playerHit;
        }

        int safetySlot = findSafetyBlockSlot();
        if (safetySlot == -1) {
            advanceToChargeStep();
            return;
        }

        if (!SwapStateManager.swapToIfNeeded(this, safetySlot, false, -1, false)) {
            return;
        }
        if (placeCooldown > 0) {
            return;
        }
        if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
            return;
        }

        ActionResult result = BlockUtils.interactWithBlock(hitResult, true);
        placeCooldown = VANILLA_PLACE_COOLDOWN_TICKS;
        if (result.isAccepted()) {
            rotation.markInteraction(silentRotationActive);
            advanceToChargeStep();
        }
    }

    private boolean shouldPlaceSafety(List<BlockPos> ranked) {
        if (!safeAnchor.isEnabled() || mc.player == null || mc.world == null || activeAnchorPos == null) {
            return false;
        }

        // Below the damage threshold the explosion isn't worth a glowstone.
        float threshold = minDamage.getFValue();
        if (threshold > 0.0f && DamageUtils.anchorDamage(mc.player, Vec3d.ofCenter(activeAnchorPos)) < threshold) {
            return false;
        }

        // If the closest-to-anchor neighbor is already a solid block, we're already covered.
        BlockPos ideal = ranked.isEmpty() ? null : ranked.get(0);
        return ideal == null || !hasExistingSafetyBlock(ideal);
    }

    /**
     * 8 horizontal neighbors of the player's feet, restricted to the half-plane
     * toward the anchor (so the safety can never end up "to the right" or
     * behind us), sorted by distance to the anchor center -- the spot that
     * sits most directly between us and the anchor comes first.
     */
    private List<BlockPos> rankedSafetyCandidates() {
        List<BlockPos> ordered = new ArrayList<>(8);
        if (mc.player == null || activeAnchorPos == null) {
            return ordered;
        }

        float partialTick = Template.getTickDelta();
        BlockPos feet = BlockPos.ofFloored(mc.player.getLerpedPos(partialTick));
        Vec3d feetCenter = Vec3d.ofCenter(feet);
        Vec3d anchorCenter = Vec3d.ofCenter(activeAnchorPos);
        Vec3d toAnchor = anchorCenter.subtract(feetCenter);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos pos = feet.add(dx, 0, dz);
                Vec3d toCand = Vec3d.ofCenter(pos).subtract(feetCenter);
                if (toCand.x * toAnchor.x + toCand.z * toAnchor.z <= 0) {
                    continue; // behind / perpendicular
                }
                ordered.add(pos);
            }
        }
        ordered.sort(Comparator.comparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(anchorCenter)));
        return ordered;
    }

    private BlockUtils.PlacementHit pickSafetyPlacement(List<BlockPos> ranked) {
        for (BlockPos pos : ranked) {
            BlockUtils.PlacementHit hit = trySafetyPlacementAt(pos);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private BlockUtils.PlacementHit trySafetyPlacementAt(BlockPos pos) {
        if (pos == null || mc.world == null || mc.player == null) {
            return null;
        }
        if (pos.equals(activeAnchorPos)) {
            return null;
        }
        if (!BlockUtils.isValidPlacement(pos)) {
            return null;
        }
        if (!BlockUtils.hasSupportingFace(pos)) {
            return null;
        }
        if (BlockUtils.collidesWithPlayer(pos)) {
            return null;
        }
        if (BlockUtils.collidesWithBlockingEntity(pos)) {
            return null;
        }

        BlockUtils.PlacementHit hit = BlockUtils.findPlacementHit(pos, canPlaceLegit.isEnabled());
        if (hit == null) {
            return null;
        }

        Vec3d eye = mc.player.getEyePos();
        double range = mc.player.getBlockInteractionRange();
        if (eye.squaredDistanceTo(hit.hitResult().getPos()) > range * range) {
            return null;
        }
        return hit;
    }

    private boolean hasExistingSafetyBlock(BlockPos pos) {
        if (mc.world == null || pos == null) {
            return false;
        }
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !state.isReplaceable();
    }

    private int findSafetyBlockSlot() {
        int slot = InvUtils.getItemSlot(Items.GLOWSTONE);
        if (slot != -1) {
            return slot;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem && !stack.isOf(Items.RESPAWN_ANCHOR)) {
                return i;
            }
        }
        return -1;
    }

    // -- Step: CHARGE --

    private void chargeActiveAnchor() {
        int currentCharge = getAnchorCharge(activeAnchorPos);
        if (remainingCharges <= 0 || currentCharge >= MAX_ANCHOR_CHARGES) {
            beginExplodeStepOrFinish();
            return;
        }

        int glowstoneSlot = InvUtils.getItemSlot(Items.GLOWSTONE);
        if (glowstoneSlot == -1) {
            remainingCharges = 0;
            beginExplodeStepOrFinish();
            return;
        }

        if (!SwapStateManager.swapToIfNeeded(this, glowstoneSlot, false, -1, false)) {
            return;
        }
        if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
            return;
        }

        BlockHitResult hitResult = getReadyAnchorHit(activeAnchorPos);
        if (hitResult == null) {
            return;
        }

        ActionResult result = BlockUtils.interactWithBlock(hitResult, true);
        if (!result.isAccepted()) {
            return;
        }

        rotation.markInteraction(silentRotationActive);
        remainingCharges--;
        if (remainingCharges <= 0) {
            anchorStep = AnchorStep.EXPLODE;
            nextAnchorActionTick = mc.player.age + rollTickDelay(explodeDelay);
            return;
        }
        nextAnchorActionTick = mc.player.age + rollTickDelay(chargeDelay);
    }

    // -- Step: EXPLODE --

    private void explodeActiveAnchor() {
        int currentCharge = getAnchorCharge(activeAnchorPos);
        if (currentCharge <= 0) {
            finishAnchorSequence();
            return;
        }

        int targetSlot = explodeSlot.getIValue() - 1;
        if (!SwapStateManager.swapToIfNeeded(this, targetSlot, false, -1, false)) {
            return;
        }

        // Glowstone in the explode slot would just charge again; bail unless the anchor is already maxed.
        ItemStack explodeStack = mc.player.getInventory().getStack(targetSlot);
        if (explodeStack.isOf(Items.GLOWSTONE) && currentCharge < MAX_ANCHOR_CHARGES) {
            finishAnchorSequence();
            return;
        }

        BlockHitResult hitResult = getReadyAnchorHit(activeAnchorPos);
        if (hitResult == null) {
            return;
        }

        ActionResult result = BlockUtils.interactWithBlock(hitResult, true);
        if (!result.isAccepted()) {
            return;
        }

        rotation.markInteraction(silentRotationActive);
        placeCooldown = VANILLA_PLACE_COOLDOWN_TICKS;
        finishAnchorSequence();
    }

    // -- Aim picking --

    private Vec3d findActiveAimPoint() {
        if (isAnchorSequenceActive()) {
            if (anchorStep == AnchorStep.PLACE_SAFETY) {
                List<BlockPos> ranked = rankedSafetyCandidates();
                if (shouldPlaceSafety(ranked)) {
                    BlockUtils.PlacementHit safetyHit = pickSafetyPlacement(ranked);
                    if (safetyHit != null) {
                        return safetyHit.hitResult().getPos();
                    }
                }
            }
            return getAnchorAimPoint(activeAnchorPos);
        }

        BlockHitResult anchorHit = currentBlockHit();
        if (anchorHit != null && isRespawnAnchor(anchorHit.getBlockPos())) {
            return anchorHit.getPos();
        }

        BlockUtils.PlacementHit placementHit = findBestPlacementHit();
        if (placementHit != null) {
            return placementHit.hitResult().getPos();
        }

        BlockHitResult manualHit = currentBlockHit();
        return manualHit != null ? manualHit.getPos() : null;
    }

    private Vec3d getAnchorAimPoint(BlockPos anchorPos) {
        if (anchorPos == null) {
            return null;
        }
        if (mc.player == null) {
            return Vec3d.ofCenter(anchorPos);
        }

        float partialTick = Template.getTickDelta();
        // Reuse the cached aim if it still raycasts cleanly. A block placed
        // since the sequence started (e.g. our safety glowstone) can occlude
        // the original corner -- in that case re-pick.
        if (cachedAnchorAim != null && anchorPos.equals(activeAnchorPos)) {
            Vec3d eye = mc.player.getEyePos();
            if (AnchorAimSearch.canHitAnchor(eye, cachedAnchorAim, anchorPos)) {
                return cachedAnchorAim;
            }
            cachedAnchorAim = AnchorAimSearch.findClearAimPoint(anchorPos, partialTick);
            return cachedAnchorAim != null ? cachedAnchorAim : Vec3d.ofCenter(anchorPos);
        }
        Vec3d fresh = AnchorAimSearch.findClearAimPoint(anchorPos, partialTick);
        return fresh != null ? fresh : Vec3d.ofCenter(anchorPos);
    }

    /**
     * Rotates toward {@code point} with a per-frame convergence. Silent aiming
     * goes through the rotation manager (server-visible spoofed rotation),
     * visual aiming moves the real view; both share the same speed semantics
     * as the original ECHO implementation (silent 2.5x, visual 0.5x).
     */
    private boolean aimAt(Vec3d point) {
        if (mc.player == null || point == null) {
            return false;
        }

        boolean silent = silentAim.isEnabled();
        float speed = aimSpeed.getFValue();
        float dt = deltaSeconds();
        float stepYaw = speed * dt * (silent ? 2.5f : 0.5f);
        float stepPitch = stepYaw;

        float currentYaw = silent ? RotationConvergenceTracker.serverYaw() : mc.player.getYaw();
        float currentPitch = silent ? RotationConvergenceTracker.serverPitch() : mc.player.getPitch();

        Rotation target = RotationUtils.getRotations(mc.player.getEyePos(), point);
        Rotation limited = RotationUtils.getLimitedRotation(new Rotation(currentYaw, currentPitch), target, stepYaw, stepPitch);

        float yawDiff = Math.abs(MathHelper.wrapDegrees(target.fyaw() - limited.fyaw()));
        float pitchDiff = Math.abs(target.fpitch() - limited.fpitch());
        if (yawDiff < 0.5f && pitchDiff < 0.5f) {
            limited = RotationUtils.correctSensitivity(target);
        } else {
            limited = RotationUtils.correctSensitivity(limited);
        }

        if (silent) {
            Template.rotationManager().setRotation(limited);
            silentRotationActive = true;
        } else {
            mc.player.setYaw(limited.fyaw());
            mc.player.setPitch(limited.fpitch());
            silentRotationActive = false;
        }
        return true;
    }

    private boolean isReadyToPlace(BlockUtils.PlacementHit placementHit) {
        float yaw = effectiveYaw();
        float pitch = effectivePitch();

        // Grim's DuplicateRotPlace compares pitch delta against the previous
        // place packet. Falling changes the needed pitch every tick, so allow
        // those non-duplicate deltas instead of waiting until we land.
        if (!rotation.isDuplicateRotPlaceSafe(ROTATION_SETTLE_THRESHOLD, silentRotationActive)) {
            return false;
        }

        if (!canPlaceLegit.isEnabled()) {
            return rayIntersectsBlock(yaw, pitch, placementHit.hitResult().getBlockPos());
        }

        BlockHitResult raycast = raycastFromRotation(yaw, pitch);
        if (raycast == null || raycast.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (!raycast.getBlockPos().equals(placementHit.hitResult().getBlockPos())) {
            return false;
        }
        return raycast.getSide() == placementHit.hitResult().getSide();
    }

    private BlockHitResult getReadyAnchorHit(BlockPos anchorPos) {
        if (!isAnchorWithinInteractionRange(anchorPos)) {
            return null;
        }

        // Grim's DuplicateRotPlace compares pitch delta against the previous
        // place packet. Falling changes the needed pitch every tick, so allow
        // those non-duplicate deltas instead of waiting until we land.
        if (!rotation.isDuplicateRotPlaceSafe(ROTATION_SETTLE_THRESHOLD, silentRotationActive)) {
            return null;
        }

        float yaw = effectiveYaw();
        float pitch = effectivePitch();

        if (!canPlaceLegit.isEnabled()) {
            if (!rayIntersectsBlock(yaw, pitch, anchorPos)) {
                return null;
            }
            return new BlockHitResult(getAnchorAimPoint(anchorPos), Direction.UP, anchorPos, false);
        }

        BlockHitResult currentHit = currentBlockHit();
        if (currentHit != null && currentHit.getBlockPos().equals(anchorPos)) {
            return currentHit;
        }

        BlockHitResult raycast = raycastFromRotation(yaw, pitch);
        if (raycast == null || raycast.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        if (!raycast.getBlockPos().equals(anchorPos)) {
            return null;
        }
        return raycast;
    }

    /** Casts an OUTLINE ray from the player's eye in the given direction out to vanilla block-interaction range. */
    private BlockHitResult raycastFromRotation(float yaw, float pitch) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = RotationUtils.getPlayerLookVec(yaw, pitch);
        Vec3d end = eye.add(look.multiply(mc.player.getBlockInteractionRange()));
        return mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    /** True if a ray from the eye at the given rotation passes through (a slightly inflated) {@code clickedPos}. */
    private boolean rayIntersectsBlock(float yaw, float pitch, BlockPos clickedPos) {
        if (mc.player == null || clickedPos == null) {
            return false;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = RotationUtils.getPlayerLookVec(yaw, pitch);
        Vec3d end = eye.add(look.multiply(mc.player.getBlockInteractionRange()));
        return new Box(clickedPos).expand(1.0E-4).raycast(eye, end).isPresent();
    }

    // -- Best-placement search (anchor placement candidate) --

    private BlockUtils.PlacementHit findBestPlacementHit() {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3d playerEye = mc.player.getEyePos();
        LivingEntity target = PlayerUtils.findFirstLivingTargetOrNull(false);
        if (target == null) {
            return null;
        }

        double blockRange = mc.player.getBlockInteractionRange();
        double blockRangeSq = blockRange * blockRange;

        float partialTick = Template.getTickDelta();
        Vec3d targetPos = target.getLerpedPos(partialTick);
        Vec3d offset = targetPos.subtract(target.getPos());
        Box targetBox = target.getBoundingBox().offset(offset);
        Vec3d targetFeet = new Vec3d(targetPos.x, targetBox.minY, targetPos.z);

        int floorX = MathHelper.floor(targetFeet.x);
        int floorY = MathHelper.floor(targetFeet.y);
        int floorZ = MathHelper.floor(targetFeet.z);

        BlockUtils.PlacementHit bestHit = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(floorX + dx, floorY + dy, floorZ + dz);

                    if (!BlockUtils.isValidPlacement(pos)) {
                        continue;
                    }
                    if (!BlockUtils.hasSupportingFace(pos)) {
                        continue;
                    }

                    BlockUtils.PlacementHit hit = BlockUtils.findPlacementHit(pos, canPlaceLegit.isEnabled());
                    if (hit == null) {
                        continue;
                    }
                    if (BlockUtils.collidesWithPlayer(pos)) {
                        continue;
                    }
                    if (BlockUtils.hasBlockingEntity(new Box(pos))) {
                        continue;
                    }
                    if (playerEye.squaredDistanceTo(hit.hitResult().getPos()) > blockRangeSq) {
                        continue;
                    }

                    Vec3d blockCenter = Vec3d.ofCenter(pos);
                    float exposure = ExplosionImpl.getExposure(blockCenter, target);
                    double feetDistSq = targetFeet.squaredDistanceTo(blockCenter);
                    // Prefer high-exposure hits; break ties by proximity to feet.
                    double score = (1.0 - exposure) * 100.0 + feetDistSq * 0.01;

                    if (score < bestScore) {
                        bestScore = score;
                        bestHit = hit;
                    }
                }
            }
        }
        return bestHit;
    }

    // -- Misc helpers --

    private float deltaSeconds() {
        long now = System.nanoTime();
        float delta = lastNanoTime == 0 ? 0.05f : (now - lastNanoTime) / 1_000_000_000.0f;
        lastNanoTime = now;
        return Math.min(delta, 0.1f);
    }

    private float effectiveYaw() {
        return silentRotationActive ? RotationConvergenceTracker.serverYaw() : mc.player.getYaw();
    }

    private float effectivePitch() {
        return silentRotationActive ? RotationConvergenceTracker.serverPitch() : mc.player.getPitch();
    }

    private boolean isActionActive() {
        if (mode.is(Mode.Hold)) {
            if (!KeyUtils.isKeyPressed(activateKey.getCode())) {
                return false;
            }
            return repeat.isEnabled() || !holdCycleCompleted || isAnchorSequenceActive();
        }
        return clickQueued || isAnchorSequenceActive();
    }

    private boolean shouldHandleSameUseKey() {
        if (mc.options == null || mc.options.useKey == null) {
            return false;
        }
        return activateKey.getCode() != -1 && activateKey.getCode() == mc.options.useKey.boundKey.getCode();
    }

    private boolean isHoldingRespawnAnchor() {
        return mc.player != null && mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR);
    }

    private BlockHitResult currentBlockHit() {
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        return null;
    }

    private BlockPos consumePendingUseKeyAnchorPos() {
        BlockPos pos = pendingUseKeyAnchorPos;
        pendingUseKeyAnchorPos = null;
        if (pos == null) {
            return null;
        }
        return isRespawnAnchor(pos) ? pos : null;
    }

    private BlockPos anchorPosOf(HitResult hitResult) {
        if (!(hitResult instanceof BlockHitResult blockHit)) {
            return null;
        }
        if (blockHit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return isRespawnAnchor(blockHit.getBlockPos()) ? blockHit.getBlockPos() : null;
    }

    private BlockPos placedAnchorPosOf(BlockHitResult hitResult) {
        return hitResult == null ? null : hitResult.getBlockPos().offset(hitResult.getSide());
    }

    private boolean isManualAnchorPlacementValid(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (!BlockUtils.isValidPlacement(pos)) {
            return false;
        }
        if (BlockUtils.collidesWithPlayer(pos)) {
            return false;
        }
        return !BlockUtils.collidesWithBlockingEntity(pos);
    }

    private boolean isRespawnAnchor(BlockPos pos) {
        if (pos == null || mc.world == null) {
            return false;
        }
        return mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    private int getAnchorCharge(BlockPos pos) {
        if (!isRespawnAnchor(pos)) {
            return 0;
        }
        return mc.world.getBlockState(pos).get(RespawnAnchorBlock.CHARGES);
    }

    private boolean isAnchorWithinInteractionRange(BlockPos anchorPos) {
        if (mc.player == null || anchorPos == null) {
            return false;
        }
        double range = mc.player.getBlockInteractionRange();
        return mc.player.squaredDistanceTo(Vec3d.ofCenter(anchorPos)) <= MathHelper.square(range + 1.0);
    }

    private void handleInactive() {
        resetAnchorSequence();
        if (SwapStateManager.isOwnerActive(this) && !restoringSwap) {
            scheduleSwapBack(1);
        }
        disengageRotations();
    }

    private void scheduleSwapBack(int delayTicks) {
        if (!SwapStateManager.isOwnerActive(this)) {
            return;
        }
        int activeSlot = SwapStateManager.getActiveTargetSlot(this);
        if (activeSlot == -1) {
            SwapStateManager.cancel(this, true);
            restoringSwap = false;
            return;
        }
        SwapStateManager.swapToIfNeeded(this, activeSlot, false, delayTicks, true);
        restoringSwap = true;
    }

    private void disengageRotations() {
        // Snap the spoofed rotation back to the real view so the server never
        // keeps seeing a stale aim after we stop controlling it.
        if (silentRotationActive && Template.rotationManager() != null && mc.player != null) {
            Template.rotationManager().setRotation(new Rotation(mc.player.getYaw(), mc.player.getPitch()));
        }
        silentRotationActive = false;
        wasAiming = false;
    }

    private void disableSiblingAnchorModules() {
        if (Template.moduleManager == null) {
            return;
        }
        AutoAnchorRewriteModule autoAnchor = Template.moduleManager.getModule(AutoAnchorRewriteModule.class);
        if (autoAnchor != null && autoAnchor.isEnabled()) {
            autoAnchor.setEnabled(false);
        }
        SmartAutoAnchorModule smartAnchor = Template.moduleManager.getModule(SmartAutoAnchorModule.class);
        if (smartAnchor != null && smartAnchor.isEnabled()) {
            smartAnchor.setEnabled(false);
        }
    }

    // Random rolls -- delegate to the randomized range settings and clamp.
    private int rollChargeCount() {
        return MathHelper.clamp(Math.round((float) charges.getRandomDouble()), 1, MAX_ANCHOR_CHARGES);
    }

    private int rollTickDelay(MinMaxNumberSetting setting) {
        return Math.max(0, Math.round((float) setting.getRandomDouble()));
    }

    @Override
    public String getSuffix() {
        if (anchorStep == null) {
            return "";
        }
        return switch (anchorStep) {
            case PLACE_SAFETY -> " Safe";
            case CHARGE -> " Charging";
            case EXPLODE -> " Exploding";
        };
    }

    private void drawBoxOutline(Box box, Color color, WorldRenderContext context) {
        Vec3d min = new Vec3d(box.minX, box.minY, box.minZ);
        Vec3d max = new Vec3d(box.maxX, box.maxY, box.maxZ);
        Vec3d[] corners = {
                new Vec3d(min.x, min.y, min.z), new Vec3d(max.x, min.y, min.z),
                new Vec3d(max.x, min.y, max.z), new Vec3d(min.x, min.y, max.z),
                new Vec3d(min.x, max.y, min.z), new Vec3d(max.x, max.y, min.z),
                new Vec3d(max.x, max.y, max.z), new Vec3d(min.x, max.y, max.z)
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            RenderUtils.Render3D.renderLineTo(corners[edge[0]], corners[edge[1]], color, 1.5f, context);
        }
    }
}
