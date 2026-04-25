# 网络包优化 | Not Enough Bandwidth (NEB) — Fabric 移植版

**Fabric 模组，适用于 Minecraft 1.21.11** — 通过精简包头、聚合 + Zstd 压缩、延迟区块缓存、持久化区块去重、区块光照剥离大幅削减网络流量。

> [!NOTE]
> Pre-release 版本可能包含实验性新功能以供提前测试。如希望抢先体验，请前往 [Releases 页面](https://github.com/RMS-Server/NotEnoughBandwidth/releases) 下载 Pre-release；否则请下载最新正式版。

> **如需适配更多版本或遇到问题**，欢迎提交 [issue](https://github.com/RMS-Server/NotEnoughBandwidth/issues)、加入 QQ 群 **362669270**（[邀请链接](https://qm.qq.com/q/Ch5CGWyjjc)），或发送邮件至 [support@rms.net.cn](mailto:support@rms.net.cn)。

> 本项目是 USS_Shenzhou 原版 [NeoForge 模组](https://github.com/USS-Shenzhou/NotEnoughBandwidth) 的非官方 Fabric 移植。
> 如有意向为上游项目贡献代码，请先在 [Discord](https://discord.gg/ZAn7U2BJpb) 与 USS_Shenzhou 沟通。

## 简介

NEB 在尽可能不影响模组和玩家正常使用的前提下，通过多种手段压缩 Minecraft 游戏过程中产生的网络流量。

在 TeaCon 甲辰的数据集上，相比未压缩的原始流量，NEB 理论上可将服务器出站流量降至原来的 **7.6%**；而原版默认压缩方案的出站流量约为原始数据的 39%。

在纯原版环境下实测，服务器出站流量降至原来的 **18%**。理论上，随着安装模组数量增多，传输内容更加复杂，压缩效果也会随之提升。

游戏内按 **N** 查看实时流量统计。

## 基准测试

NEB / 原版 zlib / 原版 raw 三种配置在 roam（跑图）、entities（多实体）两种场景下各运行 60 秒的实测对比,Zstd level 12,本地回环双 JVM。复现:`./gradlew benchmark` + `python3 scripts/plot_benchmark.py build/benchmark`。

![总带宽对比](docs/benchmark/summary_bars.png)

![roam 累积曲线](docs/benchmark/roam_timeseries.png)

![entities 累积曲线](docs/benchmark/entities_timeseries.png)

<details>
<summary>瞬时吞吐量散点图（点击展开）</summary>

![roam 瞬时带宽](docs/benchmark/roam_throughput.png)

![entities 瞬时带宽](docs/benchmark/entities_throughput.png)

</details>

## 主要功能

### 精简包头

优化 `CustomPacketPayload` 的编解码逻辑，以紧凑的数字索引替代包头中的 `Identifier`（即包类型字符串），将模组网络包包头开销压缩为固定的 3-4 字节，而非原本随类型字符串长度变化的可变长度。

> [!NOTE]
> ### 固定 8 位包头
> ```
> +------------- 1 byte (8 bits) ---------------+
> |               function flags                 |
> +---+---+--------------------------------------+
> | i | t |      reserved (6 bits)               |
> +---+---+--------------------------------------+
> ```
> - i = indexed（1 bit）
> - t = tight_indexed（1 bit，仅在 i=1 时有效）
> - reserved = 6 bits（保留位）
>
> ### 索引包类型
> - i=0（未索引）：后接完整的 UTF-8 `Identifier`。
> - i=1, t=0（索引，标准）：3 字节 — 12-bit namespace-id + 12-bit path-id（各支持 4096 个条目）。
> - i=1, t=1（索引，紧凑）：2 字节 — 8-bit namespace-id + 8-bit path-id（各支持 256 个条目）。

### 聚合与压缩

针对原版频繁发送大量小包的情况进行优化。在 `Connection` 层拦截发送操作，每 20ms 将待发包合并为一个大包，经 Zstd 压缩后统一发送。

> [!NOTE]
> ```
> +---+-----+----+----+----+----+----+----...
> | B | (S) | p0 | s0 | d0 | p1 | s1 | d1 ...
> +---+-----+----+----+----+----+----+----...
>           +--packet 1---++--packet 2---+
>           +---------compressed---------+
> ```
> - B = bool，是否已压缩
> - S = varint，未压缩数据的大小（仅压缩时存在）
> - p = prefix（medium/int/utf-8），子包类型
> - s = varint，子包大小
> - d = bytes，子包数据

### 延迟区块缓存（DCC）

原版中，玩家移动时服务端会立即通知客户端卸载身后的区块；若玩家折返，则需重新发送完整的区块数据。DCC 通过延迟卸载指令的下发，避免来回移动时反复传输相同的区块数据。

### 持久化区块缓存（PCC）

在客户端本地使用 LevelDB 持久化缓存区块数据，以内容的 64 位哈希为索引。每次连接时，客户端将所有已缓存区块哈希构建为 Bloom Filter 上报给服务端。服务端发送区块前查询该过滤器——若命中，则只发送 20 字节的哈希而非完整区块包（约 10–20 KB）；客户端直接从本地数据库加载。若出现 Bloom Filter 假阳性，客户端回退请求完整数据。每缓存 64 个新区块后 Bloom Filter 自动更新推送，当局游戏中即可享受缓存优化效果。

### 区块光照剥离

双端均安装 NEB 时，服务端在发送区块包时省略所有天光和方块光的 nibble 数组——原版最多每块携带 96 KB 原始光照数据。客户端在区块加载完成后由本地光照引擎重新计算全块光照，视觉效果与原版无差异。

探索工况实测（独立服务器）：**每块 baked 带宽降低约 27%，raw 带宽降低约 48%**。光照 nibble 数组几乎全由 Zstd 字典压缩消除（约 92%），因此 raw 降幅远大于 baked 降幅。服务端因方块变化发出的独立增量 `light_update` 包不受影响。

## 配置

配置文件路径：`config/NotEnoughBandwidthConfig.json`

### compatibleMode

> **客户端和服务端分别独立生效。**

是否启用兼容模式。设为 `true` 时，下方的 `blackList` 将被启用。

### blackList

> **客户端和服务端分别独立生效。**

兼容模式黑名单。黑名单内的包将被 NEB 跳过处理，可按需添加。

> [!WARNING]
> 为保证包的有序性，黑名单中的包会打断当前正在进行的聚合。若黑名单包数量较多或发送频率较高，聚合压缩的效率会明显下降。

### compressionLevel

> **客户端和服务端分别独立生效。**

Zstd 压缩等级（整数 1-19），默认为 **12**。数值越高压缩率越好，但 CPU 占用也越高。基准测试下 level 12 是压缩率与 CPU 成本的平衡点；若服务端 CPU 吃紧可降至 6,若 CPU 有余力可升至 19 进一步压榨带宽。

### contextLevel

> **客户端和服务端分别独立生效。**

压缩上下文窗口大小。可选范围为 21~25 的整数，对应 2~32MB。默认为 23（即 8MB）。窗口越大，压缩效果越好，流量节省越多，但内存占用也越高。

> [!TIP]
> 以 100 人服务器为例，设置为 25 时约需额外 3200MB 内存。

### dccSizeLimit, dccDistance, dccTimeout

> **仅在服务端生效。**

延迟区块缓存（DCC）的最大缓存区块数、缓存距离、缓存过期时间（秒）。值越大内存占用越高，值越小缓存命中率越低。

### chunkCacheEnabled

> **仅在客户端生效。**

是否启用持久化区块缓存（PCC）。默认为 `true`。

### chunkCacheMaxSizeMB

> **仅在客户端生效。**

本地区块缓存数据库的最大占用空间（MB）。默认为 `2048`（即 2 GB）。

### lightStripEnabled

> **仅在服务端生效（需双端均安装 NEB）。**

是否对 NEB 客户端的区块包执行光照剥离。客户端收到后在本地重新计算光照。默认为 `true`。设为 `false` 可关闭光照剥离，恢复原版光照传输行为。

## 安装

依赖要求：
- Minecraft 1.21.11
- Fabric Loader >= 0.19.2
- Fabric API

**客户端和服务端均需安装 NEB。** 若有客户端未安装 NEB，服务端会自动对该连接回退至原版行为。

## 版权和许可

Copyright (C) 2025 USS_Shenzhou

本模组是自由软件，你可以在 GNU 通用公共许可证（GPL）第 3 版或（由你选择的）更高版本的条款下再分发或修改它。该许可证由自由软件基金会发布。

本模组在发布时希望能有所帮助，但不提供任何形式的担保，包括对特定用途适用性的隐含担保。详见 GNU 通用公共许可证。

### 额外许可

当你作为游戏玩家将本程序加载至 Minecraft 并进行游玩时，本许可证自动授予你为正常游玩所必要的一切权利，包括 GPL-3.0 许可证范围之外、或 GPL-3.0 所不允许但游玩所需的权利。若 GPL-3.0 的内容与 Minecraft EULA 或其他 Mojang/微软条款相冲突，以后者为准。
