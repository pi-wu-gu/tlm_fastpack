package net.tlmfastpack.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.tlmfastpack.PackState;
import net.tlmfastpack.TlmFastPack;

/**
 * 注入 TLM 的 {@code GetJarResources.copyFolder(String, Path)}。
 *
 * <p>★兼容性设计（见 docs/2026-10-18-TLM快速解压patch-兼容性分析.md）：
 * <ul>
 *   <li>{@code @Pseudo} + {@code require=0}：目标类不存在（TLM 未装/改名）时静默不生效，不报错；</li>
 *   <li>目标类是 <strong>mod 自己的类</strong>（非 MC 混淆类），无需 refmap，MC 版本升级无感；</li>
 *   <li>HEAD 处任何异常都被吞掉并放行原逻辑——<strong>最坏情况 = 回到上游慢路径</strong>。</li>
 * </ul>
 *
 * <p>v1.1：HEAD 决策三态化——
 * {@code SKIP}（内容一致，毫秒级取消）／
 * {@code SYNC}（cancel 后自己用 ZipFile 直读做增量同步，秒级；失败自动放行上游）／
 * {@code DELEGATE}（放行上游）。
 */
@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.util.GetJarResources")
public abstract class FastPackMixin {

    @Inject(method = "copyFolder(Ljava/lang/String;Ljava/nio/file/Path;)V",
            at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void tlmfastpack$maybeSkip(String resource, Path target, CallbackInfo ci) {
        try {
            if (!PackState.isDefaultPackResource(resource)) {
                return; // 只处理 TLM 默认包这一处，其他调用一概不碰、不打扰
            }
            PackState.Decision d = PackState.decide(resource, target);
            switch (d.action()) {
                case SKIP -> {
                    TlmFastPack.LOG.info("[TLM FastPack] 已跳过默认包处理（{}）——省 8-19 分钟", d.reason());
                    PackState.markCancelled();
                    ci.cancel();
                }
                case SYNC -> {
                    TlmFastPack.LOG.info("[TLM FastPack] {}——走快速通道（直读 jar，秒级）", d.reason());
                    if (PackState.fastSync(resource, target, d.fullCopy())) {
                        PackState.markCancelled();
                        ci.cancel();
                    } else {
                        // fastSync 失败已 WARN；不 cancel → 上游全量拷贝兜底（慢但正确）
                    }
                }
                case DELEGATE -> TlmFastPack.LOG.info("[TLM FastPack] {}", d.reason());
            }
        } catch (Throwable t) {
            TlmFastPack.LOG.warn("[TLM FastPack] 判定异常，放行上游全量拷贝: {}", t.toString());
        }
    }

    /**
     * RETURN（全部正常返回点）：上游拷贝完成后重建 marker + 清 reset 标记。
     * 我们自己 cancel 的路径由 ThreadLocal 去重（marker 已在快速通道里写过）。
     */
    @Inject(method = "copyFolder(Ljava/lang/String;Ljava/nio/file/Path;)V",
            at = @At("RETURN"), require = 0, expect = 0)
    private static void tlmfastpack$afterCopy(String resource, Path target, CallbackInfo ci) {
        try {
            PackState.afterFullCopy(resource, target);
        } catch (Throwable t) {
            TlmFastPack.LOG.warn("[TLM FastPack] 收尾处理异常: {}", t.toString());
        }
    }
}
