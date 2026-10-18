package net.tlmfastpack;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * TLM FastPack —— 跳过 Touhou Little Maid 默认包的每次启动全量重拷。
 *
 * <p>根因与收益见 docs/2026-10-18-启动与运行缓慢-根因分析.md：
 * 上游 {@code GetJarResources.copyFolder} 走 FML UnionFileSystem，
 * 1260 个文件实测 8-19 分钟；内容未变时本 mod 直接短路，降到毫秒级。
 *
 * <p><strong>重置入口（模组设置）</strong>：游戏内「模组列表 → TLM FastPack → 配置」
 * 勾选 {@code resetNextLaunch} → 事件监听立刻落成 reset 标记文件并复位勾选
 * → 下次启动强制全量拷贝一次（拷完自动恢复跳过）。
 */
@Mod(value = TlmFastPack.MODID, dist = Dist.CLIENT)
public class TlmFastPack {

    public static final String MODID = "tlm_fastpack";
    public static final Logger LOG = LogUtils.getLogger();

    public TlmFastPack(IEventBus modEventBus, ModContainer modContainer) {
        // 配置 + 配置界面（Mods → TLM FastPack → 配置）
        modContainer.registerConfig(ModConfig.Type.COMMON, FastPackConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // ModConfigEvent implements IModBusEvent（字节码实证）→ 只挂 mod 总线
        modEventBus.addListener(this::onConfig);
    }

    private void onConfig(ModConfigEvent event) {
        try {
            if (event.getConfig().getSpec() != FastPackConfig.SPEC) {
                return;
            }
            if (FastPackConfig.RESET_NEXT_LAUNCH.get()) {
                PackState.requestReset();
                LOG.info("[TLM FastPack] 已从设置界面收到重置请求：下次启动将强制全量重拷默认包");
                FastPackConfig.RESET_NEXT_LAUNCH.set(false);
                FastPackConfig.SPEC.save();
            }
        } catch (Throwable t) {
            // 事件路径失败不致命：mixin 收尾还有 TOML 文本兜底
            LOG.warn("[TLM FastPack] 配置事件处理失败（reset 仍可能经 TOML 兜底生效）: {}", t.toString());
        }
    }
}
