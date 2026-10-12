package com.yiva.homingarrows;

import com.yiva.homingarrows.config.ModConfig;
import com.yiva.homingarrows.init.ModEnchantments;
import com.yiva.homingarrows.interop.EnchantmentInterop;
import com.yiva.homingarrows.interop.TridentAccess;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Ranged Enchantment Expansion（远程附魔扩展）—— 添加"追踪"与"失重"附魔，
 * 并让弓 / 弩 / 三叉戟之间的附魔互通。
 *
 * @author Yiva
 */
@Mod(HomingArrowsMod.MOD_ID)
public class HomingArrowsMod {

    public static final String MOD_ID = "ranged_enchant_expansion";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public HomingArrowsMod() {
        LOGGER.info("Ranged Enchantment Expansion by Yiva 正在加载...");

        // 附魔互通依赖 Mixin 放行附魔适用性；用接口是否被挂上作为运行时探针，
        // 万一 Mixin 没生效，日志里能立刻看出来（此时互通会退化成原版行为）。
        LOGGER.info("附魔互通 Mixin 状态：{}",
                TridentAccess.class.isAssignableFrom(ThrownTrident.class)
                        ? "已生效"
                        : "未生效（弓/弩/三叉戟附魔互通将退化）");
        LOGGER.info("附魔互通自检：{}", EnchantmentInterop.selfCheck());

        // 模组事件总线：注册延迟注册器（附魔等）
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEnchantments.register(modEventBus);

        // 通用配置
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC);

        // 注意：ModEvents 使用 @Mod.EventBusSubscriber 由 Forge 自动注册到 FORGE 总线，
        // 这里不能再手动 register，否则同一实例会被注册两次、事件被重复派发。
        // 若将来需要手动注册，请先移除该类上的 @Mod.EventBusSubscriber 注解。

        LOGGER.info("Ranged Enchantment Expansion 加载完成！");
    }
}
