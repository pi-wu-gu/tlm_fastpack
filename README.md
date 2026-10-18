# TLM FastPack

跳过 Touhou Little Maid（车万女仆）每次启动对默认资源包的**无条件全量重拷**。

## 背景

TLM 的 `CommonDefaultPack.initCommonDefaultPack()` 每次启动都把 jar 内默认包
（1260 文件 / 41MB）重拷到 `<gamedir>/tlm_custom_pack/touhou_little_maid-1.0.0/`，
且拷贝走 FML SecureJar 的 **UnionFileSystem**（`Class.getResource → Paths.get(URI)`），
每次文件操作 300-900ms——实测单次启动耗时 **8-19 分钟**。

基准测试证明解压本身只要 **0.96 秒**（jdk.nio.zipfs 同构路径 0.8ms/文件），
慢 400-1000 倍的全在文件系统协议层。详见
`aiplayer/docs/2026-10-18-启动与运行缓慢-根因分析.md`。

## 本 mod 做什么

注入 `GetJarResources.copyFolder(String, Path)` HEAD：

- **跳过条件**（全满足才短路）：目标目录 ≥1000 文件 + marker 记录的 TLM 版本一致 +
  jar 内条目数/未压缩总字节与 marker 一致（读 zip 中央目录，毫秒级）+ 磁盘文件数与 marker 一致
- **自动回退**：首次启动 / TLM 升级 / 目录损坏 / 内容变化 → 放行上游全量拷贝，
  完成后重建 marker（自愈链）
- **重置入口（模组设置）**：游戏内「模组列表 → TLM FastPack → 配置」勾选
  `resetNextLaunch` → 下次启动强制全量重拷一次，拷完自动复位
- **紧急总闸**：`disableSkip = true` 恢复上游行为

## v1.1 实测（2026-10-18，NeoForge 21.1.236 / MC 1.21.1 / TLM 1.5.3）

| 场景 | 之前（上游路径） | v1.1 快速通道 |
|---|---|---|
| 内容未变（跳过） | 471.8 / 813.8 / 1113.6 s | **0.14-0.79 s** |
| 内容清单升级（diff=0） | 8-19 min | **2.1 s**（含 41MB CRC 校验） |
| reset 全量重建 | **21.8 分钟**（实测最慢一次） | **12-14 s**（1260 条直读重写） |

★v1.1.1 关键修复：REPLACE_EXISTING 在 Windows 上 = DeleteFile0 删旧+建新，
本机删一个文件实测 2.6 秒——这也是上游全量拷贝慢的另一半根源。
改用 CREATE+TRUNCATE_EXISTING 截断覆写后，全量重建从 21.8 分钟降到 14 秒。

注：本 mod 是独立模组，版本线 1.x 自行递增，与主模组 aiplayer 的 0.2.1.x 版本线互不相干。

## 兼容性

- `@Pseudo` + `require=0`：TLM 未装/重构时静默不生效，**最坏情况 = 回到上游慢路径**
- 目标是 mod 类（非 MC 混淆类），无需 refmap
- 副产品：用户对 `tlm_custom_pack` 的编辑不再被每次启动覆盖

## 构建

```bash
./gradlew build   # 产物 build/libs/tlm_fastpack-1.0.0.jar，丢进 mods/ 即用
```
