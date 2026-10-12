package com.yiva.homingarrows.mixin;

import com.yiva.homingarrows.interop.TridentAccess;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把 {@link ThrownTrident} 的两个私有成员暴露给互通逻辑。
 * <p>
 * 用接口式访问器（accessor mixin）：Mixin 会把本接口挂到 {@code ThrownTrident} 的 implements
 * 列表上，因此代码里可以直接 {@code arrow instanceof TridentAccess} 判断。
 */
@Mixin(ThrownTrident.class)
public interface ThrownTridentMixin extends TridentAccess {

    /** 对应私有的 {@code tridentItem} 字段。 */
    @Override
    @Accessor("tridentItem")
    ItemStack homingarrows$getTridentItem();

    /** 对应私有的 {@code dealtDamage} 字段，穿透时需要每刻复位。 */
    @Override
    @Accessor("dealtDamage")
    void homingarrows$setDealtDamage(boolean dealt);
}
