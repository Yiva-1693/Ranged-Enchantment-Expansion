package com.yiva.homingarrows.interop;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 互通里"与弹体飞行有关"的部分：发射瞬间补齐属性和记录源武器，飞行途中处理三叉戟穿透。
 * <p>
 * 分工与原版的对应关系（只补原版没做的，避免双重结算）：
 * <ul>
 *   <li><b>穿透</b>：弩在 {@code CrossbowItem.getArrow} 里写穿透等级，弓不读；因此弓在这里补
 *       {@code setPierceLevel}，三叉戟则靠下面的穿透状态机。</li>
 *   <li><b>冲击 / 火矢</b>：弓在 {@code BowItem.releaseUsing} 里设置，弩不读；因此弩在这里补
 *       {@code setKnockback} 与点燃箭矢。</li>
 *   <li><b>多重射击</b>：弩的装填流程原生支持三发，弓没有；因此弓在这里克隆出另外两支箭。</li>
 * </ul>
 * 另外两项由专门的实现负责：弩的"无限"见
 * {@link com.yiva.homingarrows.mixin.CrossbowItemMixin}，弓的快速装填见 {@link QuickChargeHandler}。
 * <p>
 * 三叉戟穿透的实现思路：原版 {@code ThrownTrident.onHitEntity} 命中后会把速度反向并置位
 * {@code dealtDamage}，而 {@code findHitEntity} 一旦看到该标记就再也不返回任何目标。这里在命中
 * 前夕记录速度与已命中目标，等该刻原版处理完（服务器刻末尾）再复位标记并恢复速度，于是三叉戟会
 * 继续沿原方向飞行，同时不会重复伤害同一个目标。
 */
