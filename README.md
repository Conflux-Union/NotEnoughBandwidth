# Not Enough Bandwidth (NEB) — Fabric Port

**Fabric mod for Minecraft 1.21.4** — Network bandwidth optimization.

> This is an unofficial Fabric port of the original [NeoForge mod](https://github.com/USS-Shenzhou/NotEnoughBandwidth) by USS_Shenzhou.
> If you want to contribute to the upstream project, please discuss with USS_Shenzhou on [Discord](https://discord.gg/ZAn7U2BJpb) first.

## Introduction

NEB uses various methods to save as much network traffic as possible during Minecraft gameplay, while remaining transparent to both mods and players.

In the TeaCon Jiachen dataset, compared to raw uncompressed data, NEB can theoretically reduce the server's outbound traffic to **7.6%** of its original size. For comparison, the outbound traffic of Vanilla's default compression mechanism is 39% of the original data size.

In tests conducted in a Vanilla environment, the server outbound traffic was reduced to **18%** of its original size. As the number of installed mods increases, compression performance improves.

Press **Alt+N** in-game to view the network traffic status.

## Main Features

### Compact Packet Header

Optimizes `CustomPacketPayload` encoding and decoding by replacing the packet header `Identifier` (Packet Type) with a compact VarInt index. This reduces the mod network packet header overhead to a fixed 3-4 bytes, instead of the length of the string corresponding to the network packet type.

### Aggregation and Compression

Optimizes the situation where vanilla often produces a large number of small network packets. Intercepts transmission at the `Connection` level, assembles them into one large network packet every 20ms, and sends it after Zstd compression.

### Delayed Chunk Cache (DCC)

In Vanilla, when a player moves, the server instructs the client to immediately forget the chunks behind them. By delaying this "forgetting", the chunk transmission traffic generated when moving back and forth can be saved.

## Config

Modify the configuration file at `config/NotEnoughBandwidthConfig.json`.

### compatibleMode
Whether to enable compatibility mode. If set to `true`, the `blackList` below will be used. Works independently on client and server.

### blackList
Packets listed here will be skipped by NEB. By default, it includes command-related and player info packets. Works independently on client and server.

### contextLevel
The Zstd context window size (integer 21–25, representing 2–32MB). Default is 23 (8MB). Larger = better compression, more memory. Works independently on client and server.

### dccSizeLimit, dccDistance, dccTimeout
DCC parameters: max cached chunks, cache distance, cache timeout (seconds). Server only.

## Installation

Requires:
- Minecraft 1.21.4
- Fabric Loader ≥ 0.18.0
- Fabric API

## License

Copyright (C) 2025 USS_Shenzhou

This mod is free software under the GNU GPL 3.0. See [LICENSE](LICENSE) for details.
