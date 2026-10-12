package com.yiva.homingarrows.projectile;

import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.world.entity.projectile.AbstractArrow;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 弓箭/三叉戟的「离弦计时」与空中寿命上限。
 * <p>
 * <b>为什么需要它：</b>反编译确认原版 {@code AbstractArrow.tickDespawn()} 只在
 * {@code inGround && !level.isClientSide} 分支里被调用，也就是说箭矢那 1200 刻
 * （60 秒）寿命<strong>只在插在方块里时累加</strong>，纯空中飞行没有任何上限；
 * 所属的 {@code AbstractHurtingProjectile}（烈焰弹等）更是完全没有寿命逻辑。
 * 直线弹道永远不会自己落下来，因此必须有这条兜底规则，否则实体只会不断堆积。
 * <p>
 * 这里对所有玩家射出的箭矢/三叉戟统一计时（不只是失重弹），达到配置秒数即丢弃。
 * 射中方块后箭矢会插住，原版自己的落地清除逻辑会接管，所以这里不必区分。
 */
public final class ProjectileLifetimeManager {

    /** 键为弹体 UUID，值为已飞行刻数。 */
    private static final Map<UUID, Counter> TRACKED = new HashMap<>();

    private ProjectileLifetimeManager() {
    }

    private static final class Counter {
        final AbstractArrow projectile;
        int ticks;

        Counter(AbstractArrow projectile) {
            this.projectile = projectile;
        }
    }

    /** 开始为某支弹体计时。重复调用安全。 */
    public static void track(AbstractArrow projectile) {
        if (projectile == null || TRACKED.containsKey(projectile.getUUID())) {
            return;
        }
        TRACKED.put(projectile.getUUID(), new Counter(projectile));
    }

    /** 停止计时（弹体离开世界时调用）。 */
    public static void remove(AbstractArrow projectile) {
        if (projectile != null) {
            TRACKED.remove(projectile.getUUID());
        }
    }

    /** 清空（服务器停止时调用）。 */
    public static void clearAll() {
        TRACKED.clear();
    }

    /**
     * 由服务端 tick 驱动，推进所有弹体的离弦计时。
     */
    public static void updateAll() {
        if (TRACKED.isEmpty()) {
            return;
        }

        int lifetimeTicks = ModConfig.projectileAirLifetimeTicks();

        // 用 key 快照遍历、循环外统一移除，避免 ConcurrentModificationException
        var keys = new java.util.ArrayList<>(TRACKED.keySet());
        var toRemove = new java.util.ArrayList<UUID>();

        for (UUID key : keys) {
            Counter counter = TRACKED.get(key);
            if (counter == null) {
                continue;
            }
            AbstractArrow projectile = counter.projectile;

            if (projectile == null || !projectile.isAlive() || projectile.isRemoved()
                    || projectile.level().isClientSide()) {
                toRemove.add(key);
                continue;
            }

            counter.ticks++;

            if (lifetimeTicks > 0 && counter.ticks > lifetimeTicks) {
                projectile.discard();
                toRemove.add(key);
            }
        }

        for (UUID key : toRemove) {
            TRACKED.remove(key);
        }
    }
}
