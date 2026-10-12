package com.yiva.homingarrows.interop;

import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 弓 / 弩 / 三叉戟的附魔互通规则表。
 * <p>
 * 互通分成两件事，本类只负责第一件：
 * <ol>
 *   <li><b>能不能附上去</b>：由 {@link com.yiva.homingarrows.mixin.EnchantmentMixin} 调用
 *       {@link #canApplyTo(Enchantment, ItemStack)}，一次性打通附魔台候选池、铁砧合并与
 *       {@code /enchant} 命令（原版这三条路径最终都会走 {@code Enchantment#canEnchant}
 *       与 {@code Enchantment#canApplyAtEnchantingTable}）。</li>
 *   <li><b>附上去之后有没有效果</b>：由 {@link InteropCombatHandler} 等事件监听器补齐，
 *       靠 {@link #isNative(Enchantment, WeaponKind)} 判断"这一项原版是不是已经结算过"，
 *       从而避免双重加成。</li>
 * </ol>
 * 规则与原版实现依据（Forge 1.20.1 反编译源码）：
 * <ul>
 *   <li>弓：{@code BowItem.releaseUsing} 自己把力量/冲击/火矢写进弹体，无限在 {@code ArrowItem}；</li>
 *   <li>弩：{@code CrossbowItem} 只处理穿透（发射时）与多重射击/快速装填（装填时）；</li>
 *   <li>三叉戟：{@code ThrownTrident.onHitEntity} 用 {@code EnchantmentHelper.getDamageBonus}
 *       结算穿刺与锋利系，引雷在原版命中逻辑里，忠诚/激流在 {@code TridentItem}。</li>
 * </ul>
 * 注意：{@code ArrowDamageEnchantment}（力量）不重写 {@code getDamageBonus}，所以力量对三叉戟的
 * 投掷与近战在原版都是 0 加成——这正是需求表里"投掷要加、近战不加"能自然成立的原因。
 */
public final class EnchantmentInterop {

    private EnchantmentInterop() {
    }

    /** 三种可互通的远程/投掷武器。 */
    public enum WeaponKind {
        BOW,
        CROSSBOW,
        TRIDENT,
        /** 不属于互通范围的物品。 */
        OTHER
    }

    /** 互通只放行武器类附魔，避免把"保护/荆棘/冰霜行者"之类也放到武器上。 */
    private static final Set<EnchantmentCategory> INTEROP_CATEGORIES = EnumSet.of(
            EnchantmentCategory.WEAPON,
            EnchantmentCategory.BOW,
            EnchantmentCategory.CROSSBOW,
            EnchantmentCategory.TRIDENT);

    /** 明确不互通的组合（对应需求表里"无有效对应；不建议互通"的行）。 */
    private static final Map<ResourceLocation, Set<WeaponKind>> EXCLUSIONS = new HashMap<>();

    static {
        // 三叉戟没有"弹药"与"装填"概念
        exclude("infinity", WeaponKind.TRIDENT);
        exclude("multishot", WeaponKind.TRIDENT);
        exclude("quick_charge", WeaponKind.TRIDENT);
        // 弓与弩没有"投掷后飞回"与"水中冲刺"概念
        exclude("loyalty", WeaponKind.BOW, WeaponKind.CROSSBOW);
        exclude("riptide", WeaponKind.BOW, WeaponKind.CROSSBOW);
        // 横扫之刃只在剑的横扫攻击里有意义，三件武器都用不上
        exclude("sweeping", WeaponKind.BOW, WeaponKind.CROSSBOW, WeaponKind.TRIDENT);
        // 击退（剑）只对近战有意义，远程击退请用"冲击"
        exclude("knockback", WeaponKind.BOW, WeaponKind.CROSSBOW);
    }

    private static void exclude(String path, WeaponKind... kinds) {
        EnumSet<WeaponKind> set = EnumSet.noneOf(WeaponKind.class);
        for (WeaponKind kind : kinds) {
            set.add(kind);
        }
        EXCLUSIONS.put(new ResourceLocation("minecraft", path), set);
    }

    // ------------------------------------------------------------------
    // 物品判定
    // ------------------------------------------------------------------

    /** 判断物品属于哪一类互通武器。 */
    public static WeaponKind kindOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? WeaponKind.OTHER : kindOf(stack.getItem());
    }

    /** 判断物品属于哪一类互通武器。 */
    public static WeaponKind kindOf(Item item) {
        if (item instanceof CrossbowItem) {
            return WeaponKind.CROSSBOW;
        }
        if (item instanceof BowItem) {
            return WeaponKind.BOW;
        }
        if (item instanceof TridentItem) {
            return WeaponKind.TRIDENT;
        }
        return WeaponKind.OTHER;
    }

    /** 是否属于弓 / 弩 / 三叉戟。 */
    public static boolean isInteropWeapon(ItemStack stack) {
        return kindOf(stack) != WeaponKind.OTHER;
    }

    /**
     * 从玩家身上找出真正开火的那把武器。
     * <p>
     * 顺序沿用本模组既有约定：正在使用的物品 → 主手 → 副手。射箭瞬间
     * {@code getUseItem()} 可能已被清空，因此必须保留主手兜底。
     */
    public static ItemStack findFiringWeapon(Player player) {
        if (player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack using = player.getUseItem();
        if (isInteropWeapon(using)) {
            return using;
        }
        ItemStack mainHand = player.getMainHandItem();
        if (isInteropWeapon(mainHand)) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        if (isInteropWeapon(offHand)) {
            return offHand;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------
    // 规则判定
    // ------------------------------------------------------------------

    /** 该附魔是否属于可以互通的武器类附魔。 */
    public static boolean isInteropEnchantment(Enchantment enchantment) {
        return enchantment != null && INTEROP_CATEGORIES.contains(enchantment.category);
    }

    /**
     * 是否应该放行"把该附魔附到该物品上"。
     * <p>
     * 只会把原版判定从 false 放宽到 true，绝不会否决原版本来就允许的组合，
     * 因此不会影响弓上附力量、弩上附穿透这类原生行为。
     */
    public static boolean canApplyTo(Enchantment enchantment, ItemStack stack) {
        if (enchantment == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (!ModConfig.interopEnabled()) {
            return false;
        }
        WeaponKind kind = kindOf(stack);
        if (kind == WeaponKind.OTHER) {
            return false;
        }
        if (!isInteropEnchantment(enchantment)) {
            return false;
        }
        if (isExcluded(enchantment, kind)) {
            return false;
        }
        return !ModConfig.isInteropDisabled(idOf(enchantment));
    }

    private static boolean isExcluded(Enchantment enchantment, WeaponKind kind) {
        ResourceLocation id = idOf(enchantment);
        if (id == null) {
            return false;
        }
        Set<WeaponKind> excluded = EXCLUSIONS.get(id);
        return excluded != null && excluded.contains(kind);
    }

    /**
     * 这一项在原版是不是已经由武器自身结算过了。
     * <p>
     * 效果层只补 {@code false} 的组合：原版已经算过的再从事件里加一遍就是双重加成。
     */
    public static boolean isNative(Enchantment enchantment, WeaponKind kind) {
        if (enchantment == null || kind == WeaponKind.OTHER) {
            return false;
        }
        // 弓自身处理：力量、冲击、火矢写进弹体，无限在消耗弹药时判断
        if (enchantment == Enchantments.POWER_ARROWS
                || enchantment == Enchantments.PUNCH_ARROWS
                || enchantment == Enchantments.FLAMING_ARROWS
                || enchantment == Enchantments.INFINITY_ARROWS) {
            return kind == WeaponKind.BOW;
        }
        // 弩自身处理：穿透在发射时写入，多重射击与快速装填在装填流程里
        if (enchantment == Enchantments.PIERCING
                || enchantment == Enchantments.MULTISHOT
                || enchantment == Enchantments.QUICK_CHARGE) {
            return kind == WeaponKind.CROSSBOW;
        }
        // 三叉戟自身处理：投掷伤害会叠加 getDamageBonus（穿刺/锋利/亡灵杀手/节肢杀手），
        // 引雷在原版命中逻辑里，忠诚与激流在 TridentItem 中，近战三件武器都读主手 NBT。
        // 注意这里不含火焰附加：近战确实是原版生效，但投掷路径原版完全不读，需要本模组补，
        // 所以它必须被判定为"非原生"，否则弹体快照不会记录武器、也就读不到等级。
        if (enchantment == Enchantments.IMPALING
                || enchantment == Enchantments.SHARPNESS
                || enchantment == Enchantments.SMITE
                || enchantment == Enchantments.BANE_OF_ARTHROPODS
                || enchantment == Enchantments.CHANNELING
                || enchantment == Enchantments.LOYALTY
                || enchantment == Enchantments.RIPTIDE
                || enchantment == Enchantments.MOB_LOOTING) {
            return kind == WeaponKind.TRIDENT;
        }
        // 耐久、经验修补、消失诅咒对三件武器原版都已生效
        if (enchantment == Enchantments.UNBREAKING
                || enchantment == Enchantments.MENDING
                || enchantment == Enchantments.VANISHING_CURSE) {
            return true;
        }
        return false;
    }

    /**
     * 互通范围内是否允许这两个附魔共存（放开原版互斥）。
     * <p>
     * 由 {@link com.yiva.homingarrows.mixin.EnchantmentMixin} 注入 {@code Enchantment#isCompatibleWith}
     * 调用：该方法是 final 的、且是所有"能不能一起附"判定的唯一出口（铁砧、{@code /enchant}、
     * {@code EnchantmentHelper.isEnchantmentCompatible} 都走它），因此在源头放行即可，不必逐个
     * 去改 {@code checkCompatibility}（那里每对互斥都写在不同的类里，容易漏）。
     * <p>
     * 放行两类：
     * <ul>
     *   <li><b>武器类附魔之间</b>：双方都属于 WEAPON / BOW / CROSSBOW / TRIDENT。这样
     *       穿透↔多重射击、锋利↔亡灵杀手↔节肢杀手、激流↔忠诚↔引雷等原版互斥全部放开，
     *       而护甲/工具那边的互斥（保护系、冰霜行者↔深海探索者、精准采集↔时运）完全不受影响。</li>
     *   <li><b>无限 ↔ 经验修补</b>：经验修补属于 BREAKABLE 类，不在上面那组里，单独列出来
     *       （原版由 {@code ArrowInfiniteEnchantment} 单边排除，是玩家最常抱怨的一对）。</li>
     * </ul>
     * 关闭互通开关后完全恢复原版互斥。
     */
    public static boolean areCompatible(Enchantment first, Enchantment second) {
        if (first == null || second == null || first == second) {
            // 同一个附魔：原版本就不允许重复，交回原版
            return false;
        }
        if (!ModConfig.interopEnabled()) {
            return false;
        }
        if (isInfinityMendingPair(first, second)) {
            return true;
        }
        return isInteropEnchantment(first) && isInteropEnchantment(second);
    }

    /** 无限 ↔ 经验修补（顺序无关）。 */
    private static boolean isInfinityMendingPair(Enchantment first, Enchantment second) {
        return (first == Enchantments.INFINITY_ARROWS && second == Enchantments.MENDING)
                || (first == Enchantments.MENDING && second == Enchantments.INFINITY_ARROWS);
    }

    /**
     * 该物品上是否存在"需要本模组补实现"的附魔；不存在就不必保存武器快照。
     */
    public static boolean hasInteropRelevantEnchantment(ItemStack stack) {
        WeaponKind kind = kindOf(stack);
        if (kind == WeaponKind.OTHER) {
            return false;
        }
        for (Enchantment enchantment : stack.getAllEnchantments().keySet()) {
            if (!isInteropEnchantment(enchantment) || isNative(enchantment, kind)) {
                continue;
            }
            // 这几项在拉弓/发射/飞行阶段就处理完了，命中时不需要读武器，因此不必写快照
            if (enchantment == Enchantments.QUICK_CHARGE
                    || enchantment == Enchantments.MULTISHOT
                    || enchantment == Enchantments.PIERCING) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 弩是否因互通而获得了"无限"效果。
     * <p>
     * 原版 {@code CrossbowItem} 完全不读无限附魔，所以这一项完全由本模组实现
     * （见 {@link com.yiva.homingarrows.mixin.CrossbowItemMixin}），这里统一做开关判断。
     */
    public static boolean hasInfinityForCrossbow(ItemStack crossbow) {
        return ModConfig.interopEnabled()
                && level(crossbow, Enchantments.INFINITY_ARROWS) > 0;
    }

    /** 读取物品上某个附魔的等级，空物品返回 0。 */
    public static int level(ItemStack stack, Enchantment enchantment) {
        return stack == null || stack.isEmpty() || enchantment == null
                ? 0
                : stack.getEnchantmentLevel(enchantment);
    }

    private static ResourceLocation idOf(Enchantment enchantment) {
        return enchantment == null ? null : ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
    }

    /**
     * 启动自检：用真实附魔实例跑一遍规则，输出一行可读结果。
     * <p>
     * 若 {@link com.yiva.homingarrows.mixin.EnchantmentMixin} 没有生效，"力量→弩"会显示 false，
     * 日志里一眼就能看出来，不必进游戏试。这里刻意用默认配置（不读玩家配置），保证它反映的是
     * "互通机制本身是否工作"。
     */
    public static String selfCheck() {
        ItemStack bow = new ItemStack(Items.BOW);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        ItemStack trident = new ItemStack(Items.TRIDENT);
        return String.format(
                "力量→弩 %s，力量→三叉戟 %s，穿刺→弓 %s，穿刺→三叉戟（附魔台路径）%s；"
                        + "排除项 忠诚→弓 %s（应 false），无限→三叉戟 %s（应 false）；"
                        + "互斥放开 穿透+多重射击 %s，无限+经验修补 %s，激流+忠诚 %s，锋利+亡灵杀手 %s（均应 true）；"
                        + "护甲互斥保持 保护+防火 %s（应 false）",
                test(Enchantments.POWER_ARROWS, crossbow),
                test(Enchantments.POWER_ARROWS, trident),
                test(Enchantments.PIERCING, bow),
                Enchantments.PIERCING.canApplyAtEnchantingTable(trident),
                test(Enchantments.LOYALTY, bow),
                test(Enchantments.INFINITY_ARROWS, trident),
                Enchantments.PIERCING.isCompatibleWith(Enchantments.MULTISHOT),
                Enchantments.INFINITY_ARROWS.isCompatibleWith(Enchantments.MENDING),
                Enchantments.RIPTIDE.isCompatibleWith(Enchantments.LOYALTY),
                Enchantments.SHARPNESS.isCompatibleWith(Enchantments.SMITE),
                Enchantments.ALL_DAMAGE_PROTECTION.isCompatibleWith(Enchantments.FIRE_PROTECTION));
    }

    private static boolean test(Enchantment enchantment, ItemStack stack) {
        return enchantment.canEnchant(stack);
    }
}
