# NEB Fabric Port Plan — MC 1.21.4

## Status: COMPLETE

All features ported from NeoForge (MC 26.1) to Fabric (MC 1.21.4).

## Toolchain

| Component | Version |
|---|---|
| Minecraft | 1.21.4 |
| Fabric Loader | 0.18.6 |
| Fabric API | 0.119.4+1.21.4 |
| Yarn Mappings | 1.21.4+build.8 |
| Loom | 1.9-SNAPSHOT |
| Java | 21 |
| zstd-jni | 1.5.6-9 (jar-in-jar) |

## Completed Phases

### Phase 0: Project Skeleton
- [x] Archive NeoForge source to `reference-neoforge/`
- [x] Set up Fabric Loom build
- [x] Create `fabric.mod.json`, mixin config, access widener
- [x] Verify `./gradlew build` compiles

### Phase 1: Platform-Agnostic Core
- [x] `config/` — ConfigHelper, TConfig
- [x] `zstd/` — ZstdHelper, Context
- [x] `stat/` — SimpleStatManager, SimpleStatData, TimeCounter
- [x] `util/PacketUtil`, `util/DefaultChannelPipelineHelper`
- [x] `indextype/CustomPacketPrefixHelper`
- [x] `aggregation/AggregationFlushHelper`

### Phase 2: Vanilla Mixins
- [x] `ConnectionMixin` — intercept send(), de-bundle, aggregate
- [x] `PacketEncoderMixin` (target: `EncoderHandler`) — outbound stats
- [x] `PacketDecoderMixin` (target: `DecoderHandler`) — inbound stats
- [x] `CustomPacketPayloadMixin` (target: `CustomPayload$1`) — indexed headers
- [x] `ChunkMapMixin` (target: `ServerChunkLoadingManager.sendWatchPackets`) — DCC
- [x] `PlayerListMixin` (target: `PlayerManager.setViewDistance`) — DCC distance
- [x] `CachedChunkTrackingView` — DCC tracking with `ChunkFilter` API

### Phase 3: Fabric-Specific Rewrites
- [x] Mod entrypoints (`ModInitializer` + `ClientModInitializer`)
- [x] Network registration via `PayloadTypeRegistry`
- [x] Key binding via `KeyBindingHelper`
- [x] `AggregatedEncodePacket` / `AggregatedDecodePacket` — unified dispatch codec
- [x] `NamespaceIndexManager` — index table builder
- [x] Access Widener for `PacketCodecDispatcher`, `EncoderHandler`, `DecoderHandler`, `ClientConnection`

### Phase 4: Index Synchronization Handshake
- [x] `IndexSyncPayload` — sorted payload type list
- [x] `IndexSyncHandler` — server sends on JOIN, client installs
- [x] `collectRegisteredTypes()` — reflection into `PayloadTypeRegistryImpl.packetTypes`

### Phase 5: DCC Chunk Tickets
- [x] `ChunkTicketType<ChunkPos>` with configurable expiry
- [x] `putTicket` callback via `ChunkTicketManager.addTicket`

## Key Mapping Differences Applied

| NeoForge 26.1 | Fabric Yarn 1.21.4 |
|---|---|
| `Connection` | `ClientConnection` |
| `PacketEncoder` (handler) | `EncoderHandler` |
| `PacketDecoder` (handler) | `DecoderHandler` |
| `ChunkMap` | `ServerChunkLoadingManager` |
| `ChunkTrackingView` | `ChunkFilter` |
| `ChunkTrackingView.Positioned` | `ChunkFilter.Cylindrical` |
| `PlayerList` | `PlayerManager` |
| `IdDispatchCodec` | `PacketCodecDispatcher` |
| `ProtocolInfo` | `NetworkState` |
| `ConnectionProtocol` | `NetworkPhase` |
| `PacketFlow` | `NetworkSide` |
| `updateChunkTracking()` | `sendWatchPackets()` |
| `markChunkPendingToSend()` | `track()` |
| `dropChunk()` | `untrack()` |
