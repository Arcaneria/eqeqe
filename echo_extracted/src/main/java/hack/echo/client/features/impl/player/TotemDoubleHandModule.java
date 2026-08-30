package hack.echo.client.features.impl.player;

import hack.echo.client.event.EventSubscribe;
import hack.echo.client.event.impl.EventClientPlayerTick;
import hack.echo.client.event.impl.EventOnAttackEntity;
import hack.echo.client.event.impl.EventPacketReceive;
import hack.echo.client.event.impl.EventSetScreen;
import hack.echo.client.features.Category;
import hack.echo.client.features.Feature;
import hack.echo.client.features.FeatureInfo;
import hack.echo.client.features.settings.impl.BoolSetting;
import hack.echo.client.features.settings.impl.FloatSetting;
import hack.echo.client.features.settings.impl.IntSetting;
import hack.echo.client.features.settings.impl.RangeSetting;
import hack.echo.client.utils.combat.ExplosionUtils;
import hack.echo.client.utils.combat.TargetUtils;
import hack.echo.client.utils.inventory.InventoryUtils;
import hack.echo.client.utils.strings.Concat;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;

import java.util.HashMap;
import java.util.List;

/**
 * Double-hands a totem when the player would die: after a totem pop, after a
 * kill, or when a predicted crystal/sword hit would be lethal. Ported from
 * Stegered's AutoDoubleHandModule (template.rip) onto Echo's API, keeping the
 * Totem Hand name.
 */
public class TotemDoubleHandModule extends Feature {

    public TotemDoubleHandModule() {
        super(new FeatureInfo(
                Concat.of("Totem Hand"),
                Concat.of("Totem Double Hand"),
                Category.UTILITY
        ));
    }

    private final BoolSetting checkPlayersLook = new BoolSetting(Concat.of("Check Players Look"), true);
    private final BoolSetting predictCrystals = new BoolSetting(Concat.of("Predict Crystals"), true);
    private final BoolSetting predictSword = new BoolSetting(Concat.of("Predict Sword"), true);
    private final FloatSetting predictMultiply = new FloatSetting(Concat.of("Damage Multiplier"), 1f, 0f, 3f, 0.1f);
    private final BoolSetting doubleHandAfterPop = new BoolSetting(Concat.of("DHand After Pop"), true);
    private final BoolSetting doubleHandAfterKill = new BoolSetting(Concat.of("DHand After Kill"), true);
    private final BoolSetting switchOnOpenInv = new BoolSetting(Concat.of("Totem on Inventory"), false);
    private final BoolSetting notWhileShielding = new BoolSetting(Concat.of("Shield Check"), false);
    private final IntSetting slotToSwitch = new IntSetting(Concat.of("Totem Slot for Inventory"), 5, 1, 9);
    private final RangeSetting delay = new RangeSetting(Concat.of("Delay"), 100f, 200f, 0f, 500f, 1f, Concat.of(" ms"));
    private final RangeSetting cooldown = new RangeSetting(Concat.of("Cooldown"), 200f, 400f, 0f, 750f, 1f, Concat.of(" ms"));

    /**
     * A kill is credited to you if you attacked the victim within this window.
     */
    private static final long KILL_CREDIT_WINDOW_MS = 10_000L;
    /**
     * Deaths inside this radius also count as your kill, covering crystal and
     * other indirect kills where you never landed a direct hit.
     */
    private static final double KILL_CREDIT_RADIUS = 12.0D;

