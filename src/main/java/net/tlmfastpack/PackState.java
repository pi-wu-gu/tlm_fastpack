package net.tlmfastpack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * 跳过/快速同步判定的全部状态（v1.1）。
 *
 * <p>v1.1 关键升级（前提均已字节码核实）：
 * <ul>
 *   <li>上游 {@code copyFolder} <strong>只覆盖、从不删除</strong>（0 处 delete）
 *       → "只拷差异"与"全量覆盖"最终产物<strong>完全等价</strong>；</li>
 *   <li>zip 中央目录自带每条目 size+CRC32（不解压可读）→ 逐条比对成本 ≈ 读 41MB 算 CRC。</li>
 * </ul>
 * 因此 HEAD 决策三态化：
 * <ol>
 *   <li>{@code SKIP}——磁盘与 jar 内容清单完全一致 → 直接取消（毫秒级）；</li>
 *   <li>{@code SYNC}——需要更新 → cancel + 自己用 {@code ZipFile} 直读做<strong>增量同步</strong>
 *       （走普通文件 IO，秒级；reset 时全量重建也是秒级）；</li>
 *   <li>{@code DELEGATE}——无法判定/快速通道失败 → 放行上游（慢但正确，最坏=现状）。</li>
 * </ol>
 * 每个分支都有日志（v1.0.0 静默分支的教训）。
 */
public final class PackState {

    private static final String TLM_MODID = "touhou_little_maid";
    private static final String TLM_MAIN_CLASS = "com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid";
    /** 默认包在 jar 内的资源前缀尾段（copyFolder 的 resource 参数以此为尾）。 */
    private static final String PACK_TAIL = "/tlm_custom_pack/" + "touhou_little_maid-1.0.0";
    /** 磁盘文件数低于该值视为目录不完整（当前默认包 1260 个文件）。 */
    private static final int MIN_FILES = 1000;
    private static final String MARKER_NAME = "tlm_fastpack.marker";
    private static final String MANIFEST_NAME = "tlm_fastpack.manifest";
    private static final String RESET_FLAG_NAME = "tlm_fastpack_reset.flag";

    /** HEAD cancel / 收尾去重标记（copyFolder 只会在一个线程上被调，ThreadLocal 足够）。 */
    private static final ThreadLocal<Boolean> CANCELLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PackState() {
    }

    /** jar 侧默认包清单：条目数、未压缩总字节、逐条目 [size, crc]。 */
    public record JarManifest(int count, long totalBytes, Map<String, long[]> entries) {
    }

    /** 判定结果。 */
    public enum Action {
        SKIP, SYNC, DELEGATE
    }

    public record Decision(Action action, String reason, boolean fullCopy) {
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

    // ---------------------------------------------------------------- marker / manifest

    private static Path markerFile(Path target) {
        return target.resolveSibling(MARKER_NAME);
    }

