package com.yiva.homingarrows;

import com.yiva.homingarrows.config.ModConfig;
import com.yiva.homingarrows.init.ModEnchantments;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Homing Arrows —— 添加"锁头"附魔，使弓/弩射出的箭在飞行中自动追踪附近目标。
 *
 * @author Yiva
 */
@Mod(HomingArrowsMod.MOD_ID)
public class HomingArrowsMod {

    public static final String MOD_ID = "homingarrows";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public HomingArrowsMod() {
        LOGGER.info("Homing Arrows Mod by Yiva 正在加载...");

        // 模组事件总线：注册延迟注册器（附魔等）
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEnchantments.register(modEventBus);

        // 通用配置
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC);

        // 注意：ModEvents 使用 @Mod.EventBusSubscriber 由 Forge 自动注册到 FORGE 总线，
        // 这里不能再手动 register，否则同一实例会被注册两次、事件被重复派发。
        // 若将来需要手动注册，请先移除该类上的 @Mod.EventBusSubscriber 注解。

        LOGGER.info("Homing Arrows Mod 加载完成！");
    }
}
