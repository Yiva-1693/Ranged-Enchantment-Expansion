package com.yiva.homingarrows.weightless;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import com.yiva.homingarrows.projectile.AbstractArrowAccess;
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
 *       {@code if (!isNoGravity() && ...)}，因此 {@code setNoGravity(true)} 即可从源头关掉；
 *       它还是同步数据 {@code DATA_NO_GRAVITY}，客户端同样生效。</li>
 *   <li><b>拖拽</b>：{@code deltaMovement = deltaMovement.scale(0.99)}，<b>无条件执行</b>
 *       （反编译确认 {@code isNoGravity} 在整个 tick() 中只出现一次，只管重力那段）。
 *       每刻掉 1%，60 刻后只剩约 0.55 —— 这就是"越飞越慢"。拖拽没有原版开关，
 *       只能每刻把速度覆盖回初速度来抵消。</li>
 * </ol>
 * <p>
 * <b>与忠诚附魔的冲突（务必保留停止条件）：</b>忠诚每刻给三叉戟施加"朝主人飞"的牵引
 * （{@code deltaMovement = deltaMovement * 0.95 + dir * 0.05 * level}）。如果我们一直把
 * 速度覆盖成出射速度，就会把这个牵引逐刻抹掉，三叉戟既不减速也不回头 —— 表现为
 * "装了失重就飞走收不回来"。因此一旦弹体<b>停下（插进方块/落地）</b>就立刻停止失重干预，
 * 把控制权完整交还忠诚，不必等失重窗口结束。
 * <p>
 * 顺带说明原版机制：忠诚的返回并不要求插地 —— 反编译可见只要
 * {@code loyalty > 0} 且（{@code dealtDamage} 或 {@code isNoPhysics}）且主人可接受即可返回，
 * 所以原版忠诚 3 即使扔向虚空也会自己飞回（主人需存活、非观察者、同维度）。
 * <p>
 * 三叉戟额外限制：只在出手后的配置时长内失重（默认 3 秒），之后恢复重力与抛物线。
 * 另外一旦插进方块/落地也会立即停止干预（见下方"与忠诚附魔的冲突"），
 * 所以实际失重距离通常比这个窗口更短。
 */
public final class WeightlessProjectileManager {

    private static final Map<UUID, Tracked> TRACKED = new HashMap<>();

    /** 位置停滞判定阈值（格²）。飞行中每刻位移远超此值，只有停住才会低于它。 */
    private static final double STATIONARY_DELTA_SQR = 1.0E-4D;

    private WeightlessProjectileManager() {
    }

    /** 单个失重弹的运行状态。 */
    private static final class Tracked {
        final AbstractArrow projectile;
        /** 出射速度，生成时记录一次，之后每刻覆盖回去以抵消拖拽。 */
        final Vec3 launchVelocity;
        /** 上一次 tick 的位置，用于检测"停住了"。 */
        Vec3 lastPos;
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
        // 重力从源头关掉（同步数据，客户端同样生效）
        projectile.setNoGravity(true);
        TRACKED.put(projectile.getUUID(), new Tracked(projectile, launch));

        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[失重] 已关闭重力，出射速度 {}（{} 格/刻）",
                    launch, String.format("%.3f", launch.length()));
        }
    }

    /** 恢复某支弹体的重力。 */
    public static void restoreGravity(AbstractArrow projectile) {
        if (projectile != null && !projectile.isRemoved()) {
            projectile.setNoGravity(false);
        }
    }

    /** 停止失重干预并还回重力，把控制权交还原版（忠诚等）。 */
    private static void stopWeightless(UUID key, Tracked tracked, String reason) {
        restoreGravity(tracked.projectile);
        TRACKED.remove(key);
        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[失重] 停止失重干预（{}），交还原版物理", reason);
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

            // 落地当刻就交还原版，避免在位置停滞检测生效前触发空中寿命清理。
            if (projectile instanceof AbstractArrowAccess access && access.homingarrows$isInGround()) {
                stopWeightless(key, tracked, "已插入方块/落地");
                continue;
            }

            tracked.ticks++;

            // 停住了（插进方块/落地）→ 立刻停止干预，把控制权交还忠诚。
            // 位置停滞判定与追踪用的是同一套；不能用 onGround()，因为
            // AbstractArrow.tick() 只写 inGround，从不调用 setOnGround。
            Vec3 currentPos = projectile.position();
            boolean stationary = tracked.lastPos != null
                    && currentPos.distanceToSqr(tracked.lastPos) < STATIONARY_DELTA_SQR;
            tracked.lastPos = currentPos;

            if (stationary) {
                stopWeightless(key, tracked, "已插入方块/落地");
                continue;
            }

            // 三叉戟：失重窗口结束 → 还回重力，交还原版抛物线
            if (projectile instanceof ThrownTrident && tracked.ticks > tridentWindow) {
                stopWeightless(key, tracked, "三叉戟失重窗口结束");
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
            // 注意必须每刻覆盖——忠诚的牵引也会被一起抹掉，所以上面必须先判"停住"。
            Vec3 current = projectile.getDeltaMovement();
            if (current.distanceToSqr(tracked.launchVelocity) > 1.0E-6D) {
                projectile.setDeltaMovement(tracked.launchVelocity);
                // hasImpulse 让服务端把这次速度变化同步给客户端。
                // 缺了它客户端会按自己被 drag 衰减的速度演算，表现为"先卡住再被拽到前面"的闪现。
                projectile.hasImpulse = true;
            }
        }

        for (UUID key : toRemove) {
            TRACKED.remove(key);
        }
    }
}
