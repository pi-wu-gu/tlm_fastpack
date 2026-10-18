package net.tlmfastpack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * 跳过判定的全部状态：marker 文件 + reset 标记文件 + jar 侧统计。
 *
 * <p>设计原则（见兼容性分析文档）：
 * <ul>
 *   <li><strong>mixin 只读文件、不读配置</strong>——配置加载时序不可控，文件 IO 无时序问题；</li>
 *   <li>所有条件不满足时一律放行上游逻辑（全量拷贝），<strong>失败模式 = 回到现状</strong>；</li>
 *   <li>全量拷贝完成后由 mixin 收尾重建 marker → 下次恢复跳过（自愈链）。</li>
 * </ul>
 */
public final class PackState {

    private static final String TLM_MODID = "touhou_little_maid";
    private static final String TLM_MAIN_CLASS = "com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid";
    /** 默认包在 jar 内的资源前缀尾段（copyFolder 的 resource 参数以此为尾）。 */
    private static final String PACK_TAIL = "/tlm_custom_pack/" + "touhou_little_maid-1.0.0";
    /** 磁盘文件数低于该值视为目录不完整（当前默认包 1260 个文件）。 */
    private static final int MIN_FILES = 1000;
    private static final String MARKER_NAME = "tlm_fastpack.marker";
    private static final String RESET_FLAG_NAME = "tlm_fastpack_reset.flag";

    private PackState() {
    }

    /** jar 侧默认包统计（读 zip 中央目录，不解压、毫秒级）。 */
    record JarStats(int count, long totalBytes) {
    }

    // ---------------------------------------------------------------- reset 标记

