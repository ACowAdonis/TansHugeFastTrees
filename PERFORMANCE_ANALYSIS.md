# Tan's Huge Trees - Performance Analysis & Optimization Options

This document captures the full analysis of Tan's world generation algorithm, existing optimizations, and potential improvements.

**Branch:** `1.20.1-2025.2-sisterpc-perf-review`
**Date:** 2025-12-21

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Existing Optimizations (Already Implemented)](#2-existing-optimizations-already-implemented)
3. [Remaining Bottlenecks](#3-remaining-bottlenecks)
4. [Marker Entity System Analysis](#4-marker-entity-system-analysis)
5. [Region Data & Caching Analysis](#5-region-data--caching-analysis)
6. [Tree-to-Tree Comparison Algorithm](#6-tree-to-tree-comparison-algorithm)
7. [Multi-threading Analysis](#7-multi-threading-analysis)
8. [Living Tree Mechanics Integration](#8-living-tree-mechanics-integration)
9. [Optimization Options Summary](#9-optimization-options-summary)

---

## 1. Architecture Overview

### Key Files

| File | Purpose |
|------|---------|
| `TreeLocation.java` | Region scanning, tree position selection, distance checking |
| `TreePlacer.java` | Actual block placement, detailed detection, living tree hooks |
| `Loop.java` | Tick-based processing for living tree mechanics |
| `Cache.java` | Caching for config files, tree shapes, dictionaries |

### World Generation Flow

```
Chunk Generation Triggered
         │
         ▼
WorldGenStepBeforePlants.start()
         │
         ▼
┌────────────────────────────────────┐
│  synchronized (lock)               │  ◄── GLOBAL LOCK - blocks ALL chunks
│    TreeLocation.start()            │
│      └─ run() × 4 diagonal chunks  │
│           └─ If new region:        │
│                • Pre-load 9 neighbors
│                • Scan 32×32 chunks │
│                • Write tree_locations.bin
│                • Write place.bin   │
└────────────────────────────────────┘
         │
         ▼
TreePlacer.start()
  └─ Read place.bin for this region
  └─ For each tree in range:
       • Detailed detection (structure, ground, height)
       • Place blocks chunk-by-chunk
       • Spawn marker entity (if living tree enabled)
```

### Data Storage Structure

```
{world}/tanshugetrees/world_gen/
├── #regions/{dimension}/{regionX},{regionZ}.bin    # Region scan marker (empty file)
├── tree_locations/{dimension}/{regionX},{regionZ}.bin  # Tree positions
├── place/{dimension}/{regionX},{regionZ}.bin      # Tree placement data
└── detailed_detection/{dimension}/{regionX},{regionZ}.bin  # Placement test cache
```

---

## 2. Existing Optimizations (Already Implemented)

These optimizations are already present in the `1.20.1-2025.2-sisterpc` branch:

### 2.1 SpatialHashGrid for O(1) Proximity Checks
**Location:** `TreeLocation.java:50-103`

```java
private static class SpatialHashGrid {
    private static final int CELL_SIZE = 256;  // blocks
    // 9 regions → 18×18 = 324 cells
    // Average 8.5 trees per cell
}
```

- **Before:** O(n) linear scan of ~2,763 trees per query
- **After:** O(1) check of ~77 trees (9 cells × ~8.5 trees)
- **Improvement:** ~35× faster proximity checks

### 2.2 Region File Caching
**Location:** `TreeLocation.java:138-171`

- Pre-loads 9 neighboring region files at start of region scan
- Populates spatial grid once, reuses for all trees in region
- Eliminates repeated disk reads during scanning

### 2.3 Biome Test Caching
**Location:** `TreeLocation.java:27, 400-419`

```java
private static final Map<String, Boolean> cache_biome_test = new HashMap<>();
// Key: biomeId + "|" + speciesId
```

- Caches biome compatibility results
- Avoids repeated tag/condition matching

### 2.4 ThreadLocal Batch Writes for detailed_detection
**Location:** `TreePlacer.java:37-38, 460-482`

```java
private static final ThreadLocal<Map<String, List<String>>> pending_detailed_detection =
    ThreadLocal.withInitial(HashMap::new);
```

- Accumulates writes in memory during chunk processing
- Flushes once at end of chunk (batches 50+ writes into ~9)
- Thread-safe for C2ME parallel chunk generation

### 2.5 Infinite Overlay Loop Fix
**Location:** `TreeLocation.java:232-252`

- Previously: Loop continued forever even after animation complete
- Fixed: Check animation state before scheduling next iteration

### 2.6 Biome Test Moved After Distance Test
**Commit:** `15e7848`

- Distance check is cheaper than biome check
- Fail fast on the cheaper check

---

## 3. Remaining Bottlenecks

### 3.1 Global Synchronized Lock
**Location:** `TreeLocation.java:107`

```java
synchronized (lock) {
    TreeLocation.run(level_accessor, dimension, new ChunkPos(...));
    // ... 4 calls total
}
```

**Impact:**
- Serializes ALL chunk generation through TreeLocation
- Multiplayer: If Player A triggers region scan, Players B,C,D wait in queue
- C2ME cannot parallelize chunk generation during this phase

**Priority:** CRITICAL

### 3.2 Config Parsing Per Region
**Location:** `TreeLocation.java:176`

```java
String[] config_world_gen = FileManager.readTXT(file.getPath());
```

**Impact:**
- Reads and parses `config_world_gen.txt` for EACH new region
- File I/O + string parsing overhead

**Priority:** HIGH

### 3.3 File.listFiles() Per Tree
**Location:** `TreeLocation.java:641-648`

```java
File chosen = new File(..."/storage");
File[] list = chosen.listFiles();
chosen = new File(chosen.getPath() + "/" + list[random.nextInt(list.length)].getName());
```

**Impact:**
- Disk I/O for every tree placement decision
- OS directory listing overhead

**Priority:** HIGH

### 3.4 Chunk Existence Check Loop
**Location:** `TreeLocation.java:920-942`

```java
for (int scanX = scan_fromX; scanX <= scan_toX; scanX++) {
    for (int scanZ = scan_fromZ; scanZ <= scan_toZ; scanZ++) {
        if (level_accessor.getChunk(scanX, scanZ, ChunkStatus.FEATURES, false) != null) {
            return;  // Abort if any chunk already generated
        }
    }
}
```

**Impact:**
- Potentially (to_chunk - from_chunk + 8)² getChunk() calls per tree
- For large trees spanning 10 chunks: could be 18² = 324 calls

**Priority:** MEDIUM

### 3.5 detailed_detection File Read Per Tree
**Location:** `TreePlacer.java:99`

```java
ByteBuffer detailed_detection = FileManager.readBIN(
    Handcode.path_world_data + "/world_gen/detailed_detection/" + dimension + "/" +
    (chunk_pos.x >> 5) + "," + (chunk_pos.z >> 5) + ".bin");
```

**Impact:**
- Reads same file repeatedly for each tree in region
- Should cache at start of chunk processing

**Priority:** MEDIUM

### 3.6 Structure Detection Loop
**Location:** `TreePlacer.java:214-253`

```java
for (int scanX = -radius; scanX <= radius; scanX++) {
    for (int scanZ = -radius; scanZ <= radius; scanZ++) {
        ChunkAccess chunk = level_accessor.getChunk(..., ChunkStatus.STRUCTURE_REFERENCES, false);
        // Check structure references
    }
}
```

**Impact:**
- For radius=2: 25 getChunk() calls per tree
- Chunk access can be expensive

**Priority:** LOW-MEDIUM

---

## 4. Marker Entity System Analysis

### Current Implementation
**Location:** `TreePlacer.java:1128-1146`

```java
if (ConfigMain.tree_location == true && dead_tree_level == 0) {
    if (can_leaves_decay == true || can_leaves_drop == true || can_leaves_regrow == true) {
        String marker_data = "ForgeData:{file:\"...\",tree_settings:\"...\",rotation:...,mirrored:...}";
        Utils.command.run(level_server, x, y, z,
            Utils.command.summonEntity("marker", "TANSHUGETREES / TANSHUGETREES-tree_location", id, marker_data));
    }
}
```

### Efficiency Problems

1. **Command parsing overhead** - Full command string must be parsed each time
2. **Entity creation cost** - Marker entity + NBT data allocation
3. **Selector scanning** - Every second, `@e[tag=TANSHUGETREES-tree_location]` scans ALL entities

### What Markers Are Used For
**Location:** `Loop.java:95-120`

1. **Living tree mechanics simulation** - Random tree selection for leaf processing
2. **Tree counting** - Scoreboard tracking via entity selector
3. **NOT used for placement logic** - That uses `.bin` files

### Loop.java Tick Costs

```java
// Every second (Loop.java:138-181):
loop_tree_location = Utils.command.result(level_server, 0, 0, 0,
    "execute if entity @e[tag=TANSHUGETREES-tree_location]");  // Scans ALL entities

// Every tick when living tree mechanics enabled (Loop.java:100-117):
if (living_tree_mechanics_tick >= ConfigMain.living_tree_mechanics_tick) {
    Utils.command.run(level_server, 0, 0, 0,
        "execute as @e[tag=TANSHUGETREES-tree_location,limit=1,sort=random] at @s run ...");
}
```

### Alternative Tracking Options

| Option | Description | Speed | Memory | Persistence |
|--------|-------------|-------|--------|-------------|
| **In-memory registry** | `Map<ChunkPos, List<TreeData>>` | Very fast | Scales with trees | Manual save |
| **Chunk capabilities** | Forge capability on chunks | Fast | Minimal | Auto-saves |
| **Existing .bin files** | Use `tree_locations/*.bin` directly | Medium | Disk-based | Already exists |
| **Block entity** | Invisible block at tree base | Medium | Per-tree | Auto-saves |
| **Custom data attachment** | NeoForge data attachments | Fast | Minimal | Auto-saves |

### Recommendation
If living tree mechanics are disabled/optional, marker spawning can be skipped entirely via config check. For active use, consider replacing with chunk-based registry.

---

## 5. Region Data & Caching Analysis

### Tree Location File Format
**Per-tree entry:**
```
2 bytes: species ID (dictionary index via Cache.getDictionary)
4 bytes: posX (int)
4 bytes: posZ (int)
────────
10 bytes per tree
```

### Typical Sizes

| Metric | Value |
|--------|-------|
| Region size | 512×512 blocks (32×32 chunks) |
| Trees per region | ~100-500 (varies by biome) |
| File size per region | 1-5 KB |
| 9-region cache in memory | ~300 KB |

### Memory Breakdown for 9-Region Cache
```
Raw tree data:     9 regions × 500 trees × 10 bytes = 45 KB
Java objects:      4,500 × RegionTreeData object overhead = ~200 KB
Spatial grid:      HashMap + ArrayList overhead = ~50 KB
─────────────────────────────────────────────────────────────
Total:             ~300 KB per new region encounter
```

### Multiplayer Implications

1. **Global lock blocks all players** during region scans
2. **Serial processing**: 5 players → 5 new regions → 5× wait time
3. **No region sharing**: Each player's approach may trigger independent scans
4. **Memory not shared**: Each scan rebuilds cache from scratch

### Chunky/Pregen Implications

- Chunky triggers many chunks rapidly
- Each chunk hits the global lock
- Region scans queue up, causing apparent "freezes"
- Progress appears to stop during region transitions

---

## 6. Tree-to-Tree Comparison Algorithm

### Original Algorithm (Before Optimization)
```java
// O(n) - Check ALL trees in ALL 9 neighboring regions
for each region in 9 neighbors:
    ByteBuffer buffer = FileManager.readBIN(region_file)
    while buffer.hasRemaining():
        read tree entry
        if same position OR (same species AND within minDistance):
            reject
```

**Complexity:** O(n) where n = total trees in 9 regions (~2,763 typical)

### Optimized Algorithm (Current - SpatialHashGrid)
```java
// O(1) average - Check only nearby cells
int cellX = Math.floorDiv(centerX, 256);
int cellZ = Math.floorDiv(centerZ, 256);

for (int dx = -1; dx <= 1; dx++) {
    for (int dz = -1; dz <= 1; dz++) {
        List<RegionTreeData> trees = grid.get(cellKey(cellX + dx, cellZ + dz));
        for (RegionTreeData tree : trees) {  // ~8.5 trees per cell average
            // Position collision check
            // Same-species distance check
        }
    }
}
```

**Complexity:** O(1) average, checking ~77 trees (9 cells × 8.5 trees)

### Comparison Logic Details

```java
// 1. Exact position collision (any species)
if (centerX == tree.posX && centerZ == tree.posZ) {
    return false;  // Reject - same spot
}

// 2. Same species minimum distance (box check, not circular)
if (id.equals(tree.id)) {
    if (Math.abs(centerX - tree.posX) <= minDistance &&
        Math.abs(centerZ - tree.posZ) <= minDistance) {
        return false;  // Reject - too close to same species
    }
}
```

**Note:** Uses box distance (Chebyshev), not circular (Euclidean). This is faster but allows diagonal placement at sqrt(2) × minDistance.

### Further Optimization Options

| Option | Benefit | Tradeoff |
|--------|---------|----------|
| **Species-specific grids** | Only check same species (~1/20th trees) | More memory, more code complexity |
| **Smaller cell size (64)** | ~2 trees per cell instead of 8.5 | More cells to check (25 vs 9) |
| **Larger cell size (512)** | Fewer cells (1-4) | More trees per cell (~34) |
| **Circular distance** | More accurate spacing | sqrt() computation per comparison |
| **Skip cross-species** | Only check same species | Allows different species overlap |
| **Bloom filter pre-check** | Fast reject for exact collisions | Extra data structure |

---

## 7. Multi-threading Analysis

### Current Threading Model

```java
// TreeLocation.java:105-116
public static void start(LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos) {
    synchronized (lock) {  // ◄── SINGLE GLOBAL LOCK
        TreeLocation.run(..., new ChunkPos(chunk_pos.x + 4, chunk_pos.z + 4));
        TreeLocation.run(..., new ChunkPos(chunk_pos.x + 4, chunk_pos.z - 4));
        TreeLocation.run(..., new ChunkPos(chunk_pos.x - 4, chunk_pos.z + 4));
        TreeLocation.run(..., new ChunkPos(chunk_pos.x - 4, chunk_pos.z - 4));
    }
}
```

### Shared State That Requires Synchronization

```java
// All static, all shared across all threads:
private static final Map<String, List<String>> cache_write_tree_location = new HashMap<>();
private static final Map<String, List<String>> cache_write_place = new HashMap<>();
private static final Map<String, List<String>> cache_dead_tree_auto_level = new HashMap<>();
private static final Map<String, Boolean> cache_biome_test = new HashMap<>();
private static final Map<String, List<RegionTreeData>> cache_region_files = new HashMap<>();
private static final SpatialHashGrid spatialGrid = new SpatialHashGrid();
```

### Why Parallel is Difficult

1. **Spatial grid is shared** - Two threads adding trees to same grid = race condition
2. **Write caches are shared** - Same region's tree list modified by multiple threads
3. **File I/O conflicts** - Multiple threads reading/writing same region files
4. **Tree placement order matters** - "First come first served" for conflicts

### Options for Parallelization

#### Option A: Per-Region Locks
```java
private static final Map<String, Object> regionLocks = new ConcurrentHashMap<>();

public static void start(...) {
    String regionKey = getRegionKey(chunk_pos);
    Object regionLock = regionLocks.computeIfAbsent(regionKey, k -> new Object());
    synchronized (regionLock) {
        // Only blocks other threads working on SAME region
    }
}
```
**Benefit:** Different regions can be parallel
**Risk:** Low - regions are independent
**Complexity:** Medium

#### Option B: ThreadLocal Caches with Merge
```java
private static final ThreadLocal<Map<String, List<String>>> threadCache =
    ThreadLocal.withInitial(HashMap::new);

// Each thread builds its own cache
// At end, merge all thread caches (needs coordination)
```
**Benefit:** Full parallelism during scan
**Risk:** Medium - merge logic complex
**Complexity:** High

#### Option C: Lock-Free with ConcurrentHashMap
```java
private static final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> cache =
    new ConcurrentHashMap<>();
```
**Benefit:** Fine-grained concurrency
**Risk:** Medium - CopyOnWriteArrayList slow for writes
**Complexity:** Medium

#### Option D: Background Pre-generation
```java
// Separate thread pool for region scanning
ExecutorService regionScanner = Executors.newFixedThreadPool(4);

// When player approaches new region boundary:
regionScanner.submit(() -> {
    // Pre-scan regions player is moving toward
    // Results ready before player arrives
});
```
**Benefit:** Hides latency from players
**Risk:** Low - doesn't change core algorithm
**Complexity:** Medium

#### Option E: Early-Out Before Lock
```java
public static void start(...) {
    // Check WITHOUT lock first
    File regionFile = new File(regionPath);
    if (regionFile.exists()) {
        return;  // Already scanned, skip entirely - no lock needed
    }

    synchronized (lock) {
        // Double-check inside lock (another thread may have just finished)
        if (regionFile.exists()) {
            return;
        }
        // Proceed with scan
    }
}
```
**Benefit:** Existing regions skip lock entirely
**Risk:** Very low
**Complexity:** Low

### Recommended Approach

1. **Immediate:** Implement Option E (early-out) - low risk, quick win
2. **Short-term:** Implement Option A (per-region locks) - moderate benefit
3. **Long-term:** Consider Option D (background pre-gen) for best UX

---

## 8. Living Tree Mechanics Integration

### Integration Points in TreePlacer

| Location | Feature | Condition |
|----------|---------|-----------|
| Lines 529-531 | Parse `can_leaves_decay/drop/regrow` | Always (if tree settings exist) |
| Lines 552-563 | Store flags from tree settings | Always |
| Lines 1050-1087 | `LeafLitter.start()` during placement | `ConfigMain.leaf_litter && leaf_litter_world_gen && can_leaves_drop` |
| Lines 1090-1107 | Abscission (skip leaves in snowy biome) | `ConfigMain.abscission_world_gen && in_snowy_biome` |
| Lines 1128-1146 | Marker entity spawn | `ConfigMain.tree_location && dead_tree_level == 0 && (decay OR drop OR regrow)` |

### Config Flags That Control Living Tree Features

```java
// ConfigMain.java
public static boolean tree_location = false;           // Master switch for markers
public static boolean living_tree_mechanics = false;   // Master switch for living trees
public static boolean leaf_litter = false;             // Leaf litter system
public static boolean leaf_litter_world_gen = false;   // Leaf litter during world gen
public static boolean abscission_world_gen = false;    // Seasonal leaf dropping
```

### If Living Tree Mechanics Disabled

When `living_tree_mechanics = false` and `tree_location = false`:

1. **No marker entities spawned** - Skip lines 1128-1146
2. **No LeafLitter during worldgen** - Skip lines 1050-1087 (if `leaf_litter_world_gen = false`)
3. **No abscission** - Skip lines 1090-1107 (if `abscission_world_gen = false`)
4. **Loop.java overhead eliminated** - No entity selector scans

### Simplification Options

| Option | Saves | Impact |
|--------|-------|--------|
| Disable `tree_location` | Marker spawn + Loop.java scans | No living tree tracking |
| Disable `leaf_litter_world_gen` | LeafLitter.start() calls | No pre-placed leaf litter |
| Disable `abscission_world_gen` | Biome checks + leaf skipping | All leaves placed regardless of season |
| Skip parsing `can_leaves_*` | String parsing | Need code change |

---

## 9. Optimization Options Summary

### Priority Matrix

| ID | Optimization | Priority | Complexity | Risk | Impact |
|----|--------------|----------|------------|------|--------|
| **O1** | Early-out before lock | CRITICAL | Low | Very Low | High |
| **O2** | Per-region locks | HIGH | Medium | Low | High |
| **O3** | Cache config at startup | HIGH | Low | Very Low | Medium |
| **O4** | Cache shape file list | HIGH | Low | Very Low | Medium |
| **O5** | Cache detailed_detection per chunk | MEDIUM | Low | Very Low | Medium |
| **O6** | Background region pre-generation | MEDIUM | Medium | Low | High (UX) |
| **O7** | Replace marker entities | MEDIUM | High | Medium | Medium |
| **O8** | Species-specific spatial grids | LOW | Medium | Low | Low |
| **O9** | Skip chunk existence checks | LOW | Low | Medium | Low |
| **O10** | Disable living tree during worldgen | LOW | Very Low | Very Low | Variable |

### Quick Wins (Can Implement Immediately)

1. **O1: Early-out before lock**
   - Check `file_region.exists()` BEFORE acquiring lock
   - Existing regions skip lock entirely

2. **O3: Cache config at startup**
   - Parse `config_world_gen.txt` once during mod init
   - Store in static `List<SpeciesConfig>`

3. **O4: Cache shape file list**
   - Index available shapes per species at startup
   - Replace `File.listFiles()` with cached array

4. **O10: Disable living tree worldgen features**
   - Set `tree_location = false` in config
   - Eliminates marker spawning entirely

### Medium-Term Improvements

5. **O2: Per-region locks**
   - Replace global lock with per-region lock map
   - Allows parallel scanning of different regions

6. **O5: Cache detailed_detection reads**
   - Read once per chunk, not per tree
   - Store in local variable during TreePlacer.start()

7. **O6: Background pre-generation**
   - Detect player movement direction
   - Pre-scan approaching regions in background thread

### Long-Term Architectural Changes

8. **O7: Replace marker entities**
   - Use chunk-based data storage
   - Eliminate entity selector overhead

9. **O8: Species-specific spatial grids**
   - Separate grid per species
   - Only check same-species conflicts

---

## Appendix: File Locations Reference

```
TreeLocation.java:
  - Global lock: line 107
  - SpatialHashGrid: lines 50-103
  - Region caching: lines 138-171
  - Config parsing: line 176
  - Biome caching: lines 400-419
  - File.listFiles(): lines 641-648
  - Chunk existence check: lines 920-942

TreePlacer.java:
  - ThreadLocal buffer: lines 37-38
  - detailed_detection read: line 99
  - Structure detection loop: lines 214-253
  - LeafLitter integration: lines 1050-1087
  - Abscission: lines 1090-1107
  - Marker spawn: lines 1128-1146
  - Flush buffer: lines 460-482

Loop.java:
  - Entity selector scans: lines 138-181
  - Living tree mechanics tick: lines 100-117
```

---

*Document generated during performance analysis session, December 2025*
