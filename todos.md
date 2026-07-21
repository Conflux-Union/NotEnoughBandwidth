# TODOs — upstream comparison audit (2026-07-15, re-verified 2026-07-21)

Source-level comparison against upstream [USS-Shenzhou/NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth) `master` (MC 26.1).
Our last feature sync was 2026-04-26 (`be1881c`); upstream landed fixes after that point.
Findings verified against both codebases; chunk items additionally verified against the vanilla 26.1 jar and upstream git history. No build/runtime verification.
2026-07-21: full semantic comparison against upstream `fffdec7` (HEAD, unchanged since 2026-06-22) re-confirmed every open item below, corrected the DCC ticket item, and added the inbound raw-stat item. Core mechanisms (aggregation envelope, zstd streaming context, namespace index table, mixin injection points) are semantically identical; all other differences are platform adaptation, deliberate port fixes, or port-only features.

2026-07-21 (implementation): all 14 items closed in six fix commits (`a26d58a..4683e41`). Verified: all 7 versions compile, full `./gradlew build` produces all jars, 26.1 dedicated-server smoke boot (mixins apply, NEB initializes, status ping round-trips through the mixed Varint21 pipeline). Not verified: client runtime, cross-version network sessions. Notes that extend or correct the audit text below:

- Varint21 mixins are plain-Mixin ports (`@ModifyConstant` + `@Redirect`; the 1.21.1 loader bundles MixinExtras 0.4.x, upstream needs 0.5.3+ for `@Expression`), skipped on 1.20.1 via NebMixinPlugin (sendBatched already splits). `maxPacketSizeByte` is `transient` — this repo's Gson ignores `@Expose` (no `excludeFieldsWithoutExposeAnnotation`), upstream silently persists its cache — and `parseByteSize` catches NumberFormatException ("1.2.3MB" would crash upstream on the decode path).
- DCC anchor/flags: MC>=12002 only. 1.21.6 gets `TicketUse.LOADING_AND_SIMULATION` (no expire-if-unloaded equivalent exists there); 1.21.1/1.21.4 have no flags API; the 1.20.1 branch keeps per-chunk region tickets by design — its DCC needs departed chunks loaded for vanilla resend.
- Replay opt-out ports the intent (`!contains`) and additionally nulls the dict for listed players (dict-referencing frames are equally replay-hostile). The dict+useContext pair is pinned per connection at server JOIN — the exact snapshot sent in DictionarySyncPayload — which closes the handoff race; a dict trained mid-session reaches only connections established afterwards.
- Sampling is keyed off SERVER_STARTING/SERVER_STOPPED (dedicated boot unchanged; a physical client samples only while hosting). Training completion adopts the dict in memory only while still hosting; it is always persisted to disk.
- ChunkRequestPayload guard: tracking-view containment (chessboard view+dcc distance on 1.20.1) plus a per-connection rate limit (100 per 10 s, hardcoded, self-cleaning weak cache).
- VANILLA_PATHS audit correction (extracted from GamePacketTypes bytecode, superseding the item text below): the `horse_screen_open`→`mount_screen_open` and `debug_sample_subscription`→`debug_subscription_request` renames plus `game_test_highlight_pos` and the four `debug/*` ids already exist on 1.21.11 — the old list was stale there too (unindexed fallback only, tolerated). The true 26.1 set is 184 paths and additionally gains `attack`, `game_rule_values`, `set_game_rule`, `spectate_entity`, `low_disk_space_warning`. Sync-safety is a dedicated S2C `neb:vanilla_paths` payload consumed once per handshake, NOT a trailing IndexSyncPayload field — old clients hard-disconnect on leftover payload bytes ("Packet was larger than I expected") but silently discard an unknown channel, so every old×new pairing behaves exactly as today.

## High

- [x] **Port upstream `39b076c` "fix packet size limit" — aggregates can exceed the 2MB frame limit and kick players.**
  `AggregationManager.flushInternal` (`aggregation/AggregationManager.java:112-144`) packs one flush window (20ms, up to 3 waited cycles) into a single aggregate with no size cap or splitting. We have no `Varint21FrameDecoderMixin` / `Varint21LengthFieldPrependerMixin` and no `maxPacketSize` config. Vanilla frame length is a 3-byte VarInt (max 2,097,151 bytes); an oversized frame makes `Varint21LengthFieldPrepender` throw `EncoderException` → player kicked with "Internal Exception". Chunk bursts on join/teleport at high view distance can trigger this. Upstream hit it in production; NeoForge at least had `GenericPacketSplitter` as a safety net — Fabric has nothing, so we are more exposed than pre-fix upstream.
  Fix: port both Varint21 mixins + `maxPacketSize` parsing (2–64MB clamp) from upstream `NotEnoughBandwidthConfig`.

