package com.yiva.homingarrows.event;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import com.yiva.homingarrows.whitelist.HomingWhitelist;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪箭管理器：服务端每刻更新所有被标记为追踪箭的箭矢。
 * <p>
 * 行为要点：
 * <ul>
 *   <li>所有数值均来自 {@link ModConfig}，改配置即时生效；</li>
 *   <li>目标搜索被限制在球形范围内的锥形区域中（以箭矢飞行方向为中心）；</li>
 *   <li>搜索按 {@code searchInterval} 节流，并且目标仍有效时完全跳过实体查询；</li>
 *   <li>追踪只改变方向，速度受发射速度与 {@code maxSpeedMultiplier} 限制，不会无限加速；</li>
 *   <li><b>永不锁定射手本人</b>（无论配置如何，这是硬性规则）。</li>
 * </ul>
 */
public final class HomingArrowManager {

    /** 追踪标记，写在箭矢的持久化数据上。 */
    public static final String TAG_LOCK_ON_ARROW = "isLockOnArrow";

    /** 所有被追踪箭的数据，键为箭矢 UUID。 */
    private static final Map<UUID, ArrowTrackingData> TRACKING_ARROWS = new ConcurrentHashMap<>();

    /**
     * 连续多少次更新位置不变即判定箭矢已落地/卡住，从而提前结束追踪。
     * <p>
     * 取 2 是为了容忍「刚撞上方块、引擎尚未完全夹住」的边界情况：
     * 真正卡住后位移恒为 0，会立刻累加到 2，仅比落地晚约 2 刻（0.1 秒）。
     */
    private static final int STATIONARY_UPDATES_TO_STOP = 2;

    /**
     * 位置停滞的判定阈值（格²）。
     * <p>
     * 飞行中的箭矢每刻位移通常为 0.5~3 格，远大于此值；只有被方块或地面
     * 卡住时引擎才会把它拉回原位，使位移恒为 0。
     */
    private static final double STATIONARY_DELTA_SQR = 1.0E-4D;

    private HomingArrowManager() {
    }

    /** 单支追踪箭的运行时状态。 */
    private static final class ArrowTrackingData {
        final AbstractArrow arrow;
        /** 存活刻数（从进入世界开始计算）。 */
        int ticksAlive;
        /** 记录到的最大速度，用于避免追踪过程中掉速，同时严格遵守速度上限。 */
        double maxSpeed;
        LivingEntity currentTarget;
        /** 已命中的目标，配合穿透附魔避免反复追同一个目标。 */
        final Set<UUID> hitTargets = new HashSet<>();
        /** 下一次允许执行目标搜索的刻数。 */
        int nextSearchTick;
        /**
         * 连续「位置未发生变化」的更新次数，用于判断箭矢已落地/卡在方块中。
         * <p>
         * 刻意不使用 {@code deltaMovement} 判断：制导逻辑每刻都会覆写速度，
         * 该变量无法反映箭矢是否真的被卡住。位置则由引擎在 {@code move()} 中
         * 独立推进，不受我们写入速度的影响，因此是唯一可靠的停滞信号。
         */
        int stationaryUpdates;
        /** 上一次更新时记录的箭矢位置（首次记录后不为 null）。 */
        Vec3 lastPos;

        ArrowTrackingData(AbstractArrow arrow, double observedSpeed) {
            this.arrow = arrow;
            this.maxSpeed = Math.max(0.0D, observedSpeed);
            this.lastPos = arrow.position();
        }
    }

    // ------------------------------------------------------------------
    // 注册 / 注销
    // ------------------------------------------------------------------