    private boolean needToDHand;
    private long cooldownClock, delayClock;
    private final HashMap<Integer, Long> attackedPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        super.onEnable();
        needToDHand = false;
        cooldownClock = System.currentTimeMillis() + (long) cooldown.getRandom();
        delayClock = System.currentTimeMillis() + (long) delay.getRandom();
        attackedPlayers.clear();
    }

    @Override
    public void onDisable() {
        attackedPlayers.clear();
        super.onDisable();
    }

    private boolean willDie(double damage) {
        if (mc.player.isUsingItem() && (mc.player.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE) || mc.player.getUseItem().is(Items.GOLDEN_APPLE))) {
            return false;
        }
        return mc.player.getHealth() - (damage * predictMultiply.getValue()) <= 0;
    }

    private boolean willDie(Player player, double damage) {
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 start = player.getEyePosition(partialTick);
        Vec3 end = start.add(player.getLookAngle().scale(player.position().distanceTo(mc.player.position())));
        HitResult hit = mc.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getLocation().distanceTo(mc.player.position().add(0, 1, 0)) < 1.5) {
            return mc.player.getHealth() - (damage * predictMultiply.getValue()) <= 0;
        }
        return false;
    }

    private boolean arePlayersAimingAtCrystal(EndCrystal crystal) {
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;

            Vec3 start = player.getEyePosition(partialTick);
            Vec3 end = start.add(player.getLookAngle());
            AABB box = new AABB(start, end);
            List<EndCrystal> crystalsInBox = mc.level.getEntitiesOfClass(EndCrystal.class, box, endCrystal -> crystal == endCrystal);

            if (!crystalsInBox.isEmpty())
                return true;
        }
        return false;
    }

    private boolean isPlayerAimingAtMe(Player player) {
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 start = player.getEyePosition(partialTick);
        Vec3 end = start.add(player.getLookAngle());
        AABB box = new AABB(start, end);
        List<Player> playersInBox = mc.level.getEntitiesOfClass(Player.class, box, player1 -> player1 == mc.player);

        return !playersInBox.isEmpty();
    }

    private boolean arePlayersAimingAtBlock(BlockPos blockPos) {
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Player player : mc.level.players()) {
            Vec3 start = player.getEyePosition(partialTick);
            Vec3 end = start.add(player.getLookAngle());
            BlockHitResult blockHitResult = mc.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

            if (blockHitResult != null && blockHitResult.getType() == HitResult.Type.BLOCK && blockHitResult.getBlockPos().equals(blockPos))
                return true;
        }
        return false;
    }

    @EventSubscribe
    private void onScreen(EventSetScreen event) {
        if (event.getScreen() instanceof InventoryScreen && mc.screen == null && switchOnOpenInv.getValue()) {
            InventoryUtils.setInvSlot(slotToSwitch.getValue() - 1);
        }
    }

    @EventSubscribe
    private void onAttack(EventOnAttackEntity.Post event) {
        if (event.getTarget() instanceof Player victim && victim != mc.player) {
            attackedPlayers.put(victim.getId(), System.currentTimeMillis());
        }
    }

    @EventSubscribe
    private void onPacketReceive(EventPacketReceive event) {
        if (!(event.getPacket() instanceof ClientboundEntityEventPacket packet) || mc.level == null) {
            return;
        }

        if (packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH && doubleHandAfterPop.getValue()
                && packet.getEntity(mc.level) == mc.player) {
            needToDHand = true;
        }

        if (packet.getEventId() == EntityEvent.DEATH && doubleHandAfterKill.getValue()
                && packet.getEntity(mc.level) instanceof Player victim
                && victim != mc.player && isMyKill(victim)) {
            attackedPlayers.remove(victim.getId());
            needToDHand = true;
        }
    }

    private boolean isMyKill(Player victim) {
        Long attackedAt = attackedPlayers.get(victim.getId());
        if (attackedAt != null && System.currentTimeMillis() - attackedAt <= KILL_CREDIT_WINDOW_MS) {
            return true;
        }
        return mc.player != null && mc.player.distanceToSqr(victim) <= KILL_CREDIT_RADIUS * KILL_CREDIT_RADIUS;
    }

    private void pruneAttackedPlayers() {
        if (mc.level == null) {
            attackedPlayers.clear();
            return;
        }
        long now = System.currentTimeMillis();
        attackedPlayers.entrySet().removeIf(entry -> {
            Entity entity = mc.level.getEntity(entry.getKey());
            return entity == null || !entity.isAlive() || now - entry.getValue() > KILL_CREDIT_WINDOW_MS;
        });
    }

    @EventSubscribe
    private void onPlayerTick(EventClientPlayerTick event) {
        if (isNull()) return;
        pruneAttackedPlayers();

        if (mc.screen != null) return;

        if ((notWhileShielding.getValue() && mc.player.getUseItem().is(Items.SHIELD)) || cooldownClock > System.currentTimeMillis()) {
            return;
        }

        boolean hand = needToDHand;

        if (!needToDHand && predictCrystals.getValue()) {
            List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(10), endCrystal -> true);

            for (EndCrystal crystal : crystals) {
                if (checkPlayersLook.getValue()) {
                    if (!arePlayersAimingAtCrystal(crystal)) continue;
                }

                float damage = ExplosionUtils.getExplosionDamageTo(mc.player, crystal.position(), 6.0f);
                if (willDie(damage)) {
                    needToDHand = true;
                    break;
                }
            }
        }

        if (!needToDHand && predictSword.getValue()) {
            LivingEntity target = TargetUtils.findClosestResolvedTarget(
                    mc.player.getEyePosition(),
                    mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            if (target instanceof Player player) {
                double damage = getSwordDamage(player);
                if (willDie(player, damage)) {
                    needToDHand = true;
                }
            }
        }

        if (needToDHand) {
            if (!hand) {
                delayClock = System.currentTimeMillis() + (long) delay.getRandom();
            }
            if (!mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
                if (delayClock > System.currentTimeMillis()) {
                    return;
                }

                InventoryUtils.selectItemFromHotbar(Items.TOTEM_OF_UNDYING);
            }

            needToDHand = false;
            cooldownClock = System.currentTimeMillis() + (long) cooldown.getRandom();
        }
    }

    /**
     * Rough estimate of how much damage {@code attacker}'s next sword hit would
     * deal to us: base fist damage plus the held sword's attack damage modifier
     * (only at full charge), crit, Sharpness, Strength and Weakness, then
     * reduced by our armor/toughness using the same approximation as
     * {@link ExplosionUtils}.
     */
    private double getSwordDamage(Player attacker) {
        double damage = 1;

        if (attacker.getAttackStrengthScale(0.5f) > 0.7f) {
            ItemAttributeModifiers mods = attacker.getMainHandItem().getAttributeModifiers(EquipmentSlot.MAINHAND);
            for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
                if (Attributes.ATTACK_DAMAGE.equals(entry.attribute())) {
                    damage += entry.modifier().amount();
                }
            }
            if (canCrit(attacker)) {
                damage *= 1.5;
            }
        }

        var sharpnessHolder = mc.level.registryAccess().get(Enchantments.SHARPNESS);
        if (sharpnessHolder.isPresent()) {
            int sharpness = EnchantmentHelper.getItemEnchantmentLevel(sharpnessHolder.get(), attacker.getMainHandItem());
            if (sharpness > 0) {
                damage += (0.5 * sharpness) + 0.5;
            }
        }

        MobEffectInstance strength = attacker.getEffect(MobEffects.STRENGTH);
        if (strength != null) {
            damage += 3.0 * (strength.getAmplifier() + 1);
        }

        MobEffectInstance weakness = attacker.getEffect(MobEffects.WEAKNESS);
        if (weakness != null) {
            damage -= 4.0 * (weakness.getAmplifier() + 1);
        }

        damage = Math.max(damage, 0);
        return damageAfterArmor((float) damage);
    }

    private static boolean canCrit(Player attacker) {
        return attacker.onGround() && !attacker.isInWater() && !attacker.hasEffect(MobEffects.BLINDNESS);
    }

    private float damageAfterArmor(float rawDamage) {
        float armor = mc.player.getArmorValue();
        float toughness = (float) mc.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float divider = 2.0f + toughness / 4.0f;
        float effectiveArmor = Mth.clamp(armor - rawDamage / divider, armor * 0.2f, 20.0f);
        return rawDamage * (1.0f - effectiveArmor / 25.0f);
    }
}
