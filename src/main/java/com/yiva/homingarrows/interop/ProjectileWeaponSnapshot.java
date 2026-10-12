package com.yiva.homingarrows.interop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;

/**
 * 弹体上的"源武器快照"。
 * <p>
 * 箭矢实体本身不保存发射它的那把弓/弩，原版的效果（力量、冲击、火矢）都是在发射瞬间一次性写进
 * 弹体属性的，一旦箭离弦就再也拿不到武器上的其它附魔了。互通要补的伤害、抢掠、引雷等项目必须在
 * <b>命中时</b>才知道目标是谁（亡灵？节肢？水生？），因此这里在发射瞬间把武器整份存进弹体的持久化
 * 数据，命中时再读出来。
 * <p>
 * 这样做还有一个额外好处：玩家射出箭后立刻切手、丢下武器甚至死亡，都不影响这次命中的结算。
 * 只有在武器上真的存在"需要补实现"的附魔时才写入，普通弓弩不产生任何额外 NBT 开销。
 */
public final class ProjectileWeaponSnapshot {

    /** 源武器在弹体持久化数据中的键。 */
    private static final String TAG_SOURCE_WEAPON = "ranged_enchant_expansion:sourceWeapon";

    private ProjectileWeaponSnapshot() {
    }

    /** 发射瞬间记录源武器（只在必要时写入）。 */
    public static void capture(AbstractArrow arrow, ItemStack weapon) {
        if (arrow == null || weapon == null || weapon.isEmpty()) {
            return;
        }
        if (!EnchantmentInterop.hasInteropRelevantEnchantment(weapon)) {
            return;
        }
        arrow.getPersistentData().put(TAG_SOURCE_WEAPON, weapon.copy().save(new CompoundTag()));
    }

    /**
     * 读取弹体对应的源武器。
     * <p>
     * 三叉戟实体自带物品本体（{@code tridentItem}），即使没有快照也能兜底，
     * 因此发射器抛出、或由其它模组生成的三叉戟同样能吃到互通效果。
     */
    public static ItemStack get(AbstractArrow arrow) {
        if (arrow == null) {
            return ItemStack.EMPTY;
        }
        CompoundTag data = arrow.getPersistentData();
        if (data.contains(TAG_SOURCE_WEAPON, Tag.TAG_COMPOUND)) {
            ItemStack stored = ItemStack.of(data.getCompound(TAG_SOURCE_WEAPON));
            if (!stored.isEmpty()) {
                return stored;
            }
        }
        if (arrow instanceof TridentAccess access) {
            ItemStack own = access.homingarrows$getTridentItem();
            if (own != null && !own.isEmpty()) {
                return own;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 该弹体是否已经记录了源武器。 */
    public static boolean hasSnapshot(AbstractArrow arrow) {
        return arrow != null && arrow.getPersistentData().contains(TAG_SOURCE_WEAPON, Tag.TAG_COMPOUND);
    }
}
