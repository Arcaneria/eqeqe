package template.rip.module.modules.crystal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;
import template.rip.Template;
import template.rip.api.event.events.AttackEvent;
import template.rip.api.event.events.ItemUseEvent;
import template.rip.api.event.events.TickEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.event.orbit.EventPriority;
import template.rip.api.object.Description;
import template.rip.api.util.BlockUtils;
import template.rip.api.util.InvUtils;
import template.rip.api.util.KeyUtils;
import template.rip.api.util.MouseSimulation;
import template.rip.api.util.PlayerUtils;
import template.rip.module.Module;
import template.rip.module.setting.settings.BooleanSetting;
import template.rip.module.setting.settings.DividerSetting;
import template.rip.module.setting.settings.KeybindSetting;
import template.rip.module.setting.settings.NumberSetting;
import template.rip.module.setting.settings.RegistrySetting;

import java.util.Collections;

/**
 * Crosshair-driven Auto Crystal port adapted from ECHO b0.0.6.
 *
 * <p>When Place Obsidian is enabled, holding the activation key over a valid
 * block face switches to obsidian, places and confirms the base, switches to an
 * end crystal, then continues ECHO's place/break loop. All actions use the
 * current crosshair and Stegered Client's event, setting, and config systems.</p>
 */
public final class EchoAutoCrystalModule extends Module {

    private static final int MAX_STAGE_TRANSITIONS_PER_TICK = 8;

    private enum Stage {
        IDLE,
        SWITCH_OBSIDIAN,
        PLACE_OBSIDIAN,
        CONFIRM_OBSIDIAN,
        SWITCH_CRYSTAL,
        ACTIVE
    }

    public final DividerSetting crystalDivider = new DividerSetting(this, false, "Crystal Timing");
    public final NumberSetting placeDelay = new NumberSetting(
            this,
            Description.of("Ticks between crystal placements."),
            1,
            0,
            20,
            1,
            "Place Delay"
    );
    public final NumberSetting breakDelay = new NumberSetting(
            this,
            Description.of("Ticks between crystal attacks."),
            1,
            0,
            20,
            1,
            "Break Delay"
    );
    public final NumberSetting failChance = new NumberSetting(
            this,
            Description.of("Percentage chance to skip a ready place or break action."),
            0,
            0,
            100,
            1,
            "Fail Chance"
    );
    public final BooleanSetting inputSimulation = new BooleanSetting(
            this,
            Description.of("Emits Stegered click-simulation feedback when Click Simulation is enabled."),
            false,
            "Input Simulation"
    );
    public final BooleanSetting stopOnKill = new BooleanSetting(
            this,
            Description.of("Pauses while a dead player is within six blocks."),
            false,
            "Stop On Kill"
    );
    public final BooleanSetting removeCrystals = new BooleanSetting(
            this,
            Description.of("Removes a crystal client-side the moment you attack it, so leftover hitboxes never block fast re-placement while spamming."),
            true,
            "Crystal Optimizer"
    );

    public final DividerSetting obsidianDivider = new DividerSetting(this, false, "Place Obsidian");
    public final BooleanSetting placeObsidian = new BooleanSetting(
            this,
            Description.of("Places an obsidian base before starting the crystal loop when needed."),
            false,
            "Place Obsidian"
    );
    public final NumberSetting switchDelay = new NumberSetting(
            this,
            Description.of("Ticks before automatic obsidian/crystal hotbar switches."),
            1,
            0,
            10,
            1,
            "Switch Delay"
    );
    public final NumberSetting placeObsidianDelay = new NumberSetting(
            this,
            Description.of("Ticks before placing the obsidian base."),
            1,
            0,
            10,
            1,
            "Place Obsidian Delay"
    );
    public final BooleanSetting switchBack = new BooleanSetting(
            this,
            Description.of("Restores the slot held when the activation key is released."),
            true,
            "Switch Back"
    );
    public final NumberSetting confirmationTimeout = new NumberSetting(
            this,
            Description.of("Ticks to wait for the obsidian placement to appear client-side before retrying."),
            20,
            5,
            100,
            1,
            "Confirmation Timeout"
    ).setAdvanced();

    public final RegistrySetting<Item> activationItems = new RegistrySetting<>(
            Collections.singletonList(Items.END_CRYSTAL),
            this,
            Registries.ITEM,
            "Activation Items"
    );
    public final KeybindSetting activateKey = new KeybindSetting(
            this,
            GLFW.GLFW_MOUSE_BUTTON_2,
            false,
            Description.of("Hold this key to run ECHO Auto Crystal."),
            "Activate Key"
    );

