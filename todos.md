# TODOs — upstream comparison audit (2026-07-15)

Source-level comparison against upstream [USS-Shenzhou/NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth) `master` (MC 26.1).
Our last feature sync was 2026-04-26 (`be1881c`); upstream landed fixes after that point.
Findings verified against both codebases; chunk items additionally verified against the vanilla 26.1 jar and upstream git history. No build/runtime verification.

## High

- [ ] **Port upstream `39b076c` "fix packet size limit" — aggregates can exceed the 2MB frame limit and kick players.**
  `AggregationManager.flushInternal` (`aggregation/AggregationManager.java:112-144`) packs one flush window (20ms, up to 3 waited cycles) into a single aggregate with no size cap or splitting. We have no `Varint21FrameDecoderMixin` / `Varint21LengthFieldPrependerMixin` and no `maxPacketSize` config. Vanilla frame length is a 3-byte VarInt (max 2,097,151 bytes); an oversized frame makes `Varint21LengthFieldPrepender` throw `EncoderException` → player kicked with "Internal Exception". Chunk bursts on join/teleport at high view distance can trigger this. Upstream hit it in production; NeoForge at least had `GenericPacketSplitter` as a safety net — Fabric has nothing, so we are more exposed than pre-fix upstream.
  Fix: port both Varint21 mixins + `maxPacketSize` parsing (2–64MB clamp) from upstream `NotEnoughBandwidthConfig`.

- [ ] **Clamp the decompressed-size VarInt — remote direct-memory DoS.**
  `PacketAggregationPacket.handle` (`aggregation/PacketAggregationPacket.java:152`) reads `size` and passes it straight to `Context.decompress` → `ByteBuffer.allocateDirect(size)` (`zstd/Context.java:43`) with zero validation. The serverbound aggregate handler is registered (`network/ModNetworking.java:44`), so a modified client can claim a 2GB size per packet. Shared with upstream (not a port regression), but real.
  Fix: reject non-positive values and clamp to `maxPacketSize` (do together with the item above).

## Medium

