package com.yiva.homingarrows.interop;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 互通里"命中结算"的部分：伤害、点燃、引雷、击退、抢夺。
 * <p>
 * 伤害、点燃、引雷、击退、抢夺挂在 {@link LivingHurtEvent} 上，因为这一处同时覆盖箭矢、弩箭与投掷
 * 三叉戟：三者最终都会调用 {@code entity.hurt(...)}，此时 {@code getDirectEntity()} 就是弹体，
 * {@code getEntity()} 是目标，且 {@link ProjectileWeaponSnapshot} 能给出当初发射它的那把武器。
 * <p>
 * 另有一处在 {@link LivingAttackEvent} 上（{@link #onLivingAttack}）：多重射击齐射的几支箭会被原版
 * 受击无敌帧吞掉，那一步发生在 {@code LivingHurtEvent} 之前，必须在那里放行。
 * <p>
 * 关键在于"只补原版没算的"：{@link EnchantmentInterop#isNative} 记录了每一种武器自身已经结算过的
 * 项目，这里据此跳过，避免双重加成。例如三叉戟投掷的伤害在原版已经叠加了
 * {@code EnchantmentHelper.getDamageBonus}（穿刺/锋利/亡灵杀手/节肢杀手），因此这里只补力量，
 * 不再重复补锋利系。
 */
@Mod.EventBusSubscriber(modid = HomingArrowsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InteropCombatHandler {

    private InteropCombatHandler() {
    }

    /** 命中结算入口。 */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) {
            return;
        }
        DamageSource source = event.getSource();
        // 先看直接来源：弹体 = 远程命中，玩家 = 近战命中
        if (source.getDirectEntity() instanceof AbstractArrow projectile) {
            handleProjectileHit(event, projectile, target);
        } else if (source.getDirectEntity() instanceof Player attacker) {
            handleMeleeHit(attacker, target);
        }
    }

    /**
     * 多重射击齐射：同一次射出的几支箭互相不吃受击无敌帧。
     * <p>
     * 原版 {@code LivingEntity.hurt} 在真正结算之前有一道无敌帧判断：
     * {@code invulnerableTime > 10 && 本次伤害 <= lastHurt} 就直接 {@code return false}。
     * 三支同轨迹的箭同时命中时，第二、三支会被整段吞掉——连 {@link LivingHurtEvent} 都不会触发，
     * 所以在伤害结算里再怎么补都没用。
     * <p>
     * {@link LivingAttackEvent} 是 {@code hurt} 的第一步（Forge 在无敌帧判断<b>之前</b>抛出），
     * 在这里把无敌时间清零，后面就会走"照常结算"分支，三支箭各打各的完整伤害。
     * 只有带齐射标记的箭会走到这里，因此其它伤害来源的无敌帧完全不受影响。
     */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        if (!arrow.getPersistentData().getBoolean(InteropProjectileHandler.TAG_MULTISHOT_VOLLEY)) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide()) {
            return;
        }
        target.invulnerableTime = 0;
    }

    // ------------------------------------------------------------------
    // 远程命中
    // ------------------------------------------------------------------

    private static void handleProjectileHit(LivingHurtEvent event, AbstractArrow projectile, LivingEntity target) {
        ItemStack weapon = ProjectileWeaponSnapshot.get(projectile);
        EnchantmentInterop.WeaponKind kind = EnchantmentInterop.kindOf(weapon);
        if (kind == EnchantmentInterop.WeaponKind.OTHER) {
            return;
        }

        MobType mobType = target.getMobType();
        float extra = 0.0F;

        // 锋利 / 亡灵杀手 / 节肢杀手 / 穿刺：这三件里只有三叉戟的原版投掷伤害会叠加 getDamageBonus
        if (kind != EnchantmentInterop.WeaponKind.TRIDENT) {
            extra += EnchantmentHelper.getDamageBonus(weapon, mobType);
        }
        // 力量：弓在 BowItem 里已经写进 baseDamage；弩与三叉戟需要补。
        // 原版 ArrowDamageEnchantment 不重写 getDamageBonus，所以这里的补法与弓的 +0.5/级 +0.5 完全一致
        if (kind != EnchantmentInterop.WeaponKind.BOW) {
            int power = EnchantmentInterop.level(weapon, Enchantments.POWER_ARROWS);
            if (power > 0) {
                extra += power * 0.5F + 0.5F;
            }
        }
        if (extra > 0.0F) {
            event.setAmount(event.getAmount() + extra);
            debug("{} 命中 {} 追加 {} 点伤害", kind, target.getName().getString(), String.format("%.2f", extra));
        }

        // 火矢：弓/弩在发射瞬间点燃箭矢（原版行为，命中时由弹体点燃目标 5 秒），三叉戟投掷则直接点燃目标；
        // 火焰附加（表格之外的顺带对齐）投掷路径原版完全不读，这里按近战同样的 4 秒/级补上
        int fireSeconds = 0;
        if (kind == EnchantmentInterop.WeaponKind.TRIDENT
                && EnchantmentInterop.level(weapon, Enchantments.FLAMING_ARROWS) > 0) {
            fireSeconds = 5;
        }
        int fireAspect = EnchantmentInterop.level(weapon, Enchantments.FIRE_ASPECT);
        if (fireAspect > 0) {
            fireSeconds = Math.max(fireSeconds, fireAspect * 4);
        }
        if (fireSeconds > 0) {
            target.setSecondsOnFire(fireSeconds);
        }
        // 引雷：三叉戟的原版逻辑已经处理，弓与弩需要补
        if (kind != EnchantmentInterop.WeaponKind.TRIDENT) {
            tryChanneling(projectile, weapon, target);
        }
        // 击退：弓在 BowItem 里设置冲击、弩在发射瞬间设置，只有三叉戟投掷需要在这里补
        // （原版投掷路径既不读冲击也不读剑的击退附魔，两者取较高等级）
        if (kind == EnchantmentInterop.WeaponKind.TRIDENT) {
            int strength = Math.max(EnchantmentInterop.level(weapon, Enchantments.PUNCH_ARROWS),
                    EnchantmentInterop.level(weapon, Enchantments.KNOCKBACK));
            if (strength > 0) {
                applyKnockback(target, strength, projectile.getDeltaMovement());
            }
        }
        // 节肢杀手：伤害已由上面的 getDamageBonus 补上，这里只补"命中后附加缓慢"
        applyArthropodSlow(projectile, weapon, target);
    }

    /**
     * 节肢杀手的缓慢效果。
     * <p>
     * 原版弹体命中后会调用 {@code EnchantmentHelper.doPostDamageEffects}，它读的是<b>射手当前主手</b>，
     * 因此玩家在箭落地前切手就会丢掉这个效果。这里用源武器快照兜底，同时避免与原生效果重复施加。
     */
    private static void applyArthropodSlow(AbstractArrow projectile, ItemStack weapon, LivingEntity target) {
        if (target.getMobType() != MobType.ARTHROPOD) {
            return;
        }
        int bane = EnchantmentInterop.level(weapon, Enchantments.BANE_OF_ARTHROPODS);
        if (bane <= 0) {
            return;
        }
        Entity owner = projectile.getOwner();
        if (owner instanceof LivingEntity living
                && EnchantmentInterop.level(living.getMainHandItem(), Enchantments.BANE_OF_ARTHROPODS) > 0) {
            // 射手仍然握着带节肢杀手的武器，原版会处理
            return;
        }
        // 与原版 DamageEnchantment.doPostAttack 一致：20 + 等级×10 以内的随机时长，等级 IV 缓慢
        int duration = 20 + target.getRandom().nextInt(10 * bane);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 3));
    }

    /** 引雷：雷雨天命中且目标能看见天空时召唤闪电，参考原版 {@code ThrownTrident} 的写法。 */
    private static void tryChanneling(AbstractArrow projectile, ItemStack weapon, LivingEntity target) {
        if (EnchantmentInterop.level(weapon, Enchantments.CHANNELING) <= 0) {
            return;
        }
        if (!(target.level() instanceof ServerLevel serverLevel) || !serverLevel.isThundering()) {
            return;
        }
        BlockPos pos = target.blockPosition();
        if (!serverLevel.canSeeSky(pos)) {
            return;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(serverLevel);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(Vec3.atBottomCenterOf(pos));
        Entity owner = projectile.getOwner();
        bolt.setCause(owner instanceof ServerPlayer serverPlayer ? serverPlayer : null);
        serverLevel.addFreshEntity(bolt);
        serverLevel.playSound(null, pos, SoundEvents.TRIDENT_THUNDER, SoundSource.WEATHER, 5.0F, 1.0F);
        debug("引雷附魔生效：{} 处召唤闪电", pos);
    }

    // ------------------------------------------------------------------
    // 近战命中
    // ------------------------------------------------------------------

    /**
     * 近战：只有"三叉戟 + 冲击"这一项需要补。
     * <p>
     * 力量对所有武器都不构成近战加成（原版 {@code ArrowDamageEnchantment} 不重写 {@code getDamageBonus}），
     * 锋利系本来就是主手 NBT 生效，击退（剑）由原版读取，因此都不在这里处理。
     */
    private static void handleMeleeHit(Player attacker, LivingEntity target) {
        ItemStack mainHand = attacker.getMainHandItem();
        if (EnchantmentInterop.kindOf(mainHand) != EnchantmentInterop.WeaponKind.TRIDENT) {
            return;
        }
        int punch = EnchantmentInterop.level(mainHand, Enchantments.PUNCH_ARROWS);
        if (punch > 0) {
            applyKnockback(target, punch, attacker.getViewVector(1.0F));
        }
    }

    /** 击退：与原版箭矢的击退公式一致（强度 × 0.6，并按目标的击退抗性衰减）。 */
    private static void applyKnockback(LivingEntity target, int level, Vec3 direction) {
        Vec3 flat = new Vec3(direction.x, 0.0D, direction.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return;
        }
        double resistance = Math.max(0.0D, 1.0D - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        Vec3 push = flat.normalize().scale(level * 0.6D * resistance);
        if (push.lengthSqr() > 0.0D) {
            target.push(push.x, 0.1D, push.z);
        }
    }

    // ------------------------------------------------------------------
    // 抢夺
    // ------------------------------------------------------------------

    /**
     * 抢夺：用源武器快照读取等级。
     * <p>
     * 原版默认读的是"击杀者当前主手"，玩家射箭后切手就会失效；这里只在快照的等级更高时覆盖，
     * 因此不会削弱原版行为。
     */
    @SubscribeEvent
    public static void onLootingLevel(LootingLevelEvent event) {
        DamageSource source = event.getDamageSource();
        if (source == null || !(source.getDirectEntity() instanceof AbstractArrow projectile)) {
            return;
        }
        ItemStack weapon = ProjectileWeaponSnapshot.get(projectile);
        int looting = EnchantmentInterop.level(weapon, Enchantments.MOB_LOOTING);
        if (looting > event.getLootingLevel()) {
            event.setLootingLevel(looting);
        }
    }

    private static void debug(String message, Object... args) {
        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[附魔互通] " + message, args);
        }
    }
}
