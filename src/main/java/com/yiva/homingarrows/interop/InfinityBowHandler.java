package com.yiva.homingarrows.interop;

import com.yiva.homingarrows.HomingArrowsMod;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.player.ArrowNockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 弓的无限附魔：背包没有箭时也能开始拉弓。 */
@Mod.EventBusSubscriber(modid = HomingArrowsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InfinityBowHandler {

    private InfinityBowHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onArrowNock(ArrowNockEvent event) {
        ItemStack bow = event.getBow();
        if (event.getAction() != null || event.hasAmmo()
                || !(bow.getItem() instanceof BowItem)
                || EnchantmentInterop.level(bow, Enchantments.INFINITY_ARROWS) <= 0) {
            return;
        }

        // 客户端和服务端都开始使用；放箭、耐久和不可拾取规则继续由 BowItem 处理。
        Player player = event.getEntity();
        player.startUsingItem(event.getHand());
        event.setAction(InteractionResultHolder.consume(bow));
    }
}
