package net.tlmfastpack;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 配置（显示在「模组列表 → TLM FastPack → 配置」界面）。
 *
 * <p>★时序事实（aiplayer 工程实测）：mod 构造函数执行时配置<strong>尚未加载</strong>，
 * 直接读 ModConfigSpec 会抛 IllegalStateException。所以：
 * <ul>
 *   <li>mixin 运行期（TLM 构造阶段）不依赖本配置做跳过判定——只读<strong>标记文件</strong>；</li>
 *   <li>本配置仅在「用户于设置界面勾选 reset」后由 ModConfigEvent 落成 reset 标记文件，
 *       以及 mixin 收尾时复位。所有读取都包 try-catch。</li>
 * </ul>
 */
public final class FastPackConfig {

    public static final ModConfigSpec SPEC;
    /** 在设置界面勾选后，下次启动强制全量重拷默认包；拷完由 mixin 自动复位为 false。 */
    public static final ModConfigSpec.BooleanValue RESET_NEXT_LAUNCH;
    /** 紧急总闸：true = 本 mod 完全放行上游行为（每次全量拷贝）。 */
    public static final ModConfigSpec.BooleanValue DISABLE_SKIP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("TLM FastPack：跳过 TLM 默认包的每次启动全量重拷（详见 mod 描述）")
                .push("fastpack");
        RESET_NEXT_LAUNCH = b.comment(
                        "勾选后下次启动强制全量重拷默认包（拷完自动复位为 false）。")
                .define("resetNextLaunch", false);
        DISABLE_SKIP = b.comment(
                        "紧急开关：true 时本 mod 不做任何跳过，恢复上游行为（每次启动全量拷贝）。")
                .define("disableSkip", false);
        b.pop();
        SPEC = b.build();
    }

    /** 配置是否要求禁用跳过（配置未加载时按 false 处理）。 */
    public static boolean disableSkip() {
        try {
            return DISABLE_SKIP.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否有"强制重拷"请求（配置值或 reset 标记文件任一成立）。 */
    public static boolean resetRequested() {
        try {
            if (RESET_NEXT_LAUNCH.get()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 配置尚未加载——只看标记文件
        }
        return PackState.resetFlagPresent();
    }

    /** 尽力把配置复位为 false 并落盘；任何失败都不抛（mixin 收尾路径不能炸）。 */
    public static void clearResetFlagInConfig() {
        try {
            if (RESET_NEXT_LAUNCH.get()) {
                RESET_NEXT_LAUNCH.set(false);
                SPEC.save();
            }
        } catch (Throwable ignored) {
            // 配置未加载 / save 失败：TOML 兜底重写在 PackState 里做
        }
    }

    private FastPackConfig() {
    }
}