    /** 将一支箭加入追踪系统。重复调用是安全的。 */
    public static void addArrow(AbstractArrow arrow) {
        if (arrow == null) {
            return;
        }
        if (TRACKING_ARROWS.containsKey(arrow.getUUID())) {
            return;
        }
        // 此刻箭矢的速度已由弓的射击逻辑设置完毕，可作为速度上限的基准。
        TRACKING_ARROWS.put(arrow.getUUID(), new ArrowTrackingData(arrow, arrow.getDeltaMovement().length()));
        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[追踪箭] 开始追踪箭矢 {}，基准速度 {}",
                    arrow.getUUID(), String.format("%.3f", arrow.getDeltaMovement().length()));
        }
    }

    /** 移除一支箭的追踪数据。 */
    public static void removeArrow(AbstractArrow arrow) {
        if (arrow != null) {
            TRACKING_ARROWS.remove(arrow.getUUID());
        }
    }

    /** 清空全部追踪数据（服务器停止、维度卸载时调用）。 */
    public static void clearAll() {
        TRACKING_ARROWS.clear();
    }

    /** 当前追踪中的箭矢数量（调试用）。 */
    public static int getTrackedArrowCount() {
        return TRACKING_ARROWS.size();
    }

    // ------------------------------------------------------------------
    // 每刻更新
    // ------------------------------------------------------------------

    /** 由服务端 tick 事件驱动，更新全部追踪箭。 */
    public static void updateAllArrows() {
        if (TRACKING_ARROWS.isEmpty()) {
            return;
        }

        // 用 key 快照遍历、循环外统一移除。
        // 本方法在遍历中会去读实体世界（搜索目标、发粒子），而弹体加入/离开世界会经由
        // ModEvents 的事件回调往这张表里增删；直接迭代活 Map 会抛
        // ConcurrentModificationException，并让整个服务端 tick 崩溃（实机已崩过一次）。
        // 表本身也换成 ConcurrentHashMap，容忍同 tick 内的增删。
        List<UUID> keys = new ArrayList<>(TRACKING_ARROWS.keySet());
        List<UUID> toRemove = new ArrayList<>();

        for (UUID key : keys) {
            ArrowTrackingData data = TRACKING_ARROWS.get(key);
            if (data == null) {
                continue;
            }
            AbstractArrow arrow = data.arrow;

            if (!isArrowValid(arrow)) {
                toRemove.add(key);
                continue;
            }

            data.ticksAlive++;

            // 出弓瞬间保留自然弹道
            if (data.ticksAlive <= ModConfig.initialDelayTicks()) {
                continue;
            }

            // 超时后停止追踪，恢复普通箭矢行为
            if (data.ticksAlive >= ModConfig.maxTrackingTicks()) {
                debugTick(arrow, "超过最大追踪时间，解除追踪");
                toRemove.add(key);
                continue;
            }

            // 落地/卡住检测：必须放在速度检查之前。
            // 制导每刻都会覆写速度，因此 deltaMovement 无法反映箭矢是否真的停住；
            // 这里比较「本次与上次更新时的实际位置」——位置由引擎在 move() 中推进，
            // 我们改速度不会影响它。这样三叉戟落地后会立即让位给忠诚附魔，
            // 普通箭矢插进方块后也不再回头瞄准目标。
            Vec3 currentPos = arrow.position();
            Vec3 lastPos = data.lastPos;
            data.lastPos = currentPos;

            // 首次记录时没有可比对的基准，直接跳过判定
            double moveSqr = lastPos == null ? Double.MAX_VALUE : currentPos.distanceToSqr(lastPos);

            if (moveSqr < STATIONARY_DELTA_SQR) {
                if (++data.stationaryUpdates >= STATIONARY_UPDATES_TO_STOP) {
                    debugTick(arrow, "已停止移动（位置停滞 {} 次更新），提前解除追踪", data.stationaryUpdates);
                    toRemove.add(key);
                    continue;
                }
            } else {
                // 只要位置还在变化，就重新开始计数
                data.stationaryUpdates = 0;
            }

            // 箭矢几乎停滞时没有继续制导的意义
            double speed = arrow.getDeltaMovement().length();
            if (speed < ModConfig.minTrackingSpeed()) {
                debugTick(arrow, "速度过低（{}），解除追踪", String.format("%.3f", speed));
                toRemove.add(key);
                continue;
            }

            // 记录速度峰值，用于后续限速
            if (speed > data.maxSpeed) {
                data.maxSpeed = speed;
            }

            performHomingLogic(data);
        }

        for (UUID key : toRemove) {
            TRACKING_ARROWS.remove(key);
        }
    }

    /** 判断箭矢是否仍然值得追踪。 */
    private static boolean isArrowValid(AbstractArrow arrow) {
        if (arrow == null || !arrow.isAlive() || arrow.isRemoved()) {
            return false;
        }
        // 追踪只在服务端推进
        if (arrow.level().isClientSide()) {
            return false;
        }
        // 只在世界中有加载区块的箭矢才继续追踪
        return arrow.getPersistentData().getBoolean(TAG_LOCK_ON_ARROW);
    }

    // ------------------------------------------------------------------
    // 制导逻辑
    // ------------------------------------------------------------------

    private static void performHomingLogic(ArrowTrackingData data) {
        AbstractArrow arrow = data.arrow;

        // 已有目标且仍然有效：直接飞向它，完全不做实体查询
        if (data.currentTarget != null) {
            if (isTargetValid(data.currentTarget, arrow, data)) {
                flyDirectlyToTarget(arrow, data.currentTarget, data);
                return;
            }
            if (ModConfig.debugMode()) {
                debugTick(arrow, "目标 {} 失效，重新搜索", data.currentTarget.getName().getString());
            }
            data.currentTarget = null;
        }

        // 按 searchInterval 节流目标搜索
        if (data.ticksAlive < data.nextSearchTick) {
            return;
        }
        data.nextSearchTick = data.ticksAlive + ModConfig.searchIntervalTicks();

        LivingEntity newTarget = findNewTarget(arrow, data);
        if (newTarget != null) {
            data.currentTarget = newTarget;
            flyDirectlyToTarget(arrow, newTarget, data);
        }
        // 未找到目标时保持原有弹道
    }

    /** 目标是否仍然值得追踪。 */
    private static boolean isTargetValid(LivingEntity target, AbstractArrow arrow, ArrowTrackingData data) {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        // 硬性规则：永远不锁定自己（射手）
        Entity owner = arrow.getOwner();
        if (owner != null && target.getUUID().equals(owner.getUUID())) {
            return false;
        }
        // 已命中过的目标不再重复追踪（穿透附魔场景）
        if (data.hitTargets.contains(target.getUUID())) {
            return false;
        }
        // 创造模式玩家免疫
        if (target instanceof Player player && player.isCreative()) {
            return false;
        }
        // 全局白名单：个体（UUID）与种族（EntityType）各一次 O(1) 查表。
        // 放在这里还顺带覆盖了「已被锁定的目标中途被加入白名单」——
        // 本方法返回 false 后 currentTarget 会被置空并重新搜索。
        if (HomingWhitelist.isWhitelisted(target)) {
            return false;
        }
        // 超出追踪范围
        double range = ModConfig.maxTrackingRange();
        if (arrow.position().distanceToSqr(target.position()) > range * range) {
            return false;
        }
        // 不朝向目标时不再继续跟（避免箭矢原地打转）
        return isWithinTrackingCone(arrow, target);
    }

    /** 目标是否位于以箭矢飞行方向为中心的锥形区域内。 */
    private static boolean isWithinTrackingCone(AbstractArrow arrow, LivingEntity target) {
        double coneCosine = ModConfig.coneCosine();
        // 锥形范围放宽到整球时无需计算夹角
        if (coneCosine <= -1.0D) {
            return true;
        }
        Vec3 motion = arrow.getDeltaMovement();
        double motionLengthSqr = motion.lengthSqr();
        if (motionLengthSqr < 1.0E-6D) {
            return true;
        }
        Vec3 toTarget = target.position().subtract(arrow.position());
        double distance = toTarget.length();
        if (distance < 1.0E-4D) {
            return true;
        }
        double cosine = motion.dot(toTarget) / (Math.sqrt(motionLengthSqr) * distance);
        return cosine >= coneCosine;
    }

    /**
     * 在追踪范围内寻找最近的候选目标。
     * <p>
     * 优化点：先按距离挑选最近的候选者，只对候选者执行完整校验，避免对范围内每个实体
     * 都做锥形/免疫等判断，也不产生多余的临时集合。
     */
    private static LivingEntity findNewTarget(AbstractArrow arrow, ArrowTrackingData data) {
        Level level = arrow.level();
        Vec3 arrowPos = arrow.position();
        double range = ModConfig.maxTrackingRange();

        AABB searchBox = new AABB(
                arrowPos.x - range, arrowPos.y - range, arrowPos.z - range,
                arrowPos.x + range, arrowPos.y + range, arrowPos.z + range
        );

        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, searchBox);
        if (candidates.isEmpty()) {
            return null;
        }

        // 收集候选者及其距离（每个只计算一次）
        List<Candidate> sorted = new ArrayList<>(candidates.size());
        for (LivingEntity candidate : candidates) {
            sorted.add(new Candidate(candidate, arrowPos.distanceToSqr(candidate.position())));
        }
        // 最近的排在前面，保证优先锁定最近的目标
        sorted.sort(null);

        for (Candidate candidate : sorted) {
            if (isTargetValid(candidate.entity(), arrow, data)) {
                return candidate.entity();
            }
        }
        return null;
    }

    /** 搜索候选者：实体 + 与箭矢的距离平方，用于按距离排序。 */
    private record Candidate(LivingEntity entity, double distanceSqr) implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate other) {
            return Double.compare(this.distanceSqr, other.distanceSqr);
        }
    }

    // ------------------------------------------------------------------
    // 飞行控制
    // ------------------------------------------------------------------

    /** 直接飞向目标，保持速度（受速度上限约束）。 */
    private static void flyDirectlyToTarget(AbstractArrow arrow, LivingEntity target, ArrowTrackingData data) {
        Vec3 targetPos = predictTargetPosition(arrow, target);
        Vec3 arrowPos = arrow.position();

        Vec3 directVector = targetPos.subtract(arrowPos);
        if (directVector.lengthSqr() < 1.0E-8D) {
            return;
        }
        directVector = directVector.normalize();

        double currentSpeed = arrow.getDeltaMovement().length();
        if (currentSpeed > data.maxSpeed) {
            data.maxSpeed = currentSpeed;
        }

        // 速度上限：历史上观察到的最大速度 × 倍率。
        // 取历史最大值可以兼容 Forge 在箭矢进入世界后才派发附魔加成的情况，
        // 同时严格限制追踪过程中不会被额外加速。
        double speedLimit = data.maxSpeed * ModConfig.maxSpeedMultiplier();
        double targetSpeed = Math.min(currentSpeed, speedLimit);
        // 速度过低时给一个下限，避免制导失效
        if (targetSpeed < 0.5D) {
            targetSpeed = Math.min(0.5D, speedLimit);
        }

        Vec3 newMotion = directVector.scale(targetSpeed);
        arrow.setDeltaMovement(newMotion);
        arrow.hasImpulse = true;

        // 同步朝向，让箭身与飞行方向一致
        updateArrowRotation(arrow, newMotion);

        // 可选粒子效果
        if (ModConfig.showParticles()
                && data.ticksAlive % ModConfig.particleIntervalTicks() == 0
                && arrow.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.CRIT,
                    arrow.getX(), arrow.getY(), arrow.getZ(),
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 线性预测目标位置，并按配置加入提前量。 */
    private static Vec3 predictTargetPosition(AbstractArrow arrow, LivingEntity target) {
        Vec3 targetPos = target.position();
        double predictionFactor = ModConfig.predictionFactor();

        if (predictionFactor > 0.0D) {
            Vec3 targetVelocity = target.getDeltaMovement();
            double arrowSpeed = arrow.getDeltaMovement().length();
            if (arrowSpeed > 0.1D) {
                double timeToReach = arrow.position().distanceTo(targetPos) / arrowSpeed;
                targetPos = targetPos.add(targetVelocity.scale(timeToReach * predictionFactor));
            }
        }

        // 瞄准目标身体中部，而不是脚底
        return targetPos.add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
    }

    /** 更新箭矢的旋转角度。 */
    private static void updateArrowRotation(AbstractArrow arrow, Vec3 motion) {
        double horizontalDist = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        float yRot = (float) (Math.atan2(motion.x, motion.z) * (180.0D / Math.PI));
        float xRot = (float) (Math.atan2(motion.y, horizontalDist) * (180.0D / Math.PI));

        arrow.setYRot(yRot);
        arrow.setXRot(xRot);
        arrow.yRotO = yRot;
        arrow.xRotO = xRot;
    }

    // ------------------------------------------------------------------
    // 命中处理
    // ------------------------------------------------------------------

    /** 处理箭矢命中实体（用于穿透附魔的连续追踪）。 */
    public static void onArrowHitEntity(AbstractArrow arrow, LivingEntity hitEntity) {
        if (arrow == null || hitEntity == null) {
            return;
        }
        ArrowTrackingData data = TRACKING_ARROWS.get(arrow.getUUID());
        if (data == null) {
            return;
        }

        data.hitTargets.add(hitEntity.getUUID());
        if (data.currentTarget != null && data.currentTarget.getUUID().equals(hitEntity.getUUID())) {
            data.currentTarget = null;
        }
        // 立即允许重新搜索，穿透后马上接上下一个目标
        data.nextSearchTick = data.ticksAlive;

        if (ModConfig.debugMode()) {
            debugTick(arrow, "命中目标 {}", hitEntity.getName().getString());
        }
    }

    // ------------------------------------------------------------------
    // 调试输出
    // ------------------------------------------------------------------

    /** 限流调试输出：同一类型每 20 刻最多一条，避免刷屏。 */
    private static void debugTick(AbstractArrow arrow, String message, Object... args) {
        if (!ModConfig.debugMode()) {
            return;
        }
        if (arrow.tickCount % 20 != 0) {
            return;
        }
        HomingArrowsMod.LOGGER.info("[追踪箭] " + message, args);
    }
}
