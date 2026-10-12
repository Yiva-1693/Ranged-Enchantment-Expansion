package com.yiva.homingarrows.enchantment;

import com.yiva.homingarrows.interop.EnchantmentInterop;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * 「失重」附魔：被附魔的弓/弩射出的箭矢走直线而非抛物线。
 * <p>
 * 三叉戟同样受此附魔影响，但只在出手后的一段时间内失重（见
 * {@link com.yiva.homingarrows.weightless.WeightlessProjectileManager}），
 * 之后恢复抛物线，避免三叉戟一去不返。
 * <p>
 * 单级附魔，主手生效，可通过附魔台与战利品获得。
 */
public class WeightlessEnchantment extends Enchantment {

    public WeightlessEnchantment() {
        super(Rarity.RARE, EnchantmentCategory.BOW, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
    }

    @Override
    public int getMinLevel() {
        return 1;
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public int getMinCost(int level) {
        return 20;
    }

    @Override
    public int getMaxCost(int level) {
        return 55;
    }

    /** 兼容弓、弩与三叉戟，其余物品不可附魔。 */
    @Override
    public boolean canEnchant(ItemStack stack) {
        return EnchantmentInterop.isInteropWeapon(stack);
    }

    @Override
    public boolean isTreasureOnly() {
        return false;
    }

    @Override
    public boolean isTradeable() {
        return true;
    }

    @Override
    public boolean isDiscoverable() {
        return true;
    }
}