    private Stage stage = Stage.IDLE;
    private int originalSlot = -1;
    private int switchCooldown;
    private int obsidianCooldown;
    private int placeCooldown;
    private int breakCooldown;
    private int confirmationTicks;
    private BlockPos expectedObsidianPos;
    private boolean interactionPerformedThisTick;
    private boolean wasActivationPressed;

    public EchoAutoCrystalModule(Category category, Description description, String name) {
        super(category, description, name);
        crystalDivider.addSetting(placeDelay, breakDelay, failChance, inputSimulation, stopOnKill, removeCrystals);
        obsidianDivider.addSetting(placeObsidian, switchDelay, placeObsidianDelay, switchBack, confirmationTimeout);
    }

    @Override
    public void onEnable() {
        reset(false);
        disableConflictingCrystalModules();
    }

    @Override
    public void onDisable() {
        reset(true);
    }

    @Override
    public String getSuffix() {
        return switch (stage) {
            case SWITCH_OBSIDIAN, PLACE_OBSIDIAN, CONFIRM_OBSIDIAN -> " Obsidian";
            case SWITCH_CRYSTAL, ACTIVE -> " Active";
            case IDLE -> "";
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        disableConflictingCrystalModules();
        interactionPerformedThisTick = false;

        if (!nullCheck() || mc.currentScreen != null) {
            reset(true);
            return;
        }

        if (placeCooldown > 0) {
            placeCooldown--;
        }
        if (breakCooldown > 0) {
            breakCooldown--;
        }

        boolean pressed = KeyUtils.isKeyPressed(activateKey.getCode());
        if (!pressed) {
            if (wasActivationPressed || stage != Stage.IDLE) {
                reset(true);
            }
            wasActivationPressed = false;
            return;
        }
        wasActivationPressed = true;

        if (stopOnKill.isEnabled() && isDeadPlayerNearby()) {
            return;
        }

        if (stage == Stage.IDLE) {
            if (!activationItems.selected.contains(mc.player.getMainHandStack().getItem())) {
                return;
            }
            beginCycle();
            if (stage == Stage.IDLE) {
                return;
            }
        }

        for (int transitions = 0; transitions < MAX_STAGE_TRANSITIONS_PER_TICK && stage != Stage.IDLE; transitions++) {
            Stage previous = stage;
            tickCurrentStage();
            if (stage == previous || interactionPerformedThisTick) {
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onItemUse(ItemUseEvent.Pre event) {
        if (stage != Stage.IDLE && KeyUtils.isKeyPressed(activateKey.getCode())) {
            event.cancel();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onAttack(AttackEvent.Pre event) {
        if (stage == Stage.IDLE || !KeyUtils.isKeyPressed(activateKey.getCode())) {
            return;
        }
        if (mc.crosshairTarget instanceof BlockHitResult hit && isCrystalBase(hit.getBlockPos())) {
            event.cancel();
        }
    }

    private void beginCycle() {
        if (!hasCrystalAvailable()) {
            return;
        }

        originalSlot = mc.player.getInventory().selectedSlot;
        switchCooldown = switchDelay.getIValue();
        obsidianCooldown = placeObsidianDelay.getIValue();
        expectedObsidianPos = null;
        confirmationTicks = 0;

        if (!placeObsidian.isEnabled()) {
            stage = Stage.ACTIVE;
            return;
        }

        BlockHitResult hit = currentBlockHit();
        if (hit == null) {
            stage = Stage.ACTIVE;
            return;
        }
        if (isCrystalBase(hit.getBlockPos())) {
            stage = Stage.SWITCH_CRYSTAL;
            return;
        }
        if (InvUtils.getItemSlot(Items.OBSIDIAN) == -1 || resolveObsidianPlacement(hit) == null) {
            return;
        }
        stage = Stage.SWITCH_OBSIDIAN;
    }

    private void tickCurrentStage() {
        switch (stage) {
            case SWITCH_OBSIDIAN -> tickSwitchObsidian();
            case PLACE_OBSIDIAN -> tickPlaceObsidian();
            case CONFIRM_OBSIDIAN -> tickConfirmObsidian();
            case SWITCH_CRYSTAL -> tickSwitchCrystal();
            case ACTIVE -> tickActive();
            case IDLE -> {
                // Handled before the state loop.
            }
        }
    }

    private void tickSwitchObsidian() {
        if (switchCooldown > 0) {
            switchCooldown--;
            return;
        }
        int slot = InvUtils.getItemSlot(Items.OBSIDIAN);
        if (slot == -1 || !switchToSlot(slot)) {
            reset(false);
            return;
        }
        stage = Stage.PLACE_OBSIDIAN;
    }

    private void tickPlaceObsidian() {
        if (obsidianCooldown > 0) {
            obsidianCooldown--;
            return;
        }

        BlockHitResult hit = currentBlockHit();
        if (hit == null) {
            return;
        }
        if (isCrystalBase(hit.getBlockPos())) {
            switchCooldown = switchDelay.getIValue();
            stage = Stage.SWITCH_CRYSTAL;
            return;
        }

        BlockPos placementPos = resolveObsidianPlacement(hit);
        if (placementPos == null) {
            return;
        }
        if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
            stage = Stage.SWITCH_OBSIDIAN;
            return;
        }

        expectedObsidianPos = placementPos;
        if (interactBlock(hit)) {
            confirmationTicks = 0;
            stage = Stage.CONFIRM_OBSIDIAN;
        } else {
            expectedObsidianPos = null;
            obsidianCooldown = placeObsidianDelay.getIValue();
        }
    }

    private void tickConfirmObsidian() {
        if (expectedObsidianPos != null && isCrystalBase(expectedObsidianPos)) {
            switchCooldown = switchDelay.getIValue();
            stage = Stage.SWITCH_CRYSTAL;
            return;
        }

        if (++confirmationTicks > confirmationTimeout.getIValue()) {
            expectedObsidianPos = null;
            obsidianCooldown = placeObsidianDelay.getIValue();
            stage = Stage.PLACE_OBSIDIAN;
        }
    }

    private void tickSwitchCrystal() {
        Hand crystalHand = InvUtils.handWithStack(Items.END_CRYSTAL);
        if (crystalHand == Hand.OFF_HAND) {
            stage = Stage.ACTIVE;
            return;
        }
        if (switchCooldown > 0) {
            switchCooldown--;
            return;
        }

        int crystalSlot = InvUtils.getItemSlot(Items.END_CRYSTAL);
        if (crystalSlot == -1 || !switchToSlot(crystalSlot)) {
            reset(false);
            return;
        }
        stage = Stage.ACTIVE;
    }

    private void tickActive() {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof EndCrystalEntity crystal) {
            breakCrystal(crystal);
            return;
        }
        if (hit instanceof BlockHitResult blockHit) {
            placeCrystal(blockHit);
        }
    }

    private void placeCrystal(BlockHitResult hit) {
        if (placeCooldown > 0 || !isCrystalBase(hit.getBlockPos()) || hasCrystalOnBase(hit.getBlockPos())) {
            return;
        }
        if (shouldFail()) {
            placeCooldown = placeDelay.getIValue();
            return;
        }

        Hand hand = InvUtils.handWithStack(Items.END_CRYSTAL);
        if (hand == null) {
            int crystalSlot = InvUtils.getItemSlot(Items.END_CRYSTAL);
            if (crystalSlot == -1 || !switchToSlot(crystalSlot)) {
                return;
            }
            hand = Hand.MAIN_HAND;
        }

        simulateUseFeedback();
        ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, hit);
        interactionPerformedThisTick = true;
        if (result.isAccepted() && PlayerUtils.shouldSwingHand(result)) {
            mc.player.swingHand(hand);
        }
        placeCooldown = placeDelay.getIValue();
    }

    private void breakCrystal(EndCrystalEntity crystal) {
        if (breakCooldown > 0 || crystal.isRemoved() || !crystal.isAlive()) {
            return;
        }
        if (shouldFail()) {
            breakCooldown = breakDelay.getIValue();
            return;
        }

        simulateAttackFeedback();
        mc.interactionManager.attackEntity(mc.player, crystal);
        mc.player.swingHand(Hand.MAIN_HAND);
        if (removeCrystals.isEnabled()) {
            mc.world.removeEntity(crystal.getId(), Entity.RemovalReason.DISCARDED);
        }
        interactionPerformedThisTick = true;
        breakCooldown = breakDelay.getIValue();
    }

    private BlockPos resolveObsidianPlacement(BlockHitResult hit) {
        if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem blockItem)
                || !mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
            // Resolve with a synthetic obsidian stack only after the module has
            // switched; before then, use vanilla's clicked-face rule.
            BlockState clickedState = mc.world.getBlockState(hit.getBlockPos());
            BlockPos fallback = clickedState.isReplaceable()
                    ? hit.getBlockPos()
                    : hit.getBlockPos().offset(hit.getSide());
            return canPlaceAt(fallback) ? fallback : null;
        }

        ItemPlacementContext context = new ItemPlacementContext(
                mc.player,
                Hand.MAIN_HAND,
                mc.player.getMainHandStack(),
                hit
        );
        BlockPos placementPos = context.getBlockPos();
        BlockState obsidianState = blockItem.getBlock().getPlacementState(context);
        if (!canPlaceAt(placementPos)
                || obsidianState == null
                || !blockItem.canPlace(context, obsidianState)) {
            return null;
        }
        return placementPos;
    }

    private boolean canPlaceAt(BlockPos pos) {
        return pos != null
                && mc.world.getBlockState(pos).isReplaceable()
                && !mc.player.getBoundingBox().intersects(new Box(pos));
    }

    private boolean interactBlock(BlockHitResult hit) {
        simulateUseFeedback();
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        interactionPerformedThisTick = true;
        if (result.isAccepted() && PlayerUtils.shouldSwingHand(result)) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        return result.isAccepted();
    }

    private boolean switchToSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        if (mc.player.getInventory().selectedSlot == slot) {
            return true;
        }
        mc.player.getInventory().selectedSlot = slot;
        mc.interactionManager.syncSelectedSlot();
        return true;
    }

