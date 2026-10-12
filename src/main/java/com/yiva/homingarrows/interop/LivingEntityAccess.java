package com.yiva.homingarrows.interop;

/**
 * 由 {@link com.yiva.homingarrows.mixin.LivingEntityMixin} 注入到
 * {@link net.minecraft.world.entity.LivingEntity} 的访问入口。
 * <p>
 * 原版 {@code useItemRemaining} 是 protected 字段，且只提供读取用的
 * {@code getUseItemRemainingTicks()}。弓的"拉满进度"与第一人称拉弓动画都直接读它，
 * 因此快速装填要真正加快拉满速度，就必须能改写这个字段。
 */
public interface LivingEntityAccess {

    /** 改写"使用物品剩余刻数"；数值越小表示这一动作推进得越靠后。 */
    void homingarrows$setUseItemRemaining(int ticks);
}
