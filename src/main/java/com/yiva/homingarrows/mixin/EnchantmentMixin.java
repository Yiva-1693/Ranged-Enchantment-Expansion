package com.yiva.homingarrows.mixin;

import com.yiva.homingarrows.interop.EnchantmentInterop;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 附魔适用性互通：唯一一处 Mixin，同时覆盖三条获取路径。
 * <p>
 * 原版把"能不能附到这件物品上"锁死在 {@code EnchantmentCategory} 里，而 Forge 1.20.1 没有对应的
 * 事件可以放宽它。实际调用链（均由 Forge 1.20.1 源码核对）：
 * <ul>
 *   <li>铁砧：{@code AnvilMenu} → {@code enchantment.canEnchant(itemstack)}</li>
 *   <li>附魔台：{@code EnchantmentHelper.getAvailableEnchantmentResults}
 *       → {@code enchantment.canApplyAtEnchantingTable(stack)}</li>
 *   <li>命令：{@code EnchantCommand} → {@code enchantment.canEnchant(itemstack)}</li>
 * </ul>
 * 因此只要在这两个方法头部放行互通规则，三条路径就一起打通了。
 * <p>
 * 放行是完全单向的：{@link EnchantmentInterop#canApplyTo} 返回 true 时直接给出 true，
 * 返回 false 时不动原版结果，所以原版本来就允许的组合（弓上附力量、弩上附穿透）不受影响。
 * <p>
 * 注意：这里不注入 {@code EnchantmentCategory#canEnchant}。原版枚举常量各自重写了该方法
 * （编译成 {@code EnchantmentCategory$6}、{@code $9}、{@code $11}、{@code $13} 等匿名子类），
 * 按序号定位既脆弱又容易漏，注入 {@code Enchantment} 自身的方法更稳。
 */
@Mixin(Enchantment.class)
public abstract class EnchantmentMixin {

    /** 铁砧合并、/enchant 命令走这里。 */
    @Inject(method = "canEnchant", at = @At("HEAD"), cancellable = true)
    private void homingarrows$interopCanEnchant(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (EnchantmentInterop.canApplyTo((Enchantment) (Object) this, stack)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    /**
     * 附魔台候选池走这里。
     * <p>
     * 注意 {@code remap = false}：{@code canApplyAtEnchantingTable} 是 Forge 新增的方法，
     * 不存在于官方名→SRG 的映射表里；Forge 新增成员在生产环境本来就保持原名，
     * 因此必须关闭重映射，否则注解处理器会直接报 "Unable to locate obfuscation mapping"。
     */
    @Inject(method = "canApplyAtEnchantingTable", at = @At("HEAD"), cancellable = true, remap = false)
    private void homingarrows$interopEnchantingTable(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (EnchantmentInterop.canApplyTo((Enchantment) (Object) this, stack)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    /**
     * 放开互通范围内的原版互斥。
     * <p>
     * {@code isCompatibleWith} 是 final 的，而且是"两个附魔能不能一起附"的唯一出口——铁砧的合并、
     * {@code /enchant}、{@code EnchantmentHelper.isEnchantmentCompatible} 全都经由它。因此在这里统一
     * 放行覆盖面最全，不必逐个去改 {@code checkCompatibility}（每一对互斥都写在不同的类里，容易漏）：
     * <ul>
     *   <li>穿透 ↔ 多重射击</li>
     *   <li>锋利 ↔ 亡灵杀手 ↔ 节肢杀手</li>
     *   <li>激流 ↔ 忠诚 ↔ 引雷</li>
     *   <li>无限 ↔ 经验修补</li>
     * </ul>
     * 规则细节与开关见 {@link EnchantmentInterop#areCompatible}。
     */
    @Inject(method = "isCompatibleWith", at = @At("HEAD"), cancellable = true)
    private void homingarrows$allowInteropCombinations(Enchantment other, CallbackInfoReturnable<Boolean> cir) {
        if (EnchantmentInterop.areCompatible((Enchantment) (Object) this, other)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
