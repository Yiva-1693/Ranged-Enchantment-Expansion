package com.yiva.homingarrows.init;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.enchantment.LockOnEnchantment;
import com.yiva.homingarrows.enchantment.WeightlessEnchantment;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, HomingArrowsMod.MOD_ID);

    public static final RegistryObject<Enchantment> LOCK_ON =
            ENCHANTMENTS.register("tracking", LockOnEnchantment::new);

    /** 失重：箭矢/三叉戟走直线。 */
    public static final RegistryObject<Enchantment> WEIGHTLESS =
            ENCHANTMENTS.register("weightless", WeightlessEnchantment::new);

    public static void register(IEventBus eventBus) {
        ENCHANTMENTS.register(eventBus);
        HomingArrowsMod.LOGGER.info("注册追踪、失重附魔...");
    }
}