- [ ] **Restrict `ChunkRequestPayload` handler (own feature, security).**
  `network/ModNetworking.java:68-89` resends any loaded chunk to any requester — no `player.getChunkTrackingView().contains(pos)` check, no rate limit. A modified client can harvest arbitrary loaded chunks (world download, other players' surroundings) and amplify bandwidth.
  Fix: validate against the tracking view and add rate limiting.

- [ ] **Port the missing half of upstream `1764660` — DCC ticket anchor.**
  `chunk/CachedChunkTrackingView.java:100` puts a ticket at *each* cached chunk; upstream changed it to `context.putTicket(player.chunkPosition(), ...)`, which `TicketStorage` dedupes to effectively one ticket. Ours accumulates up to `dccSizeLimit` (60) tickets per player, each pinning a 3×3 area for 60s — server load regression. (The other half of that commit, caching the `TicketType` instance, was already ported.)

- [ ] **Restore ticket flags.**
  `mixin/ChunkMapMixin.java:61` uses only `TicketType.FLAG_LOADING`; upstream uses `FLAG_LOADING | FLAG_SIMULATION | FLAG_CAN_EXPIRE_IF_UNLOADED`. Missing `FLAG_CAN_EXPIRE_IF_UNLOADED` means the timeout does not count down under save pressure, compounding the ticket-anchor item above.

- [ ] **Port upstream `36f6fe5` replay-compat opt-out.**
  `zstd/Context.java:37` always compresses with the streaming context (`EndDirective.FLUSH`) — every frame depends on the per-connection stream, so replay/recording mods cannot decode individual frames. Upstream added `Context(boolean useContext)` + `playersDoNotUseContext` config. WARNING: upstream's check looks inverted (`use = playersDoNotUseContext.contains(uuid)` — a listed player *keeps* the context); port the intent (`!contains`), not the bug.

- [ ] **Fix dictionary handoff race (own feature).**
  Server `Context` is created lazily with `DictionaryManager.getDict()` *at first compress* (`zstd/ZstdHelper.java:47`), while the client got the dict snapshot at JOIN (`network/IndexSyncHandler.java:52-55`). If `trainAsync` completes between the two, the server compresses with the new dict against a client holding none — the stateful stream corrupts permanently for that connection. Training completion also never broadcasts the new dict to online players.
  Fix: pin the dict per connection at JOIN (same snapshot for payload and Context), or evict + resync all connections when training completes.

- [ ] **Stop dictionary sampling on physical clients (own feature).**
  `DictionaryManager.loadFromDisk()` sets `serverSide = true` (`zstd/DictionaryManager.java:38`) and is called unconditionally from the common entrypoint (`NotEnoughBandwidth.java:30`). A client on a dict-less NEB server samples its own serverbound blobs (mostly movement packets), buffers up to 32MB, trains a junk dictionary, and persists it to `config/neb_trained_dict.bin` — later served if that user hosts a LAN world.
  Fix: gate `loadFromDisk`/sampling to dedicated-server or integrated-server-start paths.

- [ ] **Evict zstd contexts on server disconnect.**
  Server DISCONNECT handler (`network/IndexSyncHandler.java:46-50`) never calls `ZstdHelper.evict(connection)`; with guava `weakKeys` the native context (~8MB at windowLog 23, plus dict copies) lingers until GC + lazy cache maintenance. Upstream sweeps `removeIf(!isConnected)` on every `get()` (upstream `zstd/ZstdHelper.java:50`).

- [ ] **Serialize config file writes (upstream fix #11).**
  `config/ConfigHelper.java:77-87` still writes via `CompletableFuture.runAsync` on the common pool — two in-flight saves can interleave writes to the same file. Upstream moved to a dedicated single-thread daemon executor. Our caller-thread JSON snapshot already covers the other half of that fix; keep it.

## Low

- [ ] **Update `VANILLA_PATHS` for 26.1 — and make it sync-safe.**
  `indextype/NamespaceIndexManager.java:37-100` still holds the 1.21.x list (verified against the 26.1 jar): `horse_screen_open` and `debug_sample_subscription` no longer exist; missing `mount_screen_open`, `debug_subscription_request`, `low_disk_space_warning`, `game_test_highlight_pos`, and the four `debug/*` ids. Bandwidth-only today (unindexed types fall back to `0x00` + full identifier), BUT the list is hardcoded on both ends — changing it is a wire-protocol change between our own builds. When fixing, move the list (or a hash of it) into `IndexSyncPayload` so mismatched builds stay consistent.

- [ ] **Release `data` on malformed aggregate input.**
  `aggregation/PacketAggregationPacket.java:149-155`: if `readBoolean()`/`readVarInt()`/decompress throws, the retained duplicate in `this.data` leaks — the try/finally only covers the parse loop. Move the decompress block inside it.

- [ ] **Blacklist `minecraft:start_configuration` (shared with upstream).**
  A mid-game reconfiguration gets the terminal packet aggregated, so the vanilla protocol-swap handshake never fires on the wrapper; `flushInternal` additionally drops the buffer (`packets.clear()`) once the protocol leaves PLAY (`aggregation/AggregationManager.java:117-123`). Rare, but cheap to fix via `COMMON_BLOCK_LIST`.

## Do NOT regress (deliberate improvements over upstream)

When porting upstream fixes, keep these port-specific behaviors — they fix real upstream bugs:

- `flushConnectionSync` flushes on the calling thread before skip-type packets (`mixin/ConnectionMixin.java:60-63`); upstream's async timer flush has an ordering race despite its "ensure packet order" comment.
- Callback-bearing packets bypass aggregation, and de-bundling attaches callbacks only to the last sub-packet; upstream drops or multiplies `ChannelFutureListener`s.
- `bakedSize` is computed before `writeBytes`/`release` (upstream reads a freed buffer, always 0), and `ZstdHelper.decompress` releases its input in a finally (upstream leaks every compressed inbound packet).
- `fillSingle` resolves the namespace index via `NAMESPACE_MAP` (`indextype/NamespaceIndexManager.java:146`); upstream's `namespaceIndex - 1` corrupts the table for `minecraft`-namespace custom payloads.
- Upstream `8046e30` (prefix wire-format redesign) is intentionally NOT ported: our prefix is only used inside the aggregate blob, standalone payloads keep vanilla encoding, so external parsers (proxies, replays) are unaffected by design.
- Per-connection buffer locking instead of upstream's global `synchronized`.