@Mod.EventBusSubscriber(modid = HomingArrowsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InteropProjectileHandler {

    private InteropProjectileHandler() {
    }

    /** 多重射击额外克隆出的箭矢数量（合计 3 支，与弩的原生多重射击一致）。 */
    private static final int MULTISHOT_EXTRA_ARROWS = 2;

    /**
     * 克隆箭相对主箭的横向出生偏移（格）。
     * <p>
     * 只用来把三支箭在画面上分开，方向与速度完全一致；数值不建议调大，
     * 否则体型很小的生物（如蠹虫）可能只被中间那支命中。
     */
    private static final double MULTISHOT_LATERAL_OFFSET = 0.1D;

    /** 克隆箭标记：克隆体进入世界时不再克隆、也不重复做发射瞬间的处理。 */
    private static final String TAG_MULTISHOT_CLONE = "ranged_enchant_expansion:multishotClone";

    /** 发射期属性只初始化一次，跨区块/维度重新加入世界时保留已有快照与箭矢数量。 */
    private static final String TAG_INTEROP_INITIALIZED = "ranged_enchant_expansion:interopInitialized";

    /**
     * 多重射击齐射标记：主箭与克隆体都会带上。
     * <p>
     * 几支同角度的箭几乎会在同一时间命中，原版受击无敌帧会把第二支之后的整段吞掉，因此
     * {@link InteropCombatHandler} 需要靠这个标记识别"同一次齐射"并放行。
     * 它只写在实体上、不会随掉落物回到背包（射出的箭被拾取后是新实体），因此不会外泄。
     */
    public static final String TAG_MULTISHOT_VOLLEY = "ranged_enchant_expansion:multishotVolley";

    /** 三叉戟穿透的运行时状态，键为三叉戟实体 UUID。 */
    private static final Map<UUID, PierceState> PIERCING = new HashMap<>();

    /** 单支穿透三叉戟的状态。 */
    private static final class PierceState {
        final ThrownTrident trident;
        /** 已经结算过伤害的实体 ID，避免穿过同一目标时反复命中。 */
        final Set<Integer> hitEntityIds = new HashSet<>();
        /** 命中瞬间的速度；非 null 表示等待在这一刻末尾恢复飞行。 */
        Vec3 pendingVelocity;

        PierceState(ThrownTrident trident) {
            this.trident = trident;
        }
    }

    // ------------------------------------------------------------------
    // 发射瞬间
    // ------------------------------------------------------------------

    /** 弹体进入世界时：记录源武器，并按武器类型补齐原版没做的发射期属性。 */
    @SubscribeEvent
    public static void onProjectileJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        // 我们自己克隆出来的额外箭矢：不再克隆，也无需重复处理
        CompoundTag data = arrow.getPersistentData();
        if (data.getBoolean(TAG_MULTISHOT_CLONE)) {
            // 同时修正旧存档中已生成的可回收克隆箭。
            arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            return;
        }
        // 旧存档没有初始化标记，也不能根据射手此刻持有的武器重新发射或覆盖快照。
        if (event.loadedFromDisk() || data.getBoolean(TAG_INTEROP_INITIALIZED)) {
            return;
        }
        if (!(arrow.getOwner() instanceof Player player)) {
            return;
        }

        ItemStack weapon = EnchantmentInterop.findFiringWeapon(player);
        EnchantmentInterop.WeaponKind kind = EnchantmentInterop.kindOf(weapon);
        if (kind == EnchantmentInterop.WeaponKind.OTHER) {
            return;
        }

        // 在生成克隆箭前标记，后续重新入场不再执行发射初始化。
        data.putBoolean(TAG_INTEROP_INITIALIZED, true);
        // 命中时才需要知道目标类型/掉落，武器整份快照存进弹体
        ProjectileWeaponSnapshot.capture(arrow, weapon);

        switch (kind) {
            case BOW -> {
                applyPiercing(arrow, weapon);
                applyMultishot(event.getLevel(), arrow, weapon, player);
            }
            case CROSSBOW -> {
                applyPunchAndFlame(arrow, weapon);
                applyInfinityPickup(arrow, weapon);
            }
            default -> {
                // 三叉戟：投掷期的额外效果在命中结算（InteropCombatHandler）里处理
            }
        }
    }

    /** 穿透：弓原版不读穿透附魔，这里补上（弩已在 CrossbowItem 中处理）。 */
    private static void applyPiercing(AbstractArrow arrow, ItemStack weapon) {
        int piercing = EnchantmentInterop.level(weapon, Enchantments.PIERCING);
        if (piercing > 0 && arrow.getPierceLevel() <= 0) {
            arrow.setPierceLevel((byte) Math.min(piercing, Byte.MAX_VALUE));
            debug("弓的穿透附魔生效：等级 {}", piercing);
        }
    }

    /** 冲击与火矢：弩原版不读这两项，这里补上（弓已在 BowItem 中处理）。 */
    private static void applyPunchAndFlame(AbstractArrow arrow, ItemStack weapon) {
        int punch = EnchantmentInterop.level(weapon, Enchantments.PUNCH_ARROWS);
        if (punch > 0 && arrow.getKnockback() <= 0) {
            arrow.setKnockback(punch);
        }
        if (EnchantmentInterop.level(weapon, Enchantments.FLAMING_ARROWS) > 0 && !arrow.isOnFire()) {
            // 与原版弓一致：点燃箭矢本身 100 刻，命中时由弹体逻辑点燃目标约 5 秒
            arrow.setSecondsOnFire(100);
        }
    }

    /**
     * 弩的无限附魔：射出的箭不可被拾取，与原版弓的无限语义保持一致
     * （原版 {@code BowItem} 在判定无限后同样把弹体设为 {@code CREATIVE_ONLY}）。
     * <p>
     * 这一步不能省：既然"背包里没有箭也能发射"，若射出的箭还能捡回来，就等于凭空刷箭。
     */
    private static void applyInfinityPickup(AbstractArrow arrow, ItemStack weapon) {
        if (EnchantmentInterop.hasInfinityForCrossbow(weapon)) {
            arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        }
    }

    /**
     * 多重射击（弓）：弩的装填流程原生支持三发，弓没有，这里克隆出另外两支。
     * <p>
     * 三支箭<b>方向完全一致</b>：速度向量直接照抄主箭，刻意不走 {@code shootFromRotation}——
     * 那个方法会给每支箭叠一次独立的随机散布（弓的 inaccuracy 是 1.0，约 ±1.4°），
     * 看上去就是"一支往左、一支往右"。
     * <p>
     * 但也不让它们像素级重合：克隆体的<b>出生点</b>在水平面内沿垂直于飞行方向的轴各偏
     * {@link #MULTISHOT_LATERAL_OFFSET} 格。三条轨迹互相平行、间距恒定，既能看出是三支箭，
     * 又始终打向同一个方向、命中同一点附近。
     * <p>
     * 克隆体其余状态照抄主箭：伤害、击退、穿透、暴击、着火与源武器快照。
     * {@code setOwner(player)} 会把拾取状态改成 {@code ALLOWED}，因此必须在之后显式设为
     * {@code CREATIVE_ONLY}，与原版弩的额外箭一致，避免生存模式出现"一箭变三箭"。
     * <p>
     * 另外给主箭与克隆体都打上 {@link #TAG_MULTISHOT_VOLLEY}：几支箭先后命中时，原版受击无敌帧
     * 只会让第一支造成伤害，后几支连事件都不会触发（见 {@link InteropCombatHandler#onLivingAttack}）。
     * <p>
     * 注意：克隆体由实体类型创建，药箭的药剂效果（写在物品 NBT 上的）不会跟着复制，
     * 光灵箭这类由实体类型决定行为的箭不受影响。
     */
    private static void applyMultishot(Level level, AbstractArrow arrow, ItemStack weapon, Player player) {
        if (EnchantmentInterop.level(weapon, Enchantments.MULTISHOT) <= 0) {
            return;
        }
        // 主箭也标记为齐射：否则几支箭里谁先命中是实体顺序决定的，可能反而是主箭被无敌帧吞掉
        arrow.getPersistentData().putBoolean(TAG_MULTISHOT_VOLLEY, true);

        // 速度向量原样复制，三支箭的飞行方向因此完全一致
        Vec3 motion = arrow.getDeltaMovement();

        // 水平面内垂直于飞行方向的横向轴；飞行方向几乎竖直时退回 +x
        Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
        Vec3 lateral = horizontal.lengthSqr() < 1.0E-6D
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : new Vec3(-horizontal.z, 0.0D, horizontal.x).normalize();

        for (int i = 0; i < MULTISHOT_EXTRA_ARROWS; i++) {
            AbstractArrow clone = (AbstractArrow) arrow.getType().create(level);
            if (clone == null) {
                continue;
            }
            double side = i == 0 ? -MULTISHOT_LATERAL_OFFSET : MULTISHOT_LATERAL_OFFSET;
            Vec3 spawn = new Vec3(arrow.getX(), arrow.getY(), arrow.getZ()).add(lateral.scale(side));

            clone.moveTo(spawn.x, spawn.y, spawn.z, arrow.getYRot(), arrow.getXRot());
            clone.setOwner(player);
            clone.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            clone.setBaseDamage(arrow.getBaseDamage());
            clone.setKnockback(arrow.getKnockback());
            clone.setPierceLevel(arrow.getPierceLevel());
            clone.setCritArrow(arrow.isCritArrow());
            if (arrow.isOnFire()) {
                clone.setRemainingFireTicks(arrow.getRemainingFireTicks());
            }

            // 先复制标记（含源武器快照与齐射标记），再补上"克隆体"自己的标记
            CompoundTag data = clone.getPersistentData();
            data.merge(arrow.getPersistentData().copy());
            data.putBoolean(TAG_MULTISHOT_CLONE, true);

            // 与主箭同向同速（照抄速度与朝向），只靠出生点的横向偏移把三支箭分开
            clone.setDeltaMovement(motion);
            clone.setYRot(arrow.getYRot());
            clone.setXRot(arrow.getXRot());
            clone.yRotO = arrow.getYRot();
            clone.xRotO = arrow.getXRot();
            clone.hasImpulse = true;

            level.addFreshEntity(clone);
        }
        debug("弓的多重射击生效：额外射出 {} 支平行箭（横向偏移 {} 格，方向与主箭一致）",
                MULTISHOT_EXTRA_ARROWS, MULTISHOT_LATERAL_OFFSET);
    }

    // ------------------------------------------------------------------
    // 三叉戟穿透
    // ------------------------------------------------------------------

    /**
     * 三叉戟穿透：命中前记录状态，交给本刻末尾恢复飞行。
     * <p>
     * 已经结算过的目标改用 Forge 的 {@link ProjectileImpactEvent.ImpactResult#SKIP_ENTITY}：原版会把
     * 该实体记进 {@code ignoredEntities} 并跳过本次命中，于是既不重复伤害、也不反向速度，三叉戟平滑穿过去。
     */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownTrident trident)) {
            return;
        }
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)) {
            return;
        }
        if (!(hit.getEntity() instanceof LivingEntity target)) {
            return;
        }
        ItemStack weapon = ProjectileWeaponSnapshot.get(trident);
        if (EnchantmentInterop.level(weapon, Enchantments.PIERCING) <= 0) {
            return;
        }
        // 永不打到自己
        if (target == trident.getOwner()) {
            return;
        }

        PierceState state = PIERCING.computeIfAbsent(trident.getUUID(), key -> new PierceState(trident));
        if (!state.hitEntityIds.add(target.getId())) {
            // 已经穿过的目标：让弹体直接忽略它，继续飞
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
            return;
        }
        state.pendingVelocity = trident.getDeltaMovement();
    }

    /** 服务器刻末尾：复位 dealtDamage 并恢复命中瞬间的速度，让穿透三叉戟继续飞。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PIERCING.isEmpty()) {
            return;
        }
        // 与追踪管理器同样用 key 快照 + 延迟移除：这张表会在实体加入/离开世界时被改，
        // 直接迭代活 Map 一旦发生同 tick 增删就是整个服务端 tick 崩溃。
        List<UUID> keys = new ArrayList<>(PIERCING.keySet());
        List<UUID> toRemove = new ArrayList<>();

        for (UUID key : keys) {
            PierceState state = PIERCING.get(key);
            if (state == null) {
                continue;
            }
            ThrownTrident trident = state.trident;
            if (trident == null || trident.isRemoved() || !trident.isAlive()) {
                toRemove.add(key);
                continue;
            }
            Vec3 velocity = state.pendingVelocity;
            if (velocity == null) {
                continue;
            }
            state.pendingVelocity = null;
            if (trident instanceof TridentAccess access) {
                access.homingarrows$setDealtDamage(false);
            }
            trident.setDeltaMovement(velocity);
            trident.hasImpulse = true;
        }

        for (UUID key : toRemove) {
            PIERCING.remove(key);
        }
    }

    /** 三叉戟离开世界时清掉穿透状态。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof ThrownTrident trident) {
            PIERCING.remove(trident.getUUID());
        }
    }

    private static void debug(String message, Object... args) {
        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[附魔互通] " + message, args);
        }
    }
}
