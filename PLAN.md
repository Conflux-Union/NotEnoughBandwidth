# NEB Fabric Port Plan — MC 1.21.4

## Overview

Port **NotEnoughBandwidth** from NeoForge (MC 26.1) to **Fabric** (MC 1.21.4).

Original NeoForge source archived in `reference-neoforge/`.

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

## Architecture

NEB has three core features. Each is largely independent:

1. **Indexed Packet Headers** — replace `Identifier` strings with compact VarInt indices
2. **Packet Aggregation + Zstd** — buffer small packets per-connection, flush every 20ms as one compressed blob
3. **Delayed Chunk Cache (DCC)** — keep recently-left chunks in client memory to avoid re-sending

## Port Phases

### Phase 0: Project Skeleton
- [x] Archive NeoForge source
- [ ] Set up Fabric Loom build (`build.gradle`, `settings.gradle`, `gradle.properties`)
- [ ] Create `fabric.mod.json`, mixin config, access widener
- [ ] Verify `./gradlew build` compiles empty mod

### Phase 1: Platform-Agnostic Core
Zero or near-zero changes. Just copy and adjust imports.

- [ ] `config/` — ConfigHelper, TConfig (pure Gson + file IO)
- [ ] `zstd/` — ZstdHelper, Context (pure zstd-jni)
- [ ] `stat/` — SimpleStatManager, SimpleStatData, TimeCounter
- [ ] `util/PacketUtil` — vanilla packet inspection
- [ ] `util/DefaultChannelPipelineHelper` — Netty reflection
- [ ] `indextype/CustomPacketPrefixHelper` — VarInt index encoding/decoding
- [ ] `aggregation/AggregationFlushHelper` — timing constants

### Phase 2: Vanilla Mixins
Target vanilla classes — portable with signature adjustments for 1.21.4.

- [ ] `ConnectionMixin` — intercept `send()`, de-bundle, aggregate
- [ ] `PacketEncoderMixin` — record outbound stats
- [ ] `PacketDecoderMixin` — record inbound stats
- [ ] `CustomPacketPayloadMixin` — redirect Identifier write/read to indexed prefix
- [ ] `ChunkMapMixin` — override `updateChunkTracking` for DCC
- [ ] `PlayerListMixin` — extend view distance by `dccDistance`
- [ ] `CachedChunkTrackingView` — DCC tracking logic

### Phase 3: Fabric-Specific Rewrites
NeoForge APIs → Fabric API equivalents.

| NeoForge | Fabric Equivalent |
|---|---|
| `@Mod` + `IEventBus` | `ModInitializer` / `ClientModInitializer` |
| `@EventBusSubscriber` + `RegisterPayloadHandlersEvent` | `PayloadTypeRegistry.playS2C()` / `.playC2S()` + `ServerPlayNetworking` / `ClientPlayNetworking` |
| `NetworkRegistry.getCodec()` | Mixin or reflection into Fabric's `CustomPayloadTypeProvider` internals |
| `NetworkRegistry` mixin (server init) | Mixin vanilla `ServerConfigurationNetworkHandler` or Fabric event |
| `ClientNetworkRegistry` mixin (client init) | Mixin vanilla `ClientConfigurationNetworkHandler` or Fabric event |
| `RegisterKeyMappingsEvent` | `KeyBindingHelper.registerKeyBinding()` |
| `HandlerThread.NETWORK` | Handler runs on Netty thread by default in Fabric |
| Access Transformer (.cfg) | Access Widener (.accesswidener) |
| `GenericPacketSplitter` (StackWalker check) | Remove or replace — Fabric doesn't have this class |

- [ ] Mod entrypoint (`NotEnoughBandwidth` → `ModInitializer`)
- [ ] Client entrypoint (key binding, stat screen)
- [ ] Network registration (PacketAggregationPacket, StatQuery, StatRespond)
- [ ] AggregatedEncodePacket — replace `NetworkRegistry.getCodec()` with Fabric equivalent
- [ ] AggregatedDecodePacket — same
- [ ] NamespaceIndexManager — replace NeoForge registration lookup
- [ ] Access Widener for `IdDispatchCodec` internals

### Phase 4: Index Synchronization Handshake
NeoForge gives this for free via modded network negotiation. Fabric doesn't.

**Strategy:** Implement a configuration-phase handshake:
1. Register a `ConfigurationS2CPacketType` for index table sync
2. Server collects all registered `CustomPacketPayload` types, sorts them, assigns indices
3. Server sends the index table to client during configuration phase
4. Client installs the same index table
5. Both sides are now synchronized before PLAY phase begins

- [ ] Design handshake packet format
- [ ] Implement server-side configuration handler
- [ ] Implement client-side configuration handler
- [ ] Wire into NamespaceIndexManager.init()

### Phase 5: Integration & Polish
- [ ] Verify compile
- [ ] Test in dev environment
- [ ] Update README.md

## Key Mapping Differences (NeoForge 26.1 → Fabric Yarn 1.21.4)

| NeoForge 26.1 (Mojmap) | Fabric 1.21.4 (Yarn) |
|---|---|
| `net.minecraft.resources.Identifier` | `net.minecraft.util.Identifier` |
| `FriendlyByteBuf` | `net.minecraft.network.PacketByteBuf` |
| `RegistryFriendlyByteBuf` | `net.minecraft.network.RegistryByteBuf` |
| `Component` | `net.minecraft.text.Text` |
| `KeyMapping` | `net.minecraft.client.option.KeyBinding` |
| `Connection` | `net.minecraft.network.ClientConnection` |
| `PacketEncoder` | `net.minecraft.network.handler.PacketEncoder` |
| `PacketDecoder` | `net.minecraft.network.handler.PacketDecoder` |
| `ChunkMap` | `net.minecraft.server.world.ChunkHolder` or `ThreadedAnvilChunkStorage` |
| `PlayerList` | `net.minecraft.server.PlayerManager` |
| `ServerPlayer` | `net.minecraft.server.network.ServerPlayerEntity` |
| `ChunkPos` | `net.minecraft.util.math.ChunkPos` |
| `IdDispatchCodec` | `net.minecraft.network.codec.PacketCodecDispatcher` (verify) |

## Risk Assessment

| Risk | Impact | Mitigation |
|---|---|---|
| Anonymous class `CustomPacketPayload$1` numbering differs | HIGH | Verify in 1.21.4 decompiled source; use named target if possible |
| `@Overwrite` on ChunkMap conflicts with other mods | MEDIUM | Consider `@Inject` + `@Redirect` combo instead |
| Index table desync between client/server | HIGH | Robust handshake with version/hash verification |
| Fabric API internal changes | LOW | Pin Fabric API version; use stable APIs where possible |
