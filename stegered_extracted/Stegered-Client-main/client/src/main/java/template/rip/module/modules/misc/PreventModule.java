package template.rip.module.modules.misc;

import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import template.rip.api.event.events.AttackEvent;
import template.rip.api.event.events.BlockBreakEvent;
import template.rip.api.event.events.ItemUseEvent;
import template.rip.api.event.orbit.EventHandler;
import template.rip.api.object.Description;
import template.rip.api.util.BlockUtils;
import template.rip.module.Module;
import template.rip.module.setting.settings.BooleanSetting;

/**
 * Prevents common accidental actions during crystal PvP.
 *
 * <p>This module is adapted from Argon's Prevent module to Achilles' event,
 * setting, configuration, and ClickGUI systems.</p>
 */
public final class PreventModule extends Module {

    public final BooleanSetting doubleGlowstone = new BooleanSetting(
            this,
            Description.of("Prevents charging a respawn anchor that already has a charge."),
            false,
            "Double Glowstone"
    );

    public final BooleanSetting glowstoneMisplace = new BooleanSetting(
            this,
            Description.of("Only allows using glowstone while aiming at a respawn anchor."),
            false,
            "Glowstone Misplace"
    );

    public final BooleanSetting anchorOnAnchor = new BooleanSetting(
            this,
            Description.of("Prevents placing a respawn anchor against an uncharged respawn anchor."),
            false,
            "Anchor On Anchor"
    );

    public final BooleanSetting obiPunch = new BooleanSetting(
            this,
            Description.of("Prevents starting to break obsidian while holding an end crystal."),
            false,
            "Obi Punch"
    );

    public final BooleanSetting echestClick = new BooleanSetting(
            this,
            Description.of("Prevents opening ender chests while holding common PvP items."),
            false,
            "E-Chest Click"
    );

    public PreventModule(Category category, Description description, String name) {
        super(category, description, name);
    }

    @EventHandler
    private void onAttack(AttackEvent.Pre event) {
        if (shouldPreventObsidianBreak()) {
            event.cancel();
        }
    }

    @EventHandler
    private void onBlockBreak(BlockBreakEvent.Pre event) {
        if (shouldPreventObsidianBreak()) {
            event.cancel();
        }
    }

    @EventHandler
    private void onItemUse(ItemUseEvent.Pre event) {
        BlockHitResult hit = targetedBlock();
        if (hit == null) {
            return;
        }

        BlockPos pos = hit.getBlockPos();

        if (doubleGlowstone.isEnabled()
                && isHolding(Items.GLOWSTONE)
                && BlockUtils.isAnchorCharged(pos)) {
            event.cancel();
        }

        if (glowstoneMisplace.isEnabled()
                && isHolding(Items.GLOWSTONE)
                && !BlockUtils.isBlock(Blocks.RESPAWN_ANCHOR, pos)) {
            event.cancel();
        }

        if (anchorOnAnchor.isEnabled()
                && isHolding(Items.RESPAWN_ANCHOR)
                && BlockUtils.isAnchorUncharged(pos)) {
            event.cancel();
        }

        if (echestClick.isEnabled()
                && BlockUtils.isBlock(Blocks.ENDER_CHEST, pos)
                && isHoldingPvpItemInMainHand()) {
            event.cancel();
        }
    }

    private boolean shouldPreventObsidianBreak() {
        BlockHitResult hit = targetedBlock();
        return hit != null
                && obiPunch.isEnabled()
                && isHolding(Items.END_CRYSTAL)
                && BlockUtils.isBlock(Blocks.OBSIDIAN, hit.getBlockPos());
    }

    private BlockHitResult targetedBlock() {
        if (!nullCheck()) {
            return null;
        }
        return mc.crosshairTarget instanceof BlockHitResult hit ? hit : null;
    }

    private boolean isHolding(Item item) {
        return mc.player != null && mc.player.isHolding(item);
    }

    private boolean isHoldingPvpItemInMainHand() {
        if (mc.player == null) {
            return false;
        }

        Item item = mc.player.getMainHandStack().getItem();
        return item instanceof SwordItem
                || item == Items.END_CRYSTAL
                || item == Items.OBSIDIAN
                || item == Items.RESPAWN_ANCHOR
                || item == Items.GLOWSTONE;
    }
}
