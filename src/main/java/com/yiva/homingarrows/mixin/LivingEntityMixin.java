package com.yiva.homingarrows.mixin;

import com.yiva.homingarrows.interop.LivingEntityAccess;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link LivingEntity} 的 {@code useItemRemaining} 字段。
 * <p>
 * 仅用于弓的快速装填：直接改写这个字段才能同时加快"拉满弓"的进度与第一人称拉弓动画，
 * 只在代码里补正威力会出现"动画没拉满、伤害却已满"的错位。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityMixin extends LivingEntityAccess {

    /** 对应 protected 的 {@code useItemRemaining} 字段。 */
    @Override
    @Accessor("useItemRemaining")
    void homingarrows$setUseItemRemaining(int ticks);
}
