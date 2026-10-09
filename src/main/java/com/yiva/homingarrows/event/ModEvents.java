package com.yiva.homingarrows.event;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import com.yiva.homingarrows.init.ModEnchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 追踪箭的事件入口。
 * <p>
 * 注意：本类通过 {@link Mod.EventBusSubscriber} 由 Forge 自动注册，
 * <b>不要再在模组主类里手动注册</b>，否则事件会被重复派发。
 */
@Mod.EventBusSubscriber(modid = HomingArrowsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModEvents {

    private ModEvents() {
    }

    // ------------------------------------------------------------------
    // 发射检测
    // ------------------------------------------------------------------

    /**
     * 箭矢进入世界时判断是否应该成为追踪箭。
     * <p>
     * 这里不再依赖 {@code player.getUseItem()}：该值在射箭瞬间可能已经被清空或切手，
     * 导致追踪静默失效。改为直接检查玩家双手持有武器的附魔等级。
     */
    @SubscribeEvent
    public static void onArrowFired(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        Entity owner = arrow.getOwner();
        if (!(owner instanceof Player player)) {
            return;
        }

        int lockOnLevel = getLockOnLevel(player);
        if (lockOnLevel <= 0) {
            return;
        }

        // 标记为追踪箭，并设置为单人锁定模式
        arrow.getPersistentData().putBoolean(HomingArrowManager.TAG_LOCK_ON_ARROW, true);
        HomingArrowManager.addArrow(arrow);

        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[锁头箭] 玩家 {} 射出了追踪箭（附魔等级 {}，搜索半径 {} 格，锥角 {} 度）",
                    player.getName().getString(),
                    lockOnLevel,
                    String.format("%.1f", ModConfig.maxTrackingRange()),
                    String.format("%.1f", Math.toDegrees(Math.acos(ModConfig.coneCosine()))));
        }
    }

    /**
     * 读取玩家身上的锁头附魔等级，兼容弓与弩。
     * <p>
     * 优先看正在使用的物品（拉弓/弩上弦时最准确），随后回退到主手、副手。
     */
    private static int getLockOnLevel(Player player) {
        int level = getLevelFrom(player.getUseItem());
        if (level > 0) {
            return level;
        }
        level = getLevelFrom(player.getMainHandItem());
        if (level > 0) {
            return level;
        }
        return getLevelFrom(player.getOffhandItem());
    }

    private static int getLevelFrom(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        // 使用 ItemStack 上的实例方法，EnchantmentHelper.getItemEnchantmentLevel 在 1.20.1 已废弃
        return stack.getEnchantmentLevel(ModEnchantments.LOCK_ON.get());
    }

    // ------------------------------------------------------------------
    // 命中检测
    // ------------------------------------------------------------------

    /** 追踪箭命中实体时记录命中，供穿透附魔继续追踪下一个目标。 */
    @SubscribeEvent
    public static void onEntityDamaged(LivingDamageEvent event) {
        if (event.getSource().getDirectEntity() instanceof AbstractArrow arrow) {
            if (arrow.getPersistentData().getBoolean(HomingArrowManager.TAG_LOCK_ON_ARROW)) {
                HomingArrowManager.onArrowHitEntity(arrow, event.getEntity());
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期与清理
    // ------------------------------------------------------------------

    /** 箭矢离开世界（被拾取、区块卸载、跨维度、命中消失）时清理追踪数据。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow) {
            HomingArrowManager.removeArrow(arrow);
        }
    }

    /** 服务器停止时清空全部追踪数据，避免静态表残留。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HomingArrowManager.clearAll();
    }

    // ------------------------------------------------------------------
    // 每刻驱动
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 只在 END 相位推进一次，避免每刻重复制导
        if (event.phase == TickEvent.Phase.END) {
            HomingArrowManager.updateAllArrows();
        }
    }
}