    private boolean hasCrystalAvailable() {
        return InvUtils.getItemSlot(Items.END_CRYSTAL) != -1
                || mc.player.getOffHandStack().isOf(Items.END_CRYSTAL);
    }

    private boolean hasCrystalOnBase(BlockPos basePos) {
        Box box = new Box(basePos.up()).stretch(0.0, 1.0, 0.0);
        return !mc.world.getEntitiesByClass(
                EndCrystalEntity.class,
                box,
                crystal -> !crystal.isRemoved() && crystal.isAlive()
        ).isEmpty();
    }

    private boolean isCrystalBase(BlockPos pos) {
        return pos != null && BlockUtils.crystalBlock(pos);
    }

    private BlockHitResult currentBlockHit() {
        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        return null;
    }

    private boolean shouldFail() {
        return failChance.getValue() > 0 && Math.random() * 100.0 < failChance.getValue();
    }

    private boolean isDeadPlayerNearby() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) {
                continue;
            }
            if (mc.player.squaredDistanceTo(player) < 36.0
                    && (player.isDead() || player.getHealth() <= 0.0F)) {
                return true;
            }
        }
        return false;
    }

    private void simulateUseFeedback() {
        if (inputSimulation.isEnabled() && Template.isClickSim()) {
            MouseSimulation.mouseClick(mc.options.useKey.boundKey.getCode());
        }
    }

    private void simulateAttackFeedback() {
        if (inputSimulation.isEnabled() && Template.isClickSim()) {
            MouseSimulation.mouseClick(mc.options.attackKey.boundKey.getCode());
        }
    }

    private void disableConflictingCrystalModules() {
        if (Template.moduleManager == null) {
            return;
        }
        disableIfEnabled(AutoCrystalRecodeModule.class);
        disableIfEnabled(AutoHitCrystalModule.class);
        disableIfEnabled(AutoHitCrystalRewriteModule.class);
    }

    private void disableIfEnabled(Class<? extends Module> moduleClass) {
        Module module = Template.moduleManager.getModule(moduleClass);
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
        }
    }

    private void reset(boolean restoreSlot) {
        if (restoreSlot
                && switchBack.isEnabled()
                && originalSlot >= 0
                && originalSlot <= 8
                && mc.player != null
                && mc.interactionManager != null) {
            mc.player.getInventory().selectedSlot = originalSlot;
            mc.interactionManager.syncSelectedSlot();
        }

        stage = Stage.IDLE;
        originalSlot = -1;
        switchCooldown = 0;
        obsidianCooldown = 0;
        placeCooldown = 0;
        breakCooldown = 0;
        confirmationTicks = 0;
        expectedObsidianPos = null;
        interactionPerformedThisTick = false;
    }
}
