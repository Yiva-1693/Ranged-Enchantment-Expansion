package com.yiva.homingarrows.enchantment;

import com.yiva.homingarrows.interop.EnchantmentInterop;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * "追踪"附魔：附有此附魔的弓、弩射出的箭矢，以及投掷出的三叉戟，会自动追踪附近的目标。
 * <p>
 * 单级附魔，仅作用于主手，不限制宝藏专属，因此可以通过附魔台、战利品和村民交易获得。
 */
public class LockOnEnchantment extends Enchantment {

    public LockOnEnchantment() {
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
        return 15;
    }

    @Override
    public int getMaxCost(int level) {
        return 50;
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
