package com.yiva.homingarrows.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组配置。
 * <p>
 * 所有追踪参数都由 {@link com.yiva.homingarrows.event.HomingArrowManager} 实时读取，
 * 也就是说玩家修改配置文件后无需修改代码即可调整追踪行为。
 * <p>
 * 由于配置在服务端加载完成前可能尚未可用，这里提供了一组带默认值的静态访问器，
 * 保证在极端情况下也不会抛出异常。
 */
public final class ModConfig {

    private ModConfig() {
    }

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // ---------------- 追踪参数 ----------------
    /** 开始追踪前的延迟（游戏刻）。 */
    public static final ForgeConfigSpec.IntValue INITIAL_DELAY;
    /** 最大追踪时间（秒）。 */
    public static final ForgeConfigSpec.IntValue MAX_TRACKING_TIME;
    /** 允许追踪自己的延迟（秒）。保留配置项以兼容旧配置文件，当前实现永不锁定自己。 */
    public static final ForgeConfigSpec.IntValue SELF_TRACKING_DELAY;
    /** 最小追踪速度（低于此速度停止追踪，格/刻）。 */
    public static final ForgeConfigSpec.DoubleValue MIN_TRACKING_SPEED;
    /** 追踪锥形角度（度），以箭矢飞行方向为中心。 */
    public static final ForgeConfigSpec.DoubleValue CONE_ANGLE;
    /** 最大追踪范围（格，球形搜索半径）。 */
    public static final ForgeConfigSpec.DoubleValue MAX_TRACKING_RANGE;
    /** 速度上限倍率，1.0 表示追踪过程中不允许超过发射时的速度。 */
    public static final ForgeConfigSpec.DoubleValue MAX_SPEED_MULTIPLIER;
    /** 两次目标搜索之间的最小间隔（游戏刻），越大越省性能。 */
    public static final ForgeConfigSpec.IntValue SEARCH_INTERVAL;
    /** 提前量系数，0 表示瞄准当前位置。 */
    public static final ForgeConfigSpec.DoubleValue PREDICTION_FACTOR;

    // ---------------- 视觉 / 调试 ----------------
    /** 是否显示追踪粒子效果。 */
    public static final ForgeConfigSpec.BooleanValue SHOW_PARTICLES;
    /** 粒子生成间隔（游戏刻）。 */
    public static final ForgeConfigSpec.IntValue PARTICLE_INTERVAL;
    /** 调试模式（向日志输出更多信息）。 */
    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE;

    // ---------------- 默认值（访问器回退用） ----------------
    private static final int DEF_INITIAL_DELAY = 2;
    private static final int DEF_MAX_TRACKING_TIME = 8;
    private static final double DEF_MIN_TRACKING_SPEED = 0.1D;
    private static final double DEF_CONE_ANGLE = 60.0D;
    private static final double DEF_MAX_TRACKING_RANGE = 7.0D;
    private static final double DEF_MAX_SPEED_MULTIPLIER = 1.0D;
    private static final int DEF_SEARCH_INTERVAL = 1;
    private static final double DEF_PREDICTION_FACTOR = 0.3D;
    private static final boolean DEF_SHOW_PARTICLES = false;
    private static final int DEF_PARTICLE_INTERVAL = 2;
    private static final boolean DEF_DEBUG_MODE = false;

