package com.yiva.homingarrows.whitelist;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.yiva.homingarrows.HomingArrowsMod;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.UUID;

/**
 * 锁头白名单管理命令。
 * <p>
 * 个体条目除了可以用这里的 {@code remove} 移除，也可以直接用锁头附魔书
 * Shift 右键该生物来切换（见 {@code ModEvents#onEntityInteract}）。
 * 种族条目只能用 {@code addtype} / {@code remove type} 管理。
 */
public final class HomingWhitelistCommand {

    public static final String ARG_TARGET = "target";
    public static final String ARG_TYPE = "entity_type";

    private static final SimpleCommandExceptionType ERROR_NO_PLAYER =
            new SimpleCommandExceptionType(Component.literal("无法将玩家加入锁头白名单"));

    private static final DynamicCommandExceptionType ERROR_NOT_LIVING =
            new DynamicCommandExceptionType(type -> Component.literal("目标不是生物，无法加入锁头白名单：" + type));

    private HomingWhitelistCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        // 共用同一棵命令树，注册为两个名字：/homingwhitelist 与简写 /hw。
        // 每个名字独立 try/catch：任何一个注册失败都不应拖垮另一个，
        // 更不能静默失败——命令注册异常会让整批命令全部消失，玩家侧只表现为
        // 「输入指令毫无反应」，极难排查。
        for (String name : new String[]{"homingwhitelist", "hw"}) {
            try {
                dispatcher.register(buildRoot(name, context));
                HomingArrowsMod.LOGGER.info("已注册命令 /{}", name);
            } catch (Exception e) {
                HomingArrowsMod.LOGGER.error("注册命令 /{} 失败，该命令将不可用", name, e);
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name, CommandBuildContext context) {
        LiteralArgumentBuilder<CommandSourceStack> addType = Commands.literal("addtype")
                .then(Commands.argument(ARG_TYPE, typeArgument(context))
                        .executes(ctx -> addType(ctx, getTypeHolder(ctx))));

        LiteralArgumentBuilder<CommandSourceStack> remove = Commands.literal("remove")
                .then(Commands.argument(ARG_TARGET, EntityArgument.entity())
                        .executes(HomingWhitelistCommand::removeIndividual))
                .then(Commands.literal("type")
                        .then(Commands.argument(ARG_TYPE, typeArgument(context))
                                .executes(ctx -> removeType(ctx, getTypeHolder(ctx)))));

        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> showUsage(ctx, name))
                .then(addType)
                .then(remove)
                .then(Commands.literal("list").executes(HomingWhitelistCommand::list))
                .then(Commands.literal("clear").executes(HomingWhitelistCommand::clear));
    }

