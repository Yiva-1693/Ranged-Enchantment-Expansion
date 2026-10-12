package com.yiva.homingarrows.whitelist;

import com.yiva.homingarrows.HomingArrowsMod;
import com.yiva.homingarrows.config.ModConfig;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 全局追踪白名单：名单内的生物不会被任何玩家的追踪箭锁定。
 * <p>
 * 支持两种粒度，二者语义不同：
 * <ul>
 *   <li><b>个体</b>（按 UUID）：只豁免那一只，同类型新生成的个体照常被锁定。条目永久保留，
 *       即使该生物死亡或所在区块被卸载也不会失效。</li>
 *   <li><b>种族</b>（按 {@link EntityType}）：豁免该类型的所有个体，<b>包括之后新刷出来的</b>。</li>
 * </ul>
 * 数据通过 {@link net.minecraft.world.level.saveddata.SavedData} 挂载在主世界，落在
 * {@code world/data/ranged_enchant_expansion_whitelist.dat}，因此是跨维度、跨重启的全局生效。
 * <p>
 * <b>玩家永远无法进入白名单</b>：{@link #addIndividual} 与 {@link #addType} 都会拒绝。
 */
public final class HomingWhitelist {

    /** 落盘时的数据名，对应 world/data/&lt;name&gt;.dat。 */
    private static final String DATA_NAME = "ranged_enchant_expansion_whitelist";

    private static final String KEY_INDIVIDUALS = "Individuals";
    private static final String KEY_TYPES = "Types";
    private static final String KEY_UUID = "UUID";
    private static final String KEY_TYPE = "Type";
    private static final String KEY_NAME = "Name";

    /** 当前服务器的白名单实例；仅在服务端生命周期内非 null。 */
    private static SavedData instance;

    private HomingWhitelist() {
    }

    // ------------------------------------------------------------------
    // 载入 / 卸载
    // ------------------------------------------------------------------

    /** 服务器启动完成时从主世界载入，之后所有查询都走这个实例。 */
    public static void load(MinecraftServer server) {
        instance = server.overworld().getDataStorage().computeIfAbsent(
                HomingWhitelist.SavedData::load,
                HomingWhitelist.SavedData::new,
                DATA_NAME);
        // 这条日志兼作「白名单系统已就绪」的确认，排查问题时很有用
        HomingArrowsMod.LOGGER.info("追踪白名单已载入：个体 {} 条，种族 {} 条",
                instance.individualCount(), instance.types().size());
    }

    /** 服务器停止时释放，避免静态引用跨实例残留。 */
    public static void unload() {
        instance = null;
    }

    private static SavedData data() {
        return instance;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 该生物是否受白名单保护。UUID 与类型各查一次，均为 O(1)。
     */
    public static boolean isWhitelisted(Entity entity) {
        if (entity == null) {
            return false;
        }
        SavedData data = data();
        if (data == null) {
            return false;
        }
        if (data.individuals().containsKey(entity.getUUID())) {
            return true;
        }
        ResourceLocation typeId = EntityType.getKey(entity.getType());
        return typeId != null && data.types().contains(typeId);
    }

    /** 该实体类型是否整体被豁免。 */
    public static boolean isTypeWhitelisted(EntityType<?> type) {
        SavedData data = data();
        if (data == null || type == null) {
            return false;
        }
        ResourceLocation typeId = EntityType.getKey(type);
        return typeId != null && data.types().contains(typeId);
    }

    /** 个体条目数。 */
    public static int individualCount() {
        SavedData data = data();
        return data == null ? 0 : data.individualCount();
    }

    /** 种族条目数。 */
    public static int typeCount() {
        SavedData data = data();
        return data == null ? 0 : data.types().size();
    }

    /** 个体条目快照，供 list 展示。 */
    public static List<Entry> individualEntries() {
        SavedData data = data();
        return data == null ? List.of() : new ArrayList<>(data.individuals().values());
    }

    /** 种族条目快照，供 list 展示。 */
    public static List<ResourceLocation> typeEntries() {
        SavedData data = data();
        return data == null ? List.of() : new ArrayList<>(data.types());
    }

    // ------------------------------------------------------------------
    // 修改
    // ------------------------------------------------------------------

    /**
     * 把单个生物加入白名单。
     *
     * @return true 表示新增成功；false 表示目标非法（玩家 / 非生物）或已在名单中
     */
    public static boolean addIndividual(LivingEntity target) {
        SavedData data = data();
        if (data == null || !isAllowedTarget(target)) {
            return false;
        }
        UUID id = target.getUUID();
        if (data.individuals().containsKey(id)) {
            return false;
        }
        ResourceLocation typeId = EntityType.getKey(target.getType());
        data.individuals().put(id, new Entry(id, typeId, target.getName().getString()));
        data.setDirty();
        debug("个体加入白名单：{} ({})", target.getName().getString(), id);
        return true;
    }

    /**
     * 把单个生物移出白名单（仅针对个体条目，不影响其种族条目）。
     *
     * @return true 表示确实移除了条目
     */
    public static boolean removeIndividual(LivingEntity target) {
        SavedData data = data();
        if (data == null || target == null) {
            return false;
        }
        if (data.individuals().remove(target.getUUID()) == null) {
            return false;
        }
        data.setDirty();
        debug("个体移出白名单：{}", target.getName().getString());
        return true;
    }

    /**
     * 把整个种族加入白名单，包含以后新生成的个体。
     *
     * @return true 表示新增成功；false 表示类型非法（玩家 / 非生物）或已在名单中
     */
    public static boolean addType(EntityType<?> type) {
        SavedData data = data();
        if (data == null || !isAllowedType(type)) {
            return false;
        }
        ResourceLocation typeId = EntityType.getKey(type);
        if (typeId == null || !data.types().add(typeId)) {
            return false;
        }
        data.setDirty();
        debug("种族加入白名单：{}", typeId);
        return true;
    }

    /**
     * 把整个种族移出白名单。
     *
     * @return true 表示确实移除了条目
     */
    public static boolean removeType(EntityType<?> type) {
        SavedData data = data();
        if (data == null || type == null) {
            return false;
        }
        ResourceLocation typeId = EntityType.getKey(type);
        if (typeId == null || !data.types().remove(typeId)) {
            return false;
        }
        data.setDirty();
        debug("种族移出白名单：{}", typeId);
        return true;
    }

    /** 清空全部白名单条目。 */
    public static void clearAll() {
        SavedData data = data();
        if (data == null) {
            return;
        }
        data.individuals().clear();
        data.types().clear();
        data.setDirty();
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 玩家不可加入；只接受生物（追踪目标本来就是 LivingEntity）。 */
    public static boolean isAllowedTarget(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof Player);
    }

    /**
     * 玩家类型不可加入。
     * <p>
     * 非生物类型（掉落物、船等）即使被写入也不会产生任何效果——追踪目标只可能是
     * {@link LivingEntity}，所以这里不做额外过滤，由命令层负责给出提示。
     */
    public static boolean isAllowedType(EntityType<?> type) {
        return type != null && type != EntityType.PLAYER;
    }

    private static void debug(String message, Object... args) {
        if (ModConfig.debugMode()) {
            HomingArrowsMod.LOGGER.info("[追踪白名单] " + message, args);
        }
    }

    // ------------------------------------------------------------------
    // 条目
    // ------------------------------------------------------------------

    /** 单个被豁免个体。 */
    public record Entry(UUID id, ResourceLocation typeId, String name) {
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /**
     * 实际落盘的数据。挂载在主世界的 {@code DimensionDataStorage} 上，
     * 因此对全部维度生效，并随存档一起保存。
     */
    private static final class SavedData extends net.minecraft.world.level.saveddata.SavedData {

        private final Map<UUID, Entry> individuals = new HashMap<>();
        private final Set<ResourceLocation> types = new HashSet<>();

        private Map<UUID, Entry> individuals() {
            return individuals;
        }

        private Set<ResourceLocation> types() {
            return types;
        }

        private int individualCount() {
            return individuals.size();
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag entries = new CompoundTag();
            for (Entry entry : individuals.values()) {
                CompoundTag one = new CompoundTag();
                one.put(KEY_UUID, NbtUtils.createUUID(entry.id()));
                if (entry.typeId() != null) {
                    one.putString(KEY_TYPE, entry.typeId().toString());
                }
                if (entry.name() != null) {
                    one.putString(KEY_NAME, entry.name());
                }
                entries.put(entry.id().toString(), one);
            }
            tag.put(KEY_INDIVIDUALS, entries);

            ListTag typeList = new ListTag();
            for (ResourceLocation typeId : types) {
                typeList.add(net.minecraft.nbt.StringTag.valueOf(typeId.toString()));
            }
            tag.put(KEY_TYPES, typeList);
            return tag;
        }

        private static SavedData load(CompoundTag tag) {
            SavedData data = new SavedData();

            CompoundTag entries = tag.getCompound(KEY_INDIVIDUALS);
            for (String key : entries.getAllKeys()) {
                CompoundTag one = entries.getCompound(key);
                UUID id = NbtUtils.loadUUID(one.get(KEY_UUID));
                if (id == null) {
                    continue;
                }
                ResourceLocation typeId = one.contains(KEY_TYPE)
                        ? ResourceLocation.tryParse(one.getString(KEY_TYPE))
                        : null;
                String name = one.contains(KEY_NAME) ? one.getString(KEY_NAME) : null;
                data.individuals.put(id, new Entry(id, typeId, name));
            }

            ListTag typeList = tag.getList(KEY_TYPES, Tag.TAG_STRING);
            for (int i = 0; i < typeList.size(); i++) {
                ResourceLocation typeId = ResourceLocation.tryParse(typeList.getString(i));
                if (typeId != null) {
                    data.types.add(typeId);
                }
            }
            return data;
        }
    }
}
