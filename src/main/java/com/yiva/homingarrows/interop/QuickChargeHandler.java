package com.yiva.homingarrows.interop;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 弓的快速装填互通：真正加快"拉满弓"的速度。
 * <p>
 * 原版快速装填只作用于弩（{@code CrossbowItem.getChargeDuration}），弓完全不读。弓的拉满进度、第一人称
 * 拉弓动画以及出射威力都取决于同一个字段 {@code useItemRemaining}（原版每刻减 1，威力为
 * {@code (持续时间 - useItemRemaining) / 20}），所以只要让这个字段推进得更快，三者会一起变快。
 * <p>
 * <b>本实现完全无状态</b>：每刻在原有 -1 之外再减 {@code 等级} 刻，即每刻推进 {@code 1 + 等级} 刻。
 * 这一点很重要——早先的版本为每个玩家维护了一个"拉弓进度累加器"，靠 {@code isUsingItem()} 的真假来清零，
 * 结果出现过一个很难查的问题：客户端在放箭的同一刻又按下右键（或客户端的使用状态比服务端多留一 tick）时，
 * 客户端的累加器没有被清零，画面瞬间就是满弓，玩家立刻松手，而服务端那边的已蓄力刻数还很小，
 * 射出来的箭就成了最慢的一档，配上失重附魔就变成"特别慢的飘"。
 * <p>
 * 现在进度只存在于原版的 {@code useItemRemaining} 里：新一次拉弓由原版自己把它重置为满值，
 * 我们每刻只做一次固定推进，客户端与服务端各自从同一个基准出发，因此
 * <b>"画面看起来拉满"与"服务端算出来的威力"不会再脱节</b>。
 * <table>
 *   <tr><th>等级</th><th>每刻推进</th><th>拉满所需</th></tr>
 *   <tr><td>I</td><td>2 刻</td><td>10 刻（0.5 秒）</td></tr>
 *   <tr><td>II</td><td>3 刻</td><td>约 7 刻（0.33 秒）</td></tr>
 *   <tr><td>III</td><td>4 刻</td><td>5 刻（0.25 秒）</td></tr>
 * </table>
 * 原版为 20 刻（1 秒）。若要调数值，改 {@link #QUICK_CHARGE_TICKS_PER_LEVEL} 的用法即可
 * （当前是"每级再多推进 1 刻"）。
 */
@Mod.EventBusSubscriber(modid = HomingArrowsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuickChargeHandler {

    private QuickChargeHandler() {
    }

    /** 每级额外推进的刻数：等级 L 时每刻推进 1 + L 刻。 */
    private static final int QUICK_CHARGE_EXTRA_TICKS_PER_LEVEL = 1;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 放在 END 相位：原版这一帧已经减过 1，这里再补上等级对应的额外推进
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (!player.isUsingItem()) {
            return;
        }
        ItemStack using = player.getUseItem();
        // 弩的快速装填是原版行为，只有弓需要补
        if (EnchantmentInterop.kindOf(using) != EnchantmentInterop.WeaponKind.BOW) {
            return;
        }
        int quickCharge = EnchantmentInterop.level(using, Enchantments.QUICK_CHARGE);
        if (quickCharge <= 0 || !ModConfig.interopEnabled()) {
            return;
        }
        // useItemRemaining 在玩家（LivingEntity）身上，不在物品上
        if (!(player instanceof LivingEntityAccess access)) {
            return;
        }

        int duration = using.getUseDuration();
        int accelerated = player.getUseItemRemainingTicks()
                - QUICK_CHARGE_EXTRA_TICKS_PER_LEVEL * quickCharge;
        // 只做"推进"，绝不让剩余刻数超过满值或掉到负数（掉到 0 会由原版正常结束使用）
        access.homingarrows$setUseItemRemaining(Math.max(0, Math.min(accelerated, duration)));

        if (ModConfig.debugMode() && player.tickCount % 20 == 0) {
            HomingArrowsMod.LOGGER.info("[附魔互通] 弓的快速装填 {} 级：剩余 {} 刻（每刻推进 {} 刻）",
                    quickCharge, accelerated, 1 + QUICK_CHARGE_EXTRA_TICKS_PER_LEVEL * quickCharge);
        }
    }
}