    static Path resetFlagFile() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve(RESET_FLAG_NAME);
    }

    public static boolean resetFlagPresent() {
        try {
            return Files.isRegularFile(resetFlagFile());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户在设置界面勾选 reset（或手动建标记文件）后调用：落成标记文件。 */
    public static void requestReset() {
        try {
            Path f = resetFlagFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, "reset requested " + java.time.LocalDateTime.now(),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            TlmFastPack.LOG.warn("[TLM FastPack] 写入 reset 标记失败: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------- marker

    private static Path markerFile(Path target) {
        return target.resolveSibling(MARKER_NAME);
    }

    private static Properties loadMarker(Path target) {
        Path mf = markerFile(target);
        if (!Files.isRegularFile(mf)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(mf)) {
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 统计

    /** TLM 的 mod 版本号（用于 marker：版本变了强制重拷一次）。 */
    static String tlmVersion() {
        try {
            return ModList.get().getModContainerById(TLM_MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("(absent)");
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    public static boolean isDefaultPackResource(String resource) {
        return resource != null
                && resource.startsWith("/assets/" + TLM_MODID + PACK_TAIL)
                && resource.endsWith(PACK_TAIL);
    }

    /**
     * 定位 TLM 的 mod jar 文件。
     *
     * <p>★v1.0.0 踩坑：{@code getProtectionDomain().getCodeSource()} 在 SecureJar 下返回
     * {@code union:} 协议 URI，{@code Paths.get()} 直接炸——首启没跳过就是它。
     * 正解是走 FML 的 ModList 拿真实文件路径；codeSource 仅留作 file: 协议的后备。
     */
    private static Path tlmJarFile() {
        try {
            IModFileInfo mfi = ModList.get().getModFileById(TLM_MODID);
            if (mfi != null && mfi.getFile() != null) {
                Path p = mfi.getFile().getFilePath();
                if (p != null && Files.isRegularFile(p)) {
                    return p;
                }
                TlmFastPack.LOG.debug("[TLM FastPack] ModList 拿到的路径不是常规文件: {}", p);
            }
        } catch (Throwable t) {
            TlmFastPack.LOG.debug("[TLM FastPack] ModList 定位 jar 失败: {}", t.toString());
        }
        try {
            Class<?> tlm = Class.forName(TLM_MAIN_CLASS);
            java.security.CodeSource cs = tlm.getProtectionDomain().getCodeSource();
            if (cs != null) {
                URI uri = cs.getLocation().toURI();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    Path p = Paths.get(uri);
                    if (Files.isRegularFile(p)) {
                        return p;
                    }
                }
                TlmFastPack.LOG.debug("[TLM FastPack] codeSource 协议不可用: {}", uri);
            }
        } catch (Throwable t) {
            TlmFastPack.LOG.debug("[TLM FastPack] codeSource 定位失败: {}", t.toString());
        }
        return null;
    }

    /** 读 zip 中央目录统计默认包条目数与未压缩总字节（不解压）。失败返回 null → 不跳过。 */
    static JarStats jarStats(String resource) {
        Path jar = tlmJarFile();
        if (jar == null) {
            return null;
        }
        String prefix = resource.startsWith("/") ? resource.substring(1) : resource;
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            int n = 0;
            long tot = 0L;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                if (e.getName().startsWith(prefix)) {
                    n++;
                    tot += e.getSize();
                }
            }
            return n == 0 ? null : new JarStats(n, tot);
        } catch (IOException e) {
            return null;
        }
    }

    private static int diskCount(Path target) {
        try (Stream<Path> s = Files.walk(target)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 判定是否可以跳过本次拷贝。★只对默认包资源调用（调用方先用 {@link #isDefaultPackResource} 过滤）。
     */
    public static Decision decide(String resource, Path target) {
        if (FastPackConfig.disableSkip()) {
            return new Decision(false, "紧急总闸 disableSkip=true，恢复上游行为");
        }
        if (FastPackConfig.resetRequested()) {
            return new Decision(false, "reset 请求生效，本次强制全量重拷");
        }
        if (!Files.isDirectory(target)) {
            return new Decision(false, "目标目录不存在（首次启动）");
        }
        int disk = diskCount(target);
        if (disk < MIN_FILES) {
            return new Decision(false, "磁盘文件数 " + disk + " < " + MIN_FILES + "，目录不完整，全拷自愈");
        }
        Properties m = loadMarker(target);
        if (m == null) {
            return new Decision(false, "marker 缺失或损坏");
        }
        if (!target.toAbsolutePath().toString().equals(m.getProperty("targetPath"))) {
            return new Decision(false, "marker 记录的目标路径与当前不符");
        }
        if (!tlmVersion().equals(m.getProperty("tlmVersion"))) {
            return new Decision(false, "TLM 版本变化（" + m.getProperty("tlmVersion") + " → " + tlmVersion() + "）");
        }
        int markerDisk = intOf(m, "diskCount");
        if (markerDisk != disk) {
            return new Decision(false, "磁盘文件数 " + disk + " ≠ marker 记录 " + markerDisk + "（用户增删过文件）");
        }
        JarStats js = jarStats(resource);
        if (js == null) {
            return new Decision(false, "jar 统计失败（定位或读取）");
        }
        if (js.count() != intOf(m, "entryCount") || js.totalBytes() != longOf(m, "totalBytes")) {
            return new Decision(false, "jar 内默认包内容变化（" + js.count() + " 文件 / " + js.totalBytes() + " 字节）");
        }
        return new Decision(true, "目录与 TLM " + m.getProperty("tlmVersion") + " 默认包一致（" + disk + " 文件）");
    }

    /** 判定结果：skip=true 时 reason 说明为何可跳过；skip=false 时 reason 说明为何必须全拷。 */
    public record Decision(boolean skip, String reason) {
    }

    /** 全量拷贝完成后调用：写 marker + 清 reset 标记（自愈链的重建点）。 */
    public static void afterFullCopy(String resource, Path target) {
        if (!isDefaultPackResource(resource)) {
            return;
        }
        JarStats js = jarStats(resource);
        int disk = diskCount(target);
        if (js != null && disk >= 0) {
            try {
                Properties p = new Properties();
                p.setProperty("targetPath", target.toAbsolutePath().toString());
                p.setProperty("tlmVersion", tlmVersion());
                p.setProperty("entryCount", String.valueOf(js.count()));
                p.setProperty("totalBytes", String.valueOf(js.totalBytes()));
                p.setProperty("diskCount", String.valueOf(disk));
                Path mf = markerFile(target);
                Files.createDirectories(mf.getParent());
                try (OutputStream out = Files.newOutputStream(mf)) {
                    p.store(out, "TLM FastPack marker - auto generated, do not edit");
                }
                TlmFastPack.LOG.info(
                        "[TLM FastPack] marker 已写入（{} 文件 / {} 字节）——下次启动若内容未变将直接跳过拷贝",
                        js.count(), js.totalBytes());
            } catch (Throwable t) {
                TlmFastPack.LOG.warn("[TLM FastPack] 写 marker 失败（下次仍会全拷，不影响功能）: {}", t.toString());
            }
        } else {
            // ★v1.0.0 教训：这里原来是静默分支，jar 定位失败根本看不出来
            TlmFastPack.LOG.warn(
                    "[TLM FastPack] 收尾统计失败（jar={} 磁盘={}）——marker 未写，下次启动仍会全量拷贝",
                    js == null ? "定位失败" : "ok", disk);
        }
        try {
            Files.deleteIfExists(resetFlagFile());
        } catch (Throwable ignored) {
        }
        FastPackConfig.clearResetFlagInConfig();
        rewriteTomlFlagFallback();
    }

    /** 兜底：直接改 TOML 文本，确保 resetNextLaunch=false（防配置 API 时序问题）。 */
    private static void rewriteTomlFlagFallback() {
        try {
            Path toml = FMLPaths.GAMEDIR.get().resolve("config").resolve("tlm_fastpack-common.toml");
            if (!Files.isRegularFile(toml)) {
                return;
            }
            String s = Files.readString(toml, StandardCharsets.UTF_8);
            if (s.contains("resetNextLaunch = true")) {
                Files.writeString(toml,
                        s.replace("resetNextLaunch = true", "resetNextLaunch = false"),
                        StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int intOf(Properties p, String k) {
        try {
            return Integer.parseInt(p.getProperty(k, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long longOf(Properties p, String k) {
        try {
            return Long.parseLong(p.getProperty(k, "-1"));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
