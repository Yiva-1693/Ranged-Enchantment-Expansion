package com.yiva.homingarrows.weightless;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 失重附魔的轨迹管理：让被标记的箭矢/三叉戟走直线而非抛物线。
 * <p>
 * 原版 {@code AbstractArrow.tick()} 对弹体做两件事，都会破坏直线飞行：
 * <ol>
 *   <li><b>重力</b>：{@code deltaMovement.y -= 0.05}。这段带原版开关
 *       {@code if (!isNoGravity() && ...)}，因此 {@code setNoGravity(true)} 就能从源头关掉；
 *       它还是同步数据 {@code DATA_NO_GRAVITY}，客户端同样生效。</li>
 *   <li><b>拖拽</b>：{@code deltaMovement = deltaMovement.scale(0.99)}，<b>无条件执行</b>
 *       （反编译确认 {@code isNoGravity} 在 tick() 中只出现一次，只管重力那段）。
 *       每刻掉 1%，60 刻后只剩约 0.55 —— 这就是"越飞越慢"。</li>
 * </ol>
 * 拖拽没有原版开关，所以这里每刻把速度<b>直接覆盖回初速度</b>。
 * <p>
 * <b>两个踩过的坑，改代码时务必保留：</b>
 * <ul>
 *   <li><b>不要设置 {@code hasImpulse}</b>：每刻置位会每刻强制向客户端广播一次速度，
 *       与客户端自身的运动预测互相打架，表现为「原地疯狂颤抖」。</li>
 *   <li><b>不要在遍历中改动 {@code TRACKED}</b>：之前两次服务器崩溃
 *       （{@code ConcurrentModificationException}，分别抛在
 *       {@code HashIterator.remove} 与 {@code nextNode}）都是这个原因。
 *       这里用 key 快照遍历、循环结束后统一移除。</li>
 * </ul>
 * <p>
 * 三叉戟额外限制：只在出手后的配置时长内失重（默认 8 秒），之后恢复重力与抛物线，
 * 避免三叉戟一去不返。
 */
public final class WeightlessProjectileManager {

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();

    private WeightlessProjectileManager() {
    }

    /** 单个失重弹的运行状态。 */
    private static final class Tracked {
        final AbstractArrow projectile;
        /** 出射速度，生成时记录一次，之后每刻覆盖回去。 */
        final Vec3 launchVelocity;
        int ticks;

        Tracked(AbstractArrow projectile, Vec3 launchVelocity) {
            this.projectile = projectile;
            this.launchVelocity = launchVelocity;
        }
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /**
     * 让一支弹体失重。重复调用安全。
     * <p>
     * 必须在「生成时」调用：此刻弓/弩已把速度写好，实体一次都没 tick，速度是干净的
     * 未受重力与拖拽影响的出射速度。
     */
    public static void markWeightless(AbstractArrow projectile) {
        if (projectile == null || TRACKED.containsKey(projectile.getUUID())) {
            return;
        }

        Vec3 launch = projectile.getDeltaMovement();
        // 重力从源头关掉
        projectile.setNoGravity(true);
        TRACKED.put(projectile.getUUID(), new Tracked(projectile, launch));

        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[失重] 已关闭重力，出射速度 {}（{} 格/刻）",
                    launch, String.format("%.3f", launch.length()));
        }
    }

    /** 恢复某支弹体的重力（三叉戟失重窗口结束时使用）。 */
    public static void restoreGravity(AbstractArrow projectile) {
        if (projectile != null && !projectile.isRemoved()) {
            projectile.setNoGravity(false);
        }
    }

    /** 移除追踪（不改变重力状态）。 */
    public static void remove(AbstractArrow projectile) {
        if (projectile != null) {
            TRACKED.remove(projectile.getUUID());
        }
    }

    /** 清空（服务器停止时调用）。 */
    public static void clearAll() {
        TRACKED.clear();
    }

    /** 当前受管理的弹体数量（调试用）。 */
    public static int trackedCount() {
        return TRACKED.size();
    }

    // ------------------------------------------------------------------
    // 每刻更新
    // ------------------------------------------------------------------

    /** 由服务端 tick 驱动（END 相位，即实体 tick 之后，能覆盖掉原版施加的重力与拖拽）。 */
    public static void updateAll() {
        if (TRACKED.isEmpty()) {
            return;
        }

        int tridentWindow = ModConfig.weightlessTridentTicks();
        int airLifetimeTicks = ModConfig.weightlessAirLifetimeTicks();
        boolean debug = ModConfig.debugMode();

        // 用 key 快照遍历，绝不在遍历中改动 TRACKED（避免 ConcurrentModificationException）
        List<UUID> keys = new ArrayList<>(TRACKED.keySet());
        List<UUID> toRemove = new ArrayList<>();

        for (UUID key : keys) {
            Tracked tracked = TRACKED.get(key);
            if (tracked == null) {
                continue;
            }
            AbstractArrow projectile = tracked.projectile;

            if (projectile == null || !projectile.isAlive() || projectile.isRemoved()
                    || projectile.level().isClientSide()) {
                toRemove.add(key);
                continue;
            }

            tracked.ticks++;

            // 三叉戟：失重窗口结束 → 还回重力，交还原版抛物线
            if (projectile instanceof ThrownTrident && tracked.ticks > tridentWindow) {
                restoreGravity(projectile);
                toRemove.add(key);
                if (debug) {
                    HomingArrowsMod.LOGGER.info("[失重] 三叉戟失重窗口结束，恢复重力与抛物线");
                }
                continue;
            }

            // 防无限飞行：直线弹道永远不会自己落下来，必须给空中寿命上限。
            // 参照原版对插地箭矢的 1200 刻（60 秒）处置。
            if (airLifetimeTicks > 0 && tracked.ticks > airLifetimeTicks) {
                if (debug) {
                    HomingArrowsMod.LOGGER.info("[失重] 超过空中寿命 {} 刻，销毁弹体", tracked.ticks);
                }
                projectile.discard();
                toRemove.add(key);
                continue;
            }

            // 核心：把速度覆盖回初速度，抵消原版每刻的 drag（deltaMovement *= 0.99）。
            // 已插地/入水的弹体不动它，免得把插在方块里的箭拔出来。
            if (!projectile.onGround() && !projectile.isInWater()) {
                Vec3 current = projectile.getDeltaMovement();
                // 只在偏差明显时才纠正：每刻无条件写速度会每刻广播一次运动包，
                // 与客户端预测互相打架，表现为「原地疯狂颤抖」。
                if (current.distanceToSqr(tracked.launchVelocity) > 1.0E-6D) {
                    projectile.setDeltaMovement(tracked.launchVelocity);
                    // hasImpulse 让服务端把这次速度变化同步给客户端。
                    // 缺了它客户端会一直用自己被 drag 衰减到接近 0 的速度演算，
                    // 表现为「先卡住、再被位置包拽到前面」的向前闪现。
                    projectile.hasImpulse = true;
                }
            }

            // 速度观测：应恒定等于出射速度
            if (debug && tracked.ticks % 20 == 0) {
                HomingArrowsMod.LOGGER.info("[失重] 第 {} 刻：当前速度 {}，出射速度 {}",
                        tracked.ticks,
                        String.format("%.3f", projectile.getDeltaMovement().length()),
                        String.format("%.3f", tracked.launchVelocity.length()));
            }
        }

        for (UUID key : toRemove) {
            TRACKED.remove(key);
        }
    }
}