    /** 不带子命令时给出用法提示，避免玩家对着空响应发呆。 */
    private static int showUsage(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("===== 锁头白名单 =====").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("个体：手持带锁头附魔的附魔书 + Shift 右键生物（再次右键取消）")
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("/" + name + " addtype <实体类型>  ")
                .append(Component.literal("整个种族加入白名单").withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal("/" + name + " remove <目标>     ")
                .append(Component.literal("移除某个个体条目").withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal("/" + name + " remove type <实体类型>  ")
                .append(Component.literal("移除种族条目").withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal("/" + name + " list / clear  ")
                .append(Component.literal("查看 / 清空白名单").withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private static ArgumentType<?> typeArgument(CommandBuildContext context) {
        return ResourceArgument.resource(context, Registries.ENTITY_TYPE);
    }

    @SuppressWarnings("unchecked")
    private static EntityType<?> getTypeHolder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ResourceArgument.getResource(ctx, ARG_TYPE, Registries.ENTITY_TYPE).value();
    }

    // ------------------------------------------------------------------
    // addtype
    // ------------------------------------------------------------------

    private static int addType(CommandContext<CommandSourceStack> ctx, EntityType<?> type)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        requireNonPlayerType(type);

        if (HomingWhitelist.addType(type)) {
            source.sendSuccess(() -> Component.literal("已将 ")
                    .append(describe(type))
                    .append(" 整体加入锁头白名单（包括以后新生成的个体）"), true);
        } else {
            source.sendSuccess(() -> Component.literal("该类型已在锁头白名单中：")
                    .append(describe(type)), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // remove <目标>
    // ------------------------------------------------------------------

    private static int removeIndividual(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity target = EntityArgument.getEntity(ctx, ARG_TARGET);
        CommandSourceStack source = ctx.getSource();

        if (!(target instanceof LivingEntity living)) {
            source.sendFailure(Component.literal("目标不是生物，不在锁头白名单中"));
            return 0;
        }
        if (target instanceof Player) {
            source.sendFailure(Component.literal("玩家不在锁头白名单中"));
            return 0;
        }

        UUID id = target.getUUID();
        String name = target.getName().getString();
        if (HomingWhitelist.removeIndividual(living)) {
            source.sendSuccess(() -> Component.literal("已将 " + name + " 移出锁头白名单（个体条目 " + id + "）"), true);
        } else {
            // 可能只在其种族条目里，给出明确提示而不是含糊的“没找到”
            if (HomingWhitelist.isWhitelisted(target)) {
                source.sendSuccess(() -> Component.literal(name
                        + " 没有个体条目，但它所属的类型整体在白名单中，请用 /homingwhitelist remove type 移除"), false);
            } else {
                source.sendSuccess(() -> Component.literal(name + " 不在锁头白名单中"), false);
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // remove type <实体类型>
    // ------------------------------------------------------------------

    private static int removeType(CommandContext<CommandSourceStack> ctx, EntityType<?> type) {
        CommandSourceStack source = ctx.getSource();
        if (HomingWhitelist.removeType(type)) {
            source.sendSuccess(() -> Component.literal("已将 ")
                    .append(describe(type))
                    .append(" 移出锁头白名单（种族条目）"), true);
        } else {
            source.sendSuccess(() -> Component.literal("该类型不在锁头白名单中：")
                    .append(describe(type)), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    /**
     * 发送一行反馈到聊天栏。
     * <p>
     * <b>为什么必须是聊天栏而不是行动栏：</b>个体 UUID 需要支持「点击复制」，
     * 而点击/悬停事件只有聊天栏消息能承载，行动栏（overlay）无法点击。
     * <p>
     * <b>注意：</b>聊天反馈受世界规则 {@code sendCommandFeedback} 控制。
     * 该规则为 {@code false} 时，{@code CommandSourceStack#sendSuccess} 会直接丢弃消息
     * （原版所有命令的成功反馈同样会被丢弃），此时表现为「输入指令毫无反应」。
     * 需用 {@code /gamerule sendCommandFeedback true} 打开，这是原版机制，非模组问题。
     */
    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        List<HomingWhitelist.Entry> individuals = HomingWhitelist.individualEntries();
        List<ResourceLocation> types = HomingWhitelist.typeEntries();

        HomingArrowsMod.LOGGER.info("/homingwhitelist list 被执行：个体 {} 条，种族 {} 条",
                individuals.size(), types.size());

        send(source, "===== 锁头白名单：个体 " + individuals.size() + " 条 / 种族 " + types.size() + " 条 =====");

        if (individuals.isEmpty() && types.isEmpty()) {
            send(source, "白名单当前为空");
            send(source, "添加方式：/homingwhitelist addtype <实体类型> 添加整个种族");
            send(source, "或：手持带「锁头」附魔的附魔书，Shift 右键生物添加该个体");
            return 0;
        }

        for (HomingWhitelist.Entry entry : individuals) {
            Entity entity = findEntity(server, entry.id());
            String status = entity != null ? "已加载" : "未加载或已消失";
            String typeText = entry.typeId() == null ? "未知类型" : entry.typeId().toString();
            String name = entry.name() == null ? "(无名)" : entry.name();
            send(source, "[个体] " + name + "  " + typeText + "  " + status);

            // UUID 做成可点击复制：这是玩家「识别并区分不同目标」的唯一手段，
            // 所以这一行必须留在聊天栏，不能用行动栏替代。
            source.sendSuccess(() -> Component.literal("        " + entry.id())
                    .withStyle(style -> style
                            .withColor(ChatFormatting.DARK_GRAY)
                            .withClickEvent(new ClickEvent(
                                    ClickEvent.Action.COPY_TO_CLIPBOARD, entry.id().toString()))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("点击复制该个体 UUID，可用于核对或备份")))), false);
        }

        for (ResourceLocation typeId : types) {
            send(source, "[种族] " + typeId);
        }
        return individuals.size() + types.size();
    }

    // ------------------------------------------------------------------
    // clear
    // ------------------------------------------------------------------

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int total = HomingWhitelist.individualCount() + HomingWhitelist.typeCount();
        HomingWhitelist.clearAll();
        source.sendSuccess(() -> Component.literal("已清空锁头白名单，共移除 " + total + " 条条目"), true);
        return total;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static void requireNonPlayerType(EntityType<?> type) throws CommandSyntaxException {
        if (type == EntityType.PLAYER) {
            throw ERROR_NO_PLAYER.create();
        }
        if (!HomingWhitelist.isAllowedType(type)) {
            throw ERROR_NOT_LIVING.create(EntityType.getKey(type));
        }
    }

    /** 「僵尸 (minecraft:zombie)」这样的可读描述。 */
    private static MutableComponent describe(EntityType<?> type) {
        ResourceLocation id = EntityType.getKey(type);
        return Component.literal(type.getDescription().getString() + " (" + id + ")")
                .withStyle(ChatFormatting.AQUA);
    }

    /** 在所有已加载维度里按 UUID 找实体，仅用于 list 的状态展示。 */
    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }
}