    static {
        BUILDER.push("追踪箭设置");

        INITIAL_DELAY = BUILDER
                .comment("开始追踪前的延迟（游戏刻，20刻=1秒），用于保留出弓瞬间的自然弹道")
                .defineInRange("initialDelay", DEF_INITIAL_DELAY, 0, 40);

        MAX_TRACKING_TIME = BUILDER
                .comment("最大追踪时间（秒），超时后箭矢恢复普通弹道")
                .defineInRange("maxTrackingTime", DEF_MAX_TRACKING_TIME, 1, 30);

        SELF_TRACKING_DELAY = BUILDER
                .comment("允许追踪自己的延迟（秒）。当前实现永不锁定射手本人，此项仅保留用于兼容旧配置")
                .defineInRange("selfTrackingDelay", 1, 0, 5);

        MIN_TRACKING_SPEED = BUILDER
                .comment("最小追踪速度（低于此速度停止追踪）")
                .defineInRange("minTrackingSpeed", DEF_MIN_TRACKING_SPEED, 0.01D, 1.0D);

        CONE_ANGLE = BUILDER
                .comment("追踪锥形角度（度），以箭矢当前飞行方向为中心；180 表示整球搜索")
                .defineInRange("coneAngle", DEF_CONE_ANGLE, 10.0D, 180.0D);

        MAX_TRACKING_RANGE = BUILDER
                .comment("最大追踪范围（格），球形搜索半径")
                .defineInRange("maxTrackingRange", DEF_MAX_TRACKING_RANGE, 5.0D, 50.0D);

        MAX_SPEED_MULTIPLIER = BUILDER
                .comment("速度上限倍率，1.0 表示追踪时绝不超过发射瞬间的速度")
                .defineInRange("maxSpeedMultiplier", DEF_MAX_SPEED_MULTIPLIER, 0.1D, 3.0D);

        SEARCH_INTERVAL = BUILDER
                .comment("两次目标搜索之间的最小间隔（游戏刻），调大可降低实体查询开销")
                .defineInRange("searchInterval", DEF_SEARCH_INTERVAL, 1, 20);

        PREDICTION_FACTOR = BUILDER
                .comment("提前量系数，0 表示直接瞄准目标当前位置")
                .defineInRange("predictionFactor", DEF_PREDICTION_FACTOR, 0.0D, 1.0D);

        SHOW_PARTICLES = BUILDER
                .comment("是否显示追踪粒子效果")
                .define("showParticles", DEF_SHOW_PARTICLES);

        PARTICLE_INTERVAL = BUILDER
                .comment("粒子生成间隔（游戏刻）")
                .defineInRange("particleInterval", DEF_PARTICLE_INTERVAL, 1, 40);

        DEBUG_MODE = BUILDER
                .comment("调试模式（显示更多日志信息）")
                .define("debugMode", DEF_DEBUG_MODE);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    // ---------------- 安全访问器 ----------------

    /** 开始追踪前的延迟（游戏刻）。 */
    public static int initialDelayTicks() {
        return get(INITIAL_DELAY, DEF_INITIAL_DELAY);
    }

    /** 最大追踪时间（游戏刻）。 */
    public static int maxTrackingTicks() {
        return Math.max(1, get(MAX_TRACKING_TIME, DEF_MAX_TRACKING_TIME)) * 20;
    }

    /** 最小追踪速度。 */
    public static double minTrackingSpeed() {
        return get(MIN_TRACKING_SPEED, DEF_MIN_TRACKING_SPEED);
    }

    /** 追踪锥形角度对应的余弦值，用于避免每刻重复计算。 */
    public static double coneCosine() {
        double angle = get(CONE_ANGLE, DEF_CONE_ANGLE);
        return Math.cos(Math.toRadians(Math.max(10.0D, Math.min(180.0D, angle))));
    }

    /** 最大追踪范围（格）。 */
    public static double maxTrackingRange() {
        return get(MAX_TRACKING_RANGE, DEF_MAX_TRACKING_RANGE);
    }

    /** 速度上限倍率。 */
    public static double maxSpeedMultiplier() {
        return get(MAX_SPEED_MULTIPLIER, DEF_MAX_SPEED_MULTIPLIER);
    }

    /** 两次目标搜索之间的最小间隔（游戏刻）。 */
    public static int searchIntervalTicks() {
        return Math.max(1, get(SEARCH_INTERVAL, DEF_SEARCH_INTERVAL));
    }

    /** 提前量系数。 */
    public static double predictionFactor() {
        return get(PREDICTION_FACTOR, DEF_PREDICTION_FACTOR);
    }

    /** 是否显示追踪粒子。 */
    public static boolean showParticles() {
        return get(SHOW_PARTICLES, DEF_SHOW_PARTICLES);
    }

    /** 粒子生成间隔（游戏刻）。 */
    public static int particleIntervalTicks() {
        return Math.max(1, get(PARTICLE_INTERVAL, DEF_PARTICLE_INTERVAL));
    }

    /** 是否开启调试日志。 */
    public static boolean debugMode() {
        return get(DEBUG_MODE, DEF_DEBUG_MODE);
    }

    /**
     * 读取配置值，配置尚未加载时回退到默认值。
     * 这样在数据生成、配置重载等早期阶段调用也不会抛异常。
     */
    private static <T> T get(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T loaded = value.get();
            return loaded != null ? loaded : fallback;
        } catch (IllegalStateException e) {
            // 配置尚未加载
            return fallback;
        }
    }
}
