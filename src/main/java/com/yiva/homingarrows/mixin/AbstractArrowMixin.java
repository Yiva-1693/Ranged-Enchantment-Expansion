package com.yiva.homingarrows.mixin;

import com.yiva.homingarrows.projectile.AbstractArrowAccess;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 供空中寿命与失重管理器区分飞行和落地状态。 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowMixin extends AbstractArrowAccess {

    @Override
    @Accessor("inGround")
    boolean homingarrows$isInGround();
}
