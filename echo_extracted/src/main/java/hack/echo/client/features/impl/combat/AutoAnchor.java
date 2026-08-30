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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
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
 * <p>The player places the initial anchor and keeps right click held. The
 * module switches to glowstone and charges the anchor while the crosshair is
 * on the anchor. Whenever the crosshair is on any other valid spot, the module
 * places glowstone there opportunistically - this is the fail-safe: if the
 * player moves too fast and the anchor cannot be charged yet, the glowstone is
 * still put down at whatever placeable spot was crossed, so coming back to the
 * anchor charges and detonates with the safe block already in place. Only one
 * glowstone is placed per cycle and only within the configured max distance of
 * the anchor. There is no angle threshold: the aim state itself decides.
 * Detonation happens when the crosshair is back on the (charged) anchor, with
 * a totem when available or a configurable fallback hotbar slot. The charge
 * and detonation clicks are sent back to back without waiting for client-side
 * confirmation, so spam runs as fast as the server accepts it.</p>
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
        DECIDE,
        SWITCH_DETONATOR,
        WAIT_ANCHOR_AIM,
        FINISH
    }

    private static final CharSequence SERVER_TIMING_STRICT = Concat.of("Strict");
    private static final CharSequence SERVER_TIMING_FAST = Concat.of("Fast");

    public AutoAnchor() {
        super(new FeatureInfo(
                Concat.of("Auto Anchor"),
                Concat.of("Aim-based anchor cycling with fail-safe glowstone placement"),
                Category.COMBAT
        ));
    }

    public final ModeSetting serverTiming = new ModeSetting(
            Concat.of("Server Timing"),
            SERVER_TIMING_FAST,
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
    public final IntSetting safeMaxDistance = new IntSetting(
            Concat.of("Safe Max Distance"),
            4, 1, 10,
            Concat.of(" blocks")
    );
    public final IntSetting decisionWait = new IntSetting(
            Concat.of("Decision Wait"),
            3, 0, 20,
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

    private int pendingOriginalSlot = -1;
    private int originalAnchorSlot = -1;
    private int stageTicks;
    private int slotCooldown;
    private int actionCooldown;
    private int serverActionCooldown;

    private boolean safePath;
    private boolean suppressUseUntilRelease;
    private boolean interactionPerformedThisTick;
    private boolean slotChangedThisTick;
    private int decisionTicks;

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
            case CHARGE_ANCHOR -> safePath ? "Safe" : "Charge";
            case DECIDE -> "Decide";
            case SWITCH_DETONATOR, WAIT_ANCHOR_AIM, FINISH -> safePath ? "Safe" : "Normal";
            default -> "Active";
        };
    }

    /**
     * Captures the intended anchor position before vanilla consumes the item.
     * Also handles already-placed anchors: clicking an existing anchor cancels
     * the vanilla interaction (which would instantly charge/detonate it) and
     * starts a module cycle on it instead.
     */
    @EventSubscribe
    private void onInteractBlockPre(EventPerformUseItemOn.Pre event) {
        pendingInitialPlacement = false;

        if (stage != Stage.IDLE
                || suppressUseUntilRelease
                || event.getHand() != InteractionHand.MAIN_HAND
                || !isUseHeld()
                || isNull()
                || hack.echo.client.api.MinecraftCompat.getScreen() != null) {
            return;
        }

        BlockHitResult hit = event.getHitResult();
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos clickedPos = hit.getBlockPos();
        BlockState clickedState = mc.level.getBlockState(clickedPos);

        if (clickedState.is(Blocks.RESPAWN_ANCHOR)) {
            // Already-placed anchor: cancel the vanilla interaction and run a
            // full module cycle on it (charge if needed, decide, detonate).
            event.cancel();
            pendingPrimaryCandidate = clickedPos;
            pendingSecondaryCandidate = null;
            pendingOriginalSlot = mc.player.getInventory().getSelectedSlot();
            pendingInitialPlacement = true;
            beginCycle();
            return;
        }

        if (!mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
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

        if (isNull() || hack.echo.client.api.MinecraftCompat.getScreen() != null || !isUseHeld()) {
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
            case DECIDE -> tickDecide();
            case SWITCH_DETONATOR -> tickSwitchDetonator();
            case WAIT_ANCHOR_AIM -> tickWaitAnchorAim();
            case FINISH -> tickFinish();
            case IDLE -> {
                // Handled by onTick.
            }
        }
    }

    private void beginCycle() {
        anchorCandidatePrimary = pendingPrimaryCandidate;
        anchorCandidateSecondary = pendingSecondaryCandidate;
        originalAnchorSlot = pendingOriginalSlot;
        anchorPos = null;
        safePath = false;
        decisionTicks = 0;
        slotCooldown = randomRange(slotDelays);
        actionCooldown = chargeDelay.getValue();
        ((KeyMappingAccessor) mc.options.keyUse).setClickCount(0);
        clearPendingPlacement();

        // An already-charged anchor needs no glowstone: go through the
        // decision wait and straight to detonation. Otherwise glowstone is
        // required to charge.
        if (anchorAt(anchorCandidatePrimary) && BlockUtils.isRespawnAnchorCharged(anchorCandidatePrimary)) {
            decisionTicks = decisionWait.getValue();
            transition(Stage.DECIDE);
            return;
        }
        if (getGlowstoneSlot() == -1) {
            suppressUseUntilRelease = isUseHeld();
            clearPendingPlacement();
            return;
        }
        transition(Stage.WAIT_ANCHOR);
    }

    private void tickWaitAnchor() {
        anchorPos = findPlacedAnchor();
        if (anchorPos != null) {
            if (isAnchorCharged()) {
                decisionTicks = decisionWait.getValue();
                transition(Stage.DECIDE);
                return;
            }

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

    /**
     * The core aim-state loop. Aiming at the anchor charges it; once charged it
     * hands off to the detonation path. Aiming at any other valid spot places
     * glowstone there opportunistically (fail-safe), including before the
     * anchor is charged, so fast movement cannot waste a cycle: the safe block
     * is already down by the time the player comes back to the anchor. Only
     * one glowstone is placed per cycle.
     */
    private void tickChargeAnchor() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }
        if (isAnchorCharged()) {
            decisionTicks = decisionWait.getValue();
            transition(Stage.DECIDE);
            return;
        }
        if (getGlowstoneSlot() == -1) {
            cancelCycle();
            return;
        }
        if (!mc.player.getMainHandItem().is(Items.GLOWSTONE)) {
            transition(Stage.SWITCH_GLOWSTONE);
            return;
        }

        BlockHitResult hit = currentAnchorHit();
        if (hit != null) {
            if (actionCooldown > 0) {
                actionCooldown--;
                return;
            }

            // Send the charge click and open the decision window. The charge
            // packet is sent before any detonation packet, so the server
            // processes them in order. If the charge never landed, the FINISH
            // stage detects the uncharged anchor and re-charges.
            if (interact(hit)) {
                decisionTicks = decisionWait.getValue();
                transition(Stage.DECIDE);
            } else {
                actionCooldown = chargeDelay.getValue();
            }
            return;
        }

        // Not aiming at the anchor: opportunistic fail-safe / deliberate safe
        // glowstone placement. Only once per cycle, only on a valid spot
        // within max distance, and only if enough glowstone remains to still
        // charge the anchor afterwards.
        if (safePath) {
            return;
        }
        BlockHitResult blockHit = currentBlockHit();
        BlockPos placementPos = validSafePlacement(blockHit);
        if (placementPos == null) {
            return;
        }
        if (hotbarGlowstoneCount() < 2) {
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        if (interact(blockHit)) {
            safePath = true;
        }
        actionCooldown = randomRange(safePlaceDelays);
    }

    /**
     * Post-charge decision window. Waits the configured amount of ticks, then:
     * crosshair still on the anchor -> normal detonation; crosshair on a valid
     * glowstone spot -> place one glowstone there, then detonate when the
     * crosshair returns to the anchor; anywhere else -> normal detonation.
     */
    private void tickDecide() {
        if (!anchorExists()) {
            cancelCycle();
            return;
        }
        if (isAnchorCharged() && decisionTicks > 0) {
            decisionTicks--;
            return;
        }
        if (!isAnchorCharged()) {
            // The charge click hasn't landed yet. Give it a grace period,
            // then go back and re-charge instead of waiting forever.
            if (++stageTicks > confirmationTimeout.getValue()) {
                transition(Stage.CHARGE_ANCHOR);
            }
            return;
        }

        // Decision time.
        if (currentAnchorHit() != null) {
            transition(Stage.SWITCH_DETONATOR);
            return;
        }

        if (!safePath) {
            BlockHitResult blockHit = currentBlockHit();
            BlockPos placementPos = validSafePlacement(blockHit);
            if (placementPos != null && hotbarGlowstoneCount() >= 2) {
                if (actionCooldown > 0) {
                    actionCooldown--;
                    return;
                }
                if (interact(blockHit)) {
                    safePath = true;
                }
                actionCooldown = randomRange(safePlaceDelays);
                return;
            }
        }

        transition(Stage.SWITCH_DETONATOR);
    }

    private void tickSwitchDetonator() {
        if (!anchorExists()) {
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
        if (!anchorExists()) {
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
        if (!anchorExists()) {
            // Detonation went through: the anchor is gone. Leave the chosen
            // detonator selected and do not suppress held RMB, so the next
            // click can immediately start a new cycle.
            resetCycle(false, false);
            return;
        }
        if (!isAnchorCharged()) {
            // Anchor still there but uncharged: the charge click never landed
            // server-side. Go back and charge properly instead of leaving a
            // dud anchor behind.
            transition(Stage.SWITCH_GLOWSTONE);
            return;
        }
        if (++stageTicks >= 10) {
            resetCycle(true, false);
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
     * exact glowstone position. The anchor itself and anything beyond
     * {@link #safeMaxDistance} blocks from it are rejected.
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

        int maxDistance = safeMaxDistance.getValue();
        double dx = placementPos.getX() - anchorPos.getX();
        double dy = placementPos.getY() - anchorPos.getY();
        double dz = placementPos.getZ() - anchorPos.getZ();
        if (dx * dx + dy * dy + dz * dz > (double) maxDistance * maxDistance) {
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

    /**
     * Counts glowstone reachable through the hotbar, matching
     * {@link #getGlowstoneSlot()} semantics. Used so the fail-safe placement
     * never spends the last glowstone and leaves nothing to charge with.
     */
    private int hotbarGlowstoneCount() {
        int count = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(Items.GLOWSTONE)) {
                count += stack.getCount();
            }
        }
        return count;
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
        if (!context.getLevel().isInsideBuildHeight(pos.getY())) return false;
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
        decisionTicks = 0;
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
