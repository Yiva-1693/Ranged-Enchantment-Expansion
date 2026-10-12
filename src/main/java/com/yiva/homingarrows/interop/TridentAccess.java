package com.yiva.homingarrows.interop;

import net.minecraft.world.item.ItemStack;

/**
 * 由 {@link com.yiva.homingarrows.mixin.ThrownTridentMixin} 注入到
 * {@link net.minecraft.world.entity.projectile.ThrownTrident} 的访问入口。
 * <p>
 * 原版这两个成员都是私有的，互通需要它们：
 * <ul>
 *   <li>{@code tridentItem}：三叉戟自带的那份物品，用于在没有快照时兜底读取附魔；</li>
 *   <li>{@code dealtDamage}：原版命中后置位，会阻止同一支三叉戟再次命中任何实体
 *       （{@code findHitEntity} 直接返回 null），穿透需要每刻把它复位。</li>
 * </ul>
 */
public interface TridentAccess {

    /** 读取三叉戟实体携带的物品本体。 */
    ItemStack homingarrows$getTridentItem();

    /** 复位"已造成伤害"标记，让三叉戟可以继续穿透命中下一个目标。 */
    void homingarrows$setDealtDamage(boolean dealt);
}
