package com.yiva.homingarrows.event;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import com.yiva.homingarrows.init.ModEnchantments;
import com.yiva.homingarrows.whitelist.HomingWhitelist;
import com.yiva.homingarrows.whitelist.HomingWhitelistCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 追踪箭的事件入口。
 * <p>
 * 注意：本类通过 {@link Mod.EventBusSubscriber} 由 Forge 自动注册，
 * <b>不要再在模组主类里手动注册</b>，否则事件会被重复派发。
 */
@Mod.EventBusSubscriber(modid = HomingArrowsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModEvents {

    private ModEvents() {
    }

    // ------------------------------------------------------------------
    // 发射检测
    // ------------------------------------------------------------------

    /**
     * 箭矢进入世界时判断是否应该成为追踪箭。
     * <p>
     * 这里不再依赖 {@code player.getUseItem()}：该值在射箭瞬间可能已经被清空或切手，
     * 导致追踪静默失效。改为直接检查玩家双手持有武器的附魔等级。
     */
    @SubscribeEvent
    public static void onArrowFired(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        Entity owner = arrow.getOwner();
        if (!(owner instanceof Player player)) {
            return;
        }

        int lockOnLevel = getLockOnLevel(player);
        if (lockOnLevel <= 0) {
            return;
        }

        // 标记为追踪箭，并设置为单人锁定模式
        arrow.getPersistentData().putBoolean(HomingArrowManager.TAG_LOCK_ON_ARROW, true);
        HomingArrowManager.addArrow(arrow);

        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[锁头箭] 玩家 {} 射出了追踪箭（附魔等级 {}，搜索半径 {} 格，锥角 {} 度）",
                    player.getName().getString(),
                    lockOnLevel,
                    String.format("%.1f", ModConfig.maxTrackingRange()),
                    String.format("%.1f", Math.toDegrees(Math.acos(ModConfig.coneCosine()))));
        }
    }

    /**
     * 读取玩家身上的锁头附魔等级，兼容弓与弩。
     * <p>
     * 优先看正在使用的物品（拉弓/弩上弦时最准确），随后回退到主手、副手。
     */
    private static int getLockOnLevel(Player player) {
        int level = getLevelFrom(player.getUseItem());
        if (level > 0) {
            return level;
        }
        level = getLevelFrom(player.getMainHandItem());
        if (level > 0) {
            return level;
        }
        return getLevelFrom(player.getOffhandItem());
    }

    private static int getLevelFrom(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        // 使用 ItemStack 上的实例方法，EnchantmentHelper.getItemEnchantmentLevel 在 1.20.1 已废弃
        return stack.getEnchantmentLevel(ModEnchantments.LOCK_ON.get());
    }

    /**
     * 读取附魔书上的锁头附魔等级。
     * <p>
     * 附魔书<strong>必须</strong>把附魔存在 {@code StoredEnchantments} 里；
     * 写成 {@code Enchantments} 的"附魔书"原版是读不到的。但整合包里手改 NBT、
     * 或由其它模组生成的物品有可能用错字段，导致玩家明明"看到"书上有附魔却判定失败。
     * 因此这里直接读 NBT，并同时接受两种字段，避免这类莫名其妙的失败。
     */
    private static int getLockOnLevelFromBook(ItemStack book) {
        ResourceLocation lockOnId = ForgeRegistries.ENCHANTMENTS.getKey(ModEnchantments.LOCK_ON.get());
        if (lockOnId == null) {
            return 0;
        }
        return Math.max(readEnchantLevel(book, "StoredEnchantments", lockOnId),
                readEnchantLevel(book, "Enchantments", lockOnId));
    }

    /** 从指定 NBT 列表里读出某个附魔的等级，不存在时返回 0。 */
    private static int readEnchantLevel(ItemStack stack, String tagName, ResourceLocation enchantmentId) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(tagName, Tag.TAG_LIST)) {
            return 0;
        }
        ListTag list = tag.getList(tagName, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("id"));
            if (enchantmentId.equals(id)) {
                int level = entry.getInt("lvl");
                return level > 0 ? level : 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 命中检测
    // ------------------------------------------------------------------

    /** 追踪箭命中实体时记录命中，供穿透附魔继续追踪下一个目标。 */
    @SubscribeEvent
    public static void onEntityDamaged(LivingDamageEvent event) {
        if (event.getSource().getDirectEntity() instanceof AbstractArrow arrow) {
            if (arrow.getPersistentData().getBoolean(HomingArrowManager.TAG_LOCK_ON_ARROW)) {
                HomingArrowManager.onArrowHitEntity(arrow, event.getEntity());
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期与清理
    // ------------------------------------------------------------------

    /** 箭矢离开世界（被拾取、区块卸载、跨维度、命中消失）时清理追踪数据。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow) {
            HomingArrowManager.removeArrow(arrow);
        }
    }

    /** 服务器启动完成后载入白名单；停止时释放静态引用。 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        HomingWhitelist.load(event.getServer());
    }

    /** 服务器停止时清空全部追踪数据并释放白名单，避免静态表残留。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HomingArrowManager.clearAll();
        HomingWhitelist.unload();
    }

    // ------------------------------------------------------------------
    // 白名单：锁头附魔书交互
    // ------------------------------------------------------------------

    /**
     * 手持带锁头附魔的附魔书 + Shift 右键生物 → 切换该个体的白名单状态。
     * <p>
     * 之所以用附魔书而不是命令选择器：玩家无法知道、也无法手敲目标 UUID，
     * 而右键交互天然精确指向"眼前这一只"。
     */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player == null || !player.isShiftKeyDown()) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }

        ItemStack book = event.getItemStack();
        if (!(book.getItem() instanceof EnchantedBookItem)) {
            return;
        }
        int lockOnLevel = getLockOnLevelFromBook(book);
        if (lockOnLevel <= 0) {
            // 手上这本附魔书没有锁头附魔就安静放行：Shift 右键生物本来就是原版行为，
            // 不该因为玩家手里恰好拿着一本别的附魔书就弹提示打扰他。
            return;
        }

        // 走到这里说明条件全部满足，记一条日志便于排查「右键无反应」类问题
        HomingArrowsMod.LOGGER.info("锁头附魔书交互：玩家 {} → {}（{}）",
                player.getName().getString(),
                target.getName().getString(),
                EntityType.getKey(target.getType()));

        // 客户端与服务端都要撤销这次交互，避免两边状态不一致
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        // 白名单只在服务端存在，实际读写与服务端反馈都放在这一侧
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (target instanceof Player) {
            player.displayClientMessage(
                    Component.literal("无法将玩家加入锁头白名单").withStyle(ChatFormatting.RED), true);
            return;
        }

        boolean nowWhitelisted;
        // 附魔书只切换「个体」条目，不碰「种族」条目。
        // 若该生物是被种族条目保护的，需要明确告知，否则玩家会看到
        // 「移出白名单」却依然免疫的矛盾现象。
        if (HomingWhitelist.isTypeWhitelisted(target.getType())) {
            boolean hadIndividual = HomingWhitelist.removeIndividual(target);
            player.displayClientMessage(
                    Component.literal((hadIndividual ? "已移除 " + target.getName().getString() + " 的个体条目；" : "")
                            + "但 " + target.getType().getDescription().getString()
                            + " 仍受种族白名单保护，请用 /homingwhitelist remove type 解除")
                            .withStyle(ChatFormatting.YELLOW), true);
            player.swing(event.getHand(), true);
            return;
        }

        if (HomingWhitelist.isWhitelisted(target)) {
            nowWhitelisted = !HomingWhitelist.removeIndividual(target);
        } else {
            nowWhitelisted = HomingWhitelist.addIndividual(target);
        }

        String name = target.getName().getString();
        player.displayClientMessage(
                Component.literal(nowWhitelisted
                                ? "已将 " + name + " 加入锁头白名单"
                                : "已将 " + name + " 移出锁头白名单")
                        .withStyle(nowWhitelisted ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        player.swing(event.getHand(), true);

        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(nowWhitelisted ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SMOKE,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    8, 0.3D, 0.3D, 0.3D, 0.02D);
            serverLevel.playSound(null, target.blockPosition(),
                    nowWhitelisted ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.FIRE_EXTINGUISH,
                    SoundSource.PLAYERS, 0.7F, nowWhitelisted ? 1.4F : 0.9F);
        }
    }

    // ------------------------------------------------------------------
    // 白名单：管理命令
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        HomingArrowsMod.LOGGER.info("收到 RegisterCommandsEvent，开始注册锁头白名单命令");
        HomingWhitelistCommand.register(event.getDispatcher(), event.getBuildContext());
    }

    // ------------------------------------------------------------------
    // 每刻驱动
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 只在 END 相位推进一次，避免每刻重复制导
        if (event.phase == TickEvent.Phase.END) {
            HomingArrowManager.updateAllArrows();
        }
    }
}
