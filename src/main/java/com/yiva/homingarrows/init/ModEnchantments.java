package com.yiva.homingarrows.init;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.enchantment.LockOnEnchantment;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, HomingArrowsMod.MOD_ID);

    public static final RegistryObject<Enchantment> LOCK_ON =
            ENCHANTMENTS.register("lock_on", LockOnEnchantment::new);

    public static void register(IEventBus eventBus) {
        ENCHANTMENTS.register(eventBus);
        HomingArrowsMod.LOGGER.info("注册锁头附魔...");
    }
}