- [x] **Clamp the decompressed-size VarInt — remote direct-memory DoS.**
  `PacketAggregationPacket.handle` (`aggregation/PacketAggregationPacket.java:152`) reads `size` and passes it straight to `Context.decompress` → `ByteBuffer.allocateDirect(size)` (`zstd/Context.java:43`) with zero validation. The serverbound aggregate handler is registered (`network/ModNetworking.java:44`), so a modified client can claim a 2GB size per packet. Shared with upstream (not a port regression), but real.
  Fix: reject non-positive values and clamp to `maxPacketSize` (do together with the item above).

## Medium

- [x] **Restrict `ChunkRequestPayload` handler (own feature, security).**
  `network/ModNetworking.java:68-89` resends any loaded chunk to any requester — no `player.getChunkTrackingView().contains(pos)` check, no rate limit. A modified client can harvest arbitrary loaded chunks (world download, other players' surroundings) and amplify bandwidth.
  Fix: validate against the tracking view and add rate limiting.

- [x] **Port upstream `1764660` — DCC ticket anchor (both halves of the commit are missing, verified 2026-07-21).**
  `chunk/CachedChunkTrackingView.java:109` puts a ticket at *each* departed chunk; upstream changed it to `context.putTicket(player.chunkPosition(), ...)` (upstream `CachedChunkTrackingView.java:164`), which `TicketStorage` dedupes to effectively one refreshed radius-1 ticket around the player. Ours accumulates up to `dccSizeLimit` (60) tickets per player, each pinning a 3×3 area for 60s — server load regression. Do this together with the ticket-flags item below; they are the two halves of the same upstream commit.

- [x] **Restore ticket flags (other half of `1764660`).**
  `mixin/ChunkMapMixin.java:79` uses only `TicketType.FLAG_LOADING`; upstream uses `FLAG_LOADING | FLAG_SIMULATION | FLAG_CAN_EXPIRE_IF_UNLOADED` (upstream `ChunkMapMixin.java:52`). Missing `FLAG_CAN_EXPIRE_IF_UNLOADED` means DCC tickets cannot be dropped early when the chunk is unloaded by other means, compounding the ticket-anchor item above; missing `FLAG_SIMULATION` means DCC-held chunks do not count as simulation chunks the way upstream's do.

- [x] **Port upstream `36f6fe5` replay-compat opt-out.**
  `zstd/Context.java:37` always compresses with the streaming context (`EndDirective.FLUSH`) — every frame depends on the per-connection stream, so replay/recording mods cannot decode individual frames. Upstream added `Context(boolean useContext)` + `playersDoNotUseContext` config. WARNING: upstream's check looks inverted (`use = playersDoNotUseContext.contains(uuid)` — a listed player *keeps* the context); port the intent (`!contains`), not the bug.

- [x] **Fix dictionary handoff race (own feature).**
  Server `Context` is created lazily with `DictionaryManager.getDict()` *at first compress* (`zstd/ZstdHelper.java:47`), while the client got the dict snapshot at JOIN (`network/IndexSyncHandler.java:52-55`). If `trainAsync` completes between the two, the server compresses with the new dict against a client holding none — the stateful stream corrupts permanently for that connection. Training completion also never broadcasts the new dict to online players.
  Fix: pin the dict per connection at JOIN (same snapshot for payload and Context), or evict + resync all connections when training completes.

- [x] **Stop dictionary sampling on physical clients (own feature).**
  `DictionaryManager.loadFromDisk()` sets `serverSide = true` (`zstd/DictionaryManager.java:38`) and is called unconditionally from the common entrypoint (`NotEnoughBandwidth.java:30`). A client on a dict-less NEB server samples its own serverbound blobs (mostly movement packets), buffers up to 32MB, trains a junk dictionary, and persists it to `config/neb_trained_dict.bin` — later served if that user hosts a LAN world.
  Fix: gate `loadFromDisk`/sampling to dedicated-server or integrated-server-start paths.

- [x] **Evict zstd contexts on server disconnect.**
  Server DISCONNECT handler (`network/IndexSyncHandler.java:46-50`) never calls `ZstdHelper.evict(connection)`; with guava `weakKeys` the native context (~8MB at windowLog 23, plus dict copies) lingers until GC + lazy cache maintenance. Upstream sweeps `removeIf(!isConnected)` on every `get()` (upstream `zstd/ZstdHelper.java:50`).

- [x] **Serialize config file writes (upstream fix #11).**
  `config/ConfigHelper.java:77-87` still writes via `CompletableFuture.runAsync` on the common pool — two in-flight saves can interleave writes to the same file. Upstream moved to a dedicated single-thread daemon executor. Our caller-thread JSON snapshot already covers the other half of that fix; keep it.

## Low

- [x] **Update `VANILLA_PATHS` for 26.1 — and make it sync-safe.**
  `indextype/NamespaceIndexManager.java:37-100` still holds the 1.21.x list (verified against the 26.1 jar): `horse_screen_open` and `debug_sample_subscription` no longer exist; missing `mount_screen_open`, `debug_subscription_request`, `low_disk_space_warning`, `game_test_highlight_pos`, and the four `debug/*` ids. Bandwidth-only today (unindexed types fall back to `0x00` + full identifier), BUT the list is hardcoded on both ends — changing it is a wire-protocol change between our own builds. When fixing, move the list (or a hash of it) into `IndexSyncPayload` so mismatched builds stay consistent.

- [x] **Release `data` on malformed aggregate input.**
  `aggregation/PacketAggregationPacket.java:149-155`: if `readBoolean()`/`readVarInt()`/decompress throws, the retained duplicate in `this.data` leaks — the try/finally only covers the parse loop. Move the decompress block inside it.

- [x] **Count wrapper-header bytes as inbound raw stat (parity with upstream).**
  On decode, upstream records `inRaw(bakedSize - data.readableBytes())` for the aggregation wrapper's own header bytes in addition to the decompressed payload size; we only record the decompressed size (`aggregation/PacketAggregationPacket.java:236`), so inbound raw stats undercount by the header bytes. Stats-only, no wire effect.

- [x] **Blacklist `minecraft:start_configuration` (shared with upstream).**
  A mid-game reconfiguration gets the terminal packet aggregated, so the vanilla protocol-swap handshake never fires on the wrapper; `flushInternal` additionally drops the buffer (`packets.clear()`) once the protocol leaves PLAY (`aggregation/AggregationManager.java:117-123`). Rare, but cheap to fix via `COMMON_BLOCK_LIST`.

## Do NOT regress (deliberate improvements over upstream)

When porting upstream fixes, keep these port-specific behaviors — they fix real upstream bugs:

- `flushConnectionSync` flushes on the calling thread before skip-type packets (`mixin/ConnectionMixin.java:60-63`); upstream's async timer flush has an ordering race despite its "ensure packet order" comment.
- Callback-bearing packets bypass aggregation, and de-bundling attaches callbacks only to the last sub-packet; upstream drops or multiplies `ChannelFutureListener`s.
- `bakedSize` is computed before `writeBytes`/`release` (upstream reads a freed buffer, always 0), and `ZstdHelper.decompress` releases its input in a finally (upstream leaks every compressed inbound packet).
- `fillSingle` resolves the namespace index via `NAMESPACE_MAP` (`indextype/NamespaceIndexManager.java:146`); upstream's `namespaceIndex - 1` corrupts the table for `minecraft`-namespace custom payloads.
- Upstream `8046e30` (prefix wire-format redesign) is intentionally NOT ported: our prefix is only used inside the aggregate blob, standalone payloads keep vanilla encoding, so external parsers (proxies, replays) are unaffected by design. Verified 2026-07-21: the redesign's `VALID_PACKETS` reflection oracle exists only to make the markerless format decodable in the standalone-header position; nothing in it is needed for our blob-only prefix. Same reason upstream's `CustomPacketPayloadMixin` (prefix on every standalone PLAY payload) is not ported — a codec-level rewrite cannot be gated per connection and would break our vanilla-client passthrough.
- `chat`/`chat_command`/`chat_command_signed` are permanently in `COMMON_BLOCK_LIST` (moved there in `1db1701` with the proxy handling); upstream compresses chat commands by default and only skips them in compatible mode. Keep ours — signed-chat packets must not depend on per-connection stream state across proxy backend switches.
- Per-connection buffer locking instead of upstream's global `synchronized`.
- Decode-side error containment: per-sub-packet try/catch and try/finally slice release in `PacketAggregationPacket`; upstream leaks retained slices and lets one malformed sub-packet abort the whole blob. Encode-side: we degrade only the offending sub-packet; upstream's `DontDecorateException` rethrow drops the entire aggregate.
