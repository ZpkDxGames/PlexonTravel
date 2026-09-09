# Architecture

PlexonTravel is a focused Core-native travel module. It owns spawn, hub, back, warps and the shared teleport runtime; it does not own homes, economy, ranks, moderation or general utilities.

`PlexonTravel` loads the PlexonCore 2.0 service, registers module id `travel`, then loads immutable destination/warp snapshots from SQLite. One `TravelService` owns every pending teleport. A single shared scheduler updates all warmups. Player movement routing is a single O(1) pending-map gate because PlexonCore 2.0.0 does not currently expose a player movement/damage watch API; no second listener engine is created per feature or per player.

The safe resolver asynchronously prepares unloaded chunks using Paper `getChunkAtAsync`, returns to the primary thread, performs a bounded nearby search, and repeats validation immediately before teleport. Database writes are serialized off-thread. Cooldowns use `System.nanoTime()` and are not persisted.