    private static Path manifestFile(Path target) {
        return target.resolveSibling(MANIFEST_NAME);
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

    /** 读取上次同步时的 jar 侧内容清单（relPath → [size, crc]）。缺失/损坏返回 null。 */
    private static Map<String, long[]> loadManifest(Path target) {
        Path f = manifestFile(target);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            Map<String, long[]> m = new LinkedHashMap<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 3) {
                    return null;
                }
                m.put(parts[0], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
            }
            return m.isEmpty() ? null : m;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean manifestEquals(Map<String, long[]> saved, Map<String, long[]> current) {
        if (saved.size() != current.size()) {
            return false;
        }
        for (Map.Entry<String, long[]> e : current.entrySet()) {
            long[] d = saved.get(e.getKey());
            if (d == null || d[0] != e.getValue()[0] || d[1] != e.getValue()[1]) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 统计

    /** TLM 的 mod 版本号（marker 记录用；v1.1 起版本变化不再强制全拷，只影响日志与追踪）。 */
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
     * {@code union:} 协议 URI，{@code Paths.get()} 直接炸。正解是走 FML 的 ModList
     * （注意 {@code getModFileById} 返回 {@code IModFileInfo}，路径方法是 {@code getFilePath()}）。
     */
    static Path tlmJarFile() {
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

    /** 读 zip 中央目录构建 jar 侧内容清单（不解压，毫秒级）。 */
    static JarManifest jarManifest(String resource) {
        Path jar = tlmJarFile();
        if (jar == null) {
            TlmFastPack.LOG.debug("[TLM FastPack] 无法定位 TLM jar 文件");
            return null;
        }
        String prefix = resource.startsWith("/") ? resource.substring(1) : resource;
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            int n = 0;
            long tot = 0L;
            Map<String, long[]> entries = new LinkedHashMap<>();
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(prefix)) {
                    continue;
                }
                String rel = e.getName().substring(prefix.length());
                n++;
                tot += e.getSize();
                entries.put(rel, new long[]{e.getSize(), e.getCrc()});
            }
            return n == 0 ? null : new JarManifest(n, tot, entries);
        } catch (IOException e) {
            TlmFastPack.LOG.debug("[TLM FastPack] 读 jar 中央目录失败: {}", e.toString());
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

    /** 磁盘文件清单（relPath → size），只 stat 不读内容。 */
    private static Map<String, Long> diskSizes(Path target) {
        Map<String, Long> m = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(target)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                try {
                    m.put(target.relativize(p).toString().replace('\\', '/'), Files.size(p));
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        return m;
    }

    private static long diskCrc(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            CRC32 crc = new CRC32();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) >= 0) {
                crc.update(buf, 0, r);
            }
            return crc.getValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 三态判定。★只对默认包资源调用（调用方先用 {@link #isDefaultPackResource} 过滤）。
     */
    public static Decision decide(String resource, Path target) {
        if (FastPackConfig.disableSkip()) {
            return new Decision(Action.DELEGATE, "紧急总闸 disableSkip=true，恢复上游行为", false);
        }
        JarManifest jm = jarManifest(resource);
        if (jm == null) {
            return new Decision(Action.DELEGATE, "jar 定位/统计失败，交上游处理", false);
        }
        if (!Files.isDirectory(target)) {
            return new Decision(Action.SYNC, "目标目录不存在（首次安装，直读 jar 建包）", false);
        }
        if (FastPackConfig.resetRequested()) {
            return new Decision(Action.SYNC, "reset 请求生效（快速通道全量重建）", true);
        }
        int disk = diskCount(target);
        if (disk < MIN_FILES) {
            return new Decision(Action.SYNC, "磁盘文件数 " + disk + " < " + MIN_FILES + "，增量补齐", false);
        }
        Properties m = loadMarker(target);
        Map<String, long[]> manifest = loadManifest(target);
        if (m != null && manifest != null
                && tlmVersion().equals(m.getProperty("tlmVersion"))
                && jm.count() == intOf(m, "entryCount")
                && jm.totalBytes() == longOf(m, "totalBytes")
                && manifestEquals(manifest, jm.entries())) {
            // jar 与上次同步时一致 → 只需核对磁盘文件集合
            Map<String, Long> diskMap = diskSizes(target);
            boolean diskOk = diskMap.size() == manifest.size();
            if (diskOk) {
                for (Map.Entry<String, long[]> e : manifest.entrySet()) {
                    Long ds = diskMap.get(e.getKey());
                    if (ds == null || ds != e.getValue()[0]) {
                        diskOk = false;
                        break;
                    }
                }
            }
            if (diskOk) {
                return new Decision(Action.SKIP,
                        "磁盘与 TLM " + tlmVersion() + " 默认包完全一致（" + diskMap.size() + " 文件）", false);
            }
            return new Decision(Action.SYNC, "磁盘文件集与清单有差异（增量补齐）", false);
        }
        String why;
        if (m == null) {
            why = "marker 缺失";
        } else if (manifest == null) {
            why = "内容清单缺失（v1 → v1.1 升级）";
        } else if (!tlmVersion().equals(m.getProperty("tlmVersion"))) {
            why = "TLM 版本变化（" + m.getProperty("tlmVersion") + " → " + tlmVersion() + "）";
        } else {
            why = "jar 内默认包内容变化";
        }
        return new Decision(Action.SYNC, why + "，增量同步", false);
    }

    // ---------------------------------------------------------------- 快速同步

    /**
     * 直读 jar 做增量同步（走普通文件 IO，不走 UnionFileSystem）。
     *
     * @param fullOverwrite true=忽略差异全部重写（reset 语义）；false=只拷缺失/size/CRC 不一致的条目
     * @return true=同步完成（调用方应 cancel 上游）；false=失败（调用方放行上游兜底）
     */
    public static boolean fastSync(String resource, Path target, boolean fullOverwrite) {
        JarManifest jm = jarManifest(resource);
        Path jar = tlmJarFile();
        if (jm == null || jar == null) {
            return false;
        }
        String prefix = resource.startsWith("/") ? resource.substring(1) : resource;
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        long t0 = System.nanoTime();
        int copied = 0;
        int checked = 0;
        try {
            Files.createDirectories(target);
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(prefix)) {
                        continue;
                    }
                    String rel = e.getName().substring(prefix.length());
                    if (rel.isEmpty()) {
                        continue;
                    }
                    checked++;
                    Path out = target.resolve(rel);
                    if (!fullOverwrite && Files.isRegularFile(out)) {
                        boolean same = Files.size(out) == e.getSize();
                        if (same) {
                            // 同尺寸再看 CRC（内容变化的最后防线；全部读一遍约 0.3s）
                            same = diskCrc(out) == e.getCrc();
                        }
                        if (same) {
                            continue;
                        }
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    copied++;
                }
            }
        } catch (Throwable t) {
            TlmFastPack.LOG.warn(
                    "[TLM FastPack] 快速同步中途失败（已拷 {} 条）——本次放行上游兜底，下次启动会重试: {}",
                    copied, t.toString());
            return false;
        }
        writeMarker(target, jm);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        TlmFastPack.LOG.info("[TLM FastPack] 快速同步完成：核对 {} 条 / 拷贝 {} 条 / 耗时 {} ms（{}）",
                checked, copied, ms, fullOverwrite ? "全量重建" : "增量");
        return true;
    }

    private static void writeMarker(Path target, JarManifest jm) {
        try {
            int disk = diskCount(target);
            Properties p = new Properties();
            p.setProperty("targetPath", target.toAbsolutePath().toString());
            p.setProperty("tlmVersion", tlmVersion());
            p.setProperty("entryCount", String.valueOf(jm.count()));
            p.setProperty("totalBytes", String.valueOf(jm.totalBytes()));
            p.setProperty("diskCount", String.valueOf(disk));
            Path mf = markerFile(target);
            Files.createDirectories(mf.getParent());
            try (OutputStream out = Files.newOutputStream(mf)) {
                p.store(out, "TLM FastPack marker - auto generated, do not edit");
            }
            StringBuilder sb = new StringBuilder("#TLM FastPack manifest - auto generated, do not edit\n");
            for (Map.Entry<String, long[]> e : jm.entries().entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()[0])
                        .append('\t').append(e.getValue()[1]).append('\n');
            }
            Files.writeString(manifestFile(target), sb.toString(), StandardCharsets.UTF_8);
            TlmFastPack.LOG.info("[TLM FastPack] marker/manifest 已写入（{} 文件 / {} 字节 / 清单 {} 条）",
                    jm.count(), jm.totalBytes(), jm.entries().size());
        } catch (Throwable t) {
            TlmFastPack.LOG.warn("[TLM FastPack] 写 marker/manifest 失败（下次仍会同步，不影响功能）: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------- 收尾

    /**
     * RETURN 注入点：区分"我们自己取消/完成"（marker 已写）与"上游完成全量拷贝"（这里补写 marker）。
     */
    public static void afterFullCopy(String resource, Path target) {
        if (!isDefaultPackResource(resource)) {
            return;
        }
        if (CANCELLED.get()) {
            CANCELLED.remove();
            return; // 我们走完了快速通道，marker/manifest 已写
        }
        CANCELLED.remove();
        // 上游完成了全量拷贝 → 重建 marker（自愈链）
        JarManifest jm = jarManifest(resource);
        if (jm != null) {
            writeMarker(target, jm);
        } else {
            TlmFastPack.LOG.warn("[TLM FastPack] 收尾统计失败——marker 未写，下次启动仍会同步");
        }
        try {
            Files.deleteIfExists(resetFlagFile());
        } catch (Throwable ignored) {
        }
        FastPackConfig.clearResetFlagInConfig();
        rewriteTomlFlagFallback();
    }

    public static void markCancelled() {
        CANCELLED.set(Boolean.TRUE);
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
