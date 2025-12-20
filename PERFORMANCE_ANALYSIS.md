# Tan's Huge Trees - Performance Analysis & Optimization Plan

This document captures the full analysis of Tan's world generation algorithm, existing optimizations, identified bottlenecks, and a prioritized task list for future improvements.

**Branch:** `1.20.1-2025.2-sisterpc-perf-review`
**Date:** 2025-12-21
**Memory Budget:** 100 MB for caching and optimization
**Constraint:** Must remain compatible with C2ME parallel chunk generation

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Existing Optimizations](#2-existing-optimizations-already-implemented)
3. [Remaining Bottlenecks](#3-remaining-bottlenecks)
4. [Region Scan Phase - Detailed Analysis](#4-region-scan-phase---detailed-analysis)
5. [Marker Entity & Living Tree Analysis](#5-marker-entity--living-tree-analysis)
6. [Region Data & Caching Analysis](#6-region-data--caching-analysis)
7. [Tree-to-Tree Comparison Algorithm](#7-tree-to-tree-comparison-algorithm)
8. [Multi-threading Analysis](#8-multi-threading-analysis)
9. [Data Structure Thread-Safety Analysis](#9-data-structure-thread-safety-analysis)
10. [Two-Phase Architecture Proposal](#10-two-phase-architecture-proposal)
11. [Task List for Future Work](#11-task-list-for-future-work)
12. [Appendix: File Locations Reference](#appendix-file-locations-reference)

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
│                • Scan 32×32 chunks │  ◄── THE BLOCKING PHASE (animation plays)
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

These optimizations are present in the `1.20.1-2025.2-sisterpc` branch:

### 2.1 SpatialHashGrid for O(1) Proximity Checks
**Location:** `TreeLocation.java:50-103`

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

- Caches biome compatibility results
- Uses string key lookup: `biomeId + "|" + speciesId`
- **Issue:** String concatenation on every lookup is inefficient

### 2.4 ThreadLocal Batch Writes for detailed_detection
**Location:** `TreePlacer.java:37-38, 460-482`

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
**Priority:** CRITICAL

- Serializes ALL chunk generation through TreeLocation
- Multiplayer: All players wait in queue
- C2ME cannot parallelize during this phase

### 3.2 Config Parsing Per Region
**Location:** `TreeLocation.java:176`
**Priority:** HIGH

- Reads and parses `config_world_gen.txt` for EACH new region
- File I/O + string parsing overhead
- Should be parsed ONCE at startup

### 3.3 File.listFiles() Per Tree
**Location:** `TreeLocation.java:641-648`
**Priority:** HIGH

- Disk I/O for every tree placement decision (100-500 times per region)
- OS directory listing overhead
- Should be cached at startup

### 3.4 getBiome() Called Per Species Per Chunk
**Location:** `TreeLocation.java:310`
**Priority:** HIGH

- Called ~35,840 times per region (1024 chunks × 35 species)
- Same position, different species - wasteful
- Should be called ONCE per chunk

### 3.5 String Operations in Hot Paths
**Priority:** HIGH

Multiple locations use string concatenation and parsing in hot loops:
- Biome cache key: `Utils.biome.toID(biome_center) + "|" + id`
- Config parsing: `startsWith()`, `replace()`, `substring()` in tight loops
- All should be pre-computed or use integer keys

### 3.6 Chunk Existence Check Loop
**Location:** `TreeLocation.java:920-942`
**Priority:** MEDIUM

- (to_chunk - from_chunk + 8)² getChunk() calls per tree
- For large trees: 18² = 324 calls
- May be optimizable with batch checking

### 3.7 detailed_detection File Read Per Tree
**Location:** `TreePlacer.java:99`
**Priority:** MEDIUM

- Reads same file repeatedly for each tree in region
- Should cache at start of chunk processing

### 3.8 Structure Detection Loop
**Location:** `TreePlacer.java:214-253`
**Priority:** LOW-MEDIUM

- For radius=2: 25 getChunk() calls per tree
- Chunk access can be expensive

---

## 4. Region Scan Phase - Detailed Analysis

This is the phase where the animation plays and world generation blocks.

### The Flow Per New Region

```
1. Pre-load 9 neighbor regions         │ Up to 9 file reads (~1-5 KB each)
   ├─ Read tree_locations.bin          │
   └─ Populate spatial grid            │ ~500 trees × 9 = 4,500 insertions
                                       │
2. Parse config file                   │ FileManager.readTXT() - DISK I/O
                                       │
3. Scan 32×32 = 1024 chunks            │ Main loop (region_scan_chance = 1.0)
   │                                   │
   └─ For EACH chunk:                  │
      └─ getData() called              │
         │                             │
         └─ For EACH species (~35):    │
            ├─ Parse species config    │ String operations
            ├─ getBiome() call         │ ◄── EXPENSIVE (35K calls total)
            ├─ Rarity check            │
            ├─ Biome compatibility     │ String key lookup
            ├─ testDistance()          │ O(1) via spatial grid
            │                          │
            └─ If passes:              │
               └─ writeData()          │
                  ├─ getWorldGenSettings()
                  ├─ File.listFiles()  │ ◄── DISK I/O per tree
                  ├─ getTreeShape()    │
                  └─ Chunk existence   │ ◄── 100-300 getChunk() calls
```

### Operation Counts Per Region

| Operation | Count | Notes |
|-----------|-------|-------|
| Chunks scanned | 1,024 | 32×32, if `region_scan_chance = 1.0` |
| Species per chunk | ~35 | Parsed from config each time |
| **Total species iterations** | **~35,840** | 1,024 × 35 |
| `getBiome()` calls | ~35,840 | Once per species per chunk |
| Biome test lookups | ~35,840 | String key concatenation each time |
| Spatial grid checks | ~1,000-3,000 | Only species passing rarity+biome |
| `File.listFiles()` | ~100-500 | Per placed tree |
| Chunk existence checks | ~30,000-150,000 | Per tree × chunks covered |

### Estimated Time Distribution

| Operation | Est. % of Time | Reason |
|-----------|---------------|--------|
| `getBiome()` calls | 25-35% | 35K calls, each touches chunk data |
| Config string parsing | 20-30% | Millions of string operations |
| `File.listFiles()` | 15-25% | Disk I/O, OS overhead |
| Chunk existence checks | 10-20% | `getChunk()` calls |
| Spatial grid lookups | 5-10% | Already optimized to O(1) |
| File writes at end | 5-10% | Batched, relatively fast |

---

## 5. Marker Entity & Living Tree Analysis

### Current Implementation
**Location:** `TreePlacer.java:1128-1146`

```java
if (ConfigMain.tree_location == true && dead_tree_level == 0) {
    if (can_leaves_decay || can_leaves_drop || can_leaves_regrow) {
        String marker_data = "ForgeData:{file:\"...\",tree_settings:\"...\"}";
        Utils.command.run(level_server, x, y, z,
            Utils.command.summonEntity("marker", "TANSHUGETREES-tree_location", ...));
    }
}
```

### Efficiency Problems

1. **Command parsing overhead** - Full command string parsed each spawn
2. **Entity creation cost** - Marker entity + NBT data allocation per tree
3. **Selector scanning** - Every second: `@e[tag=TANSHUGETREES-tree_location]` scans ALL entities
4. **Loop.java tick costs** - Entity selector queries every tick/second

### What Markers Are Used For

| Purpose | Location | Could Eliminate? |
|---------|----------|------------------|
| Living tree mechanics simulation | Loop.java:100-117 | Yes, if feature disabled |
| Tree counting via scoreboard | Loop.java:171-181 | Yes, use file-based count |
| Random tree selection for processing | Loop.java:110 | Replace with in-memory registry |

### Alternative Tracking Options

| Option | Speed | Memory | Persistence | Recommendation |
|--------|-------|--------|-------------|----------------|
| **Disable entirely** | N/A | N/A | N/A | Best for worldgen performance |
| **In-memory registry** | Very fast | Scales with trees | Manual save | Good for active use |
| **Chunk capabilities** | Fast | Minimal | Auto-saves | Moderate complexity |
| **Existing .bin files** | Medium | Disk-based | Already exists | No code change |

### Config Flags

```java
tree_location = false           // Disables marker spawning
living_tree_mechanics = false   // Disables all living tree features
leaf_litter_world_gen = false   // Disables leaf litter during gen
abscission_world_gen = false    // Disables seasonal leaf dropping
```

---

## 6. Region Data & Caching Analysis

### Current Memory Usage (9-Region Cache)

```
Raw tree data:     9 regions × 500 trees × 10 bytes = 45 KB
Java objects:      4,500 × RegionTreeData overhead   = ~200 KB
Spatial grid:      HashMap + ArrayList overhead      = ~50 KB
─────────────────────────────────────────────────────────────
Current Total:     ~300 KB per new region encounter
```

### With 100 MB Budget - Expanded Caching Potential

```
Available:         100 MB
Per region:        ~300 KB (current) or ~500 KB (with extra metadata)

Regions cacheable: 100,000 KB / 500 KB = ~200 regions
                   = 14×14 region grid = 448×448 chunks = 7,168×7,168 blocks

This is MASSIVE - enough to cache the entire explored area for most worlds.
```

### Proposed Expanded Caching Strategy

| Cache Type | Memory Est. | Purpose |
|------------|-------------|---------|
| **Parsed species configs** | ~50 KB | Eliminate per-region config parsing |
| **Shape file indices** | ~500 KB | Eliminate File.listFiles() |
| **Pre-loaded tree shapes** | ~10-50 MB | Eliminate shape file disk I/O |
| **Region tree data** | ~50 MB | Cache 100+ regions in memory |
| **Biome-species lookup table** | ~10 KB | Fast integer-keyed biome checks |
| **Total** | ~60-100 MB | Well within budget |

### Tree Shape Memory Estimate

```
Per shape file:    ~5-50 KB (varies by tree complexity)
Shapes per species: ~5-20
Species:           ~35
─────────────────────────────────────────────────────────────
Total shapes:      35 × 10 = ~350 files
Memory:            350 × 25 KB avg = ~8.75 MB

Conclusion: ALL tree shapes can fit in memory easily.
```

---

## 7. Tree-to-Tree Comparison Algorithm

### Current Algorithm (SpatialHashGrid)

```java
// O(1) average - Check only nearby cells
int cellX = Math.floorDiv(centerX, 256);
int cellZ = Math.floorDiv(centerZ, 256);

for (int dx = -1; dx <= 1; dx++) {
    for (int dz = -1; dz <= 1; dz++) {
        List<RegionTreeData> trees = grid.get(cellKey);
        for (RegionTreeData tree : trees) {
            // Position collision check
            // Same-species distance check (box distance)
        }
    }
}
```

**Performance:** O(1) average, ~77 trees checked per query

### Potential Improvements

| Option | Benefit | Tradeoff |
|--------|---------|----------|
| Species-specific grids | Only check same species | More memory |
| Skip cross-species checks | Much faster | Allows overlap |
| Integer species IDs | Faster comparison than String.equals() | Minor code change |

---

## 8. Multi-threading Analysis

### Current Threading Model

```java
synchronized (lock) {  // ◄── SINGLE GLOBAL LOCK
    TreeLocation.run(...);  // 4 calls
}
```

ALL shared state is static HashMap - NOT thread-safe.

### Options for Parallelization

| Option | Description | Benefit | Risk | Complexity |
|--------|-------------|---------|------|------------|
| **Early-out before lock** | Check region exists before lock | Skip lock for existing regions | Very Low | Low |
| **Per-region locks** | Lock only specific region | Parallel different regions | Low | Medium |
| **Background pre-computation** | Compute regions ahead of players | Hide latency | Low | Medium |
| **Concurrent data structures** | ConcurrentHashMap etc. | Fine-grained concurrency | Medium | Medium |

### Recommended Approach

1. **Immediate:** Early-out before lock
2. **Short-term:** Per-region locks
3. **Medium-term:** Background region pre-computation

---

## 9. Data Structure Thread-Safety Analysis

### Current Shared Mutable State (ALL NOT thread-safe)

```java
private static final Map<String, List<String>> cache_write_tree_location = new HashMap<>();
private static final Map<String, List<String>> cache_write_place = new HashMap<>();
private static final Map<String, List<String>> cache_dead_tree_auto_level = new HashMap<>();
private static final Map<String, Boolean> cache_biome_test = new HashMap<>();
private static final Map<String, List<RegionTreeData>> cache_region_files = new HashMap<>();
private static final SpatialHashGrid spatialGrid;  // Contains HashMap
```

### Thread-Safe Alternatives

| Current | Thread-Safe Alternative |
|---------|------------------------|
| `HashMap<K,V>` | `ConcurrentHashMap<K,V>` |
| `ArrayList<T>` | `CopyOnWriteArrayList<T>` or synchronized |
| String concatenation for keys | Integer/Long keys |
| Static mutable fields | Per-region isolated state |

### For C2ME Compatibility

- All read operations must be thread-safe
- Write operations need synchronization or isolation
- Consider immutable result objects from region computation

---

## 10. Two-Phase Architecture Proposal

### Concept

Separate "decision making" (expensive) from "block placement" (must be fast).

```
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 1: Region Pre-computation (Background Thread Pool)       │
│                                                                 │
│  • Triggered when region first needed OR predicted              │
│  • Runs in background thread(s)                                 │
│  • Computes ALL tree positions for 32×32 chunks                │
│  • Stores result in thread-safe cache                          │
│  • Multiple regions can compute in parallel                     │
│  • Result: ImmutableList<TreePlacement> per region             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  PHASE 2: Chunk Placement (C2ME Parallel - Main Thread)         │
│                                                                 │
│  • When chunk generates, look up pre-computed cache            │
│  • If not ready, either wait or trigger sync computation       │
│  • Filter trees for this chunk                                  │
│  • Place blocks (already parallel-safe)                         │
│  • NO shared mutable state during placement                    │
└─────────────────────────────────────────────────────────────────┘
```

### Benefits

1. **Hides latency** - Regions computed before players arrive
2. **Parallel region computation** - Multiple regions at once
3. **Immutable results** - Thread-safe by design
4. **Graceful degradation** - Falls back to sync if needed

### Implementation Considerations

- Need to track which regions are "in progress"
- Need coordination when regions share tree data (9-region lookups)
- May occasionally discard work if coordination needed
- Worth it if parallel gains exceed discarded work

---

## 11. Task List for Future Work

### Category A: Config & Startup Caching

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **A1** | Parse `config_world_gen.txt` once at startup into structured data | HIGH | Low | TBD |
| **A2** | Pre-index shape files per species at startup (eliminate File.listFiles) | HIGH | Low | TBD |
| **A3** | Load all tree shapes into memory (~10-50 MB) | HIGH | Medium | TBD |
| **A4** | Pre-compute biome-species compatibility lookup table (integer keys) | MEDIUM | Low | TBD |
| **A5** | Calculate memory usage of loading all tree models/properties | HIGH | Low | TBD |

### Category B: String Parsing & Runtime Overhead

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **B1** | Replace string-based biome cache keys with integer/long keys | HIGH | Low | TBD |
| **B2** | Eliminate all string concatenation in hot paths | HIGH | Medium | TBD |
| **B3** | Pre-parse all config values into typed fields (no runtime parsing) | HIGH | Medium | TBD |
| **B4** | Replace String species IDs with integer IDs where possible | MEDIUM | Medium | TBD |

### Category C: Global Lock Removal & Threading

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **C1** | Implement early-out before lock (check region exists first) | CRITICAL | Low | TBD |
| **C2** | Replace global lock with per-region locks | HIGH | Medium | TBD |
| **C3** | Make all shared data structures thread-safe for C2ME | HIGH | Medium | TBD |
| **C4** | Investigate background thread pool for region pre-computation | MEDIUM | High | TBD |
| **C5** | Implement two-phase architecture (background compute, sync placement) | MEDIUM | High | TBD |

### Category D: Region Scan Optimization

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **D1** | Call getBiome() once per chunk, not once per species | HIGH | Low | TBD |
| **D2** | Implement species filtering by biome/latitude before iteration | MEDIUM | Medium | TBD |
| **D3** | Early-exit chunks where no species can possibly spawn | MEDIUM | Medium | TBD |
| **D4** | Investigate early-out for parts of region (e.g., all ocean) | LOW | Medium | TBD |
| **D5** | Optimize chunk existence check loop (batch or cache) | MEDIUM | Medium | TBD |

### Category E: Region Caching Expansion

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **E1** | Expand region cache to use ~50 MB budget (100+ regions) | MEDIUM | Medium | TBD |
| **E2** | Implement LRU eviction for region cache | MEDIUM | Low | TBD |
| **E3** | Investigate predictive region pre-loading (player movement) | MEDIUM | Medium | TBD |
| **E4** | Cache region data in memory, eliminate disk reads after first load | HIGH | Medium | TBD |

### Category F: Living Tree & Marker Entities

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **F1** | Evaluate impact of disabling marker entities entirely | HIGH | Very Low | TBD |
| **F2** | If markers needed, replace with in-memory registry | MEDIUM | High | TBD |
| **F3** | If markers needed, eliminate command-based spawning | MEDIUM | Medium | TBD |
| **F4** | Document which living tree features require markers | LOW | Very Low | TBD |

### Category G: Disk I/O Elimination

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **G1** | Eliminate File.listFiles() for shape selection (use cached index) | HIGH | Low | TBD |
| **G2** | Keep all tree shapes in memory after first load | HIGH | Medium | TBD |
| **G3** | Cache detailed_detection per chunk instead of per tree | MEDIUM | Low | TBD |
| **G4** | Investigate memory-mapping region files for faster access | LOW | High | TBD |

### Category H: Modpack-Specific Optimizations

| Task ID | Task | Priority | Complexity | Branch |
|---------|------|----------|------------|--------|
| **H1** | Implement latitude/climate band filtering for species | MEDIUM | Medium | TBD |
| **H2** | Pre-compute valid species sets per biome | MEDIUM | Medium | TBD |
| **H3** | Skip species entirely outside their latitude range | MEDIUM | Low | TBD |

---

### Recommended Implementation Order

**Phase 1: Quick Wins (Low Risk, High Impact)**
1. C1 - Early-out before lock
2. A1 - Parse config at startup
3. A2 - Cache shape file lists
4. D1 - getBiome() once per chunk
5. B1 - Integer biome cache keys

**Phase 2: Core Optimizations**
6. G1 - Eliminate File.listFiles()
7. A3 - Load shapes into memory
8. B2/B3 - Eliminate string parsing
9. C2 - Per-region locks

**Phase 3: Advanced Optimizations**
10. E1-E4 - Expanded region caching
11. C4/C5 - Background pre-computation
12. D2/D3 - Species filtering
13. F1-F4 - Marker entity replacement

---

## Appendix: File Locations Reference

```
TreeLocation.java:
  - Global lock: line 107
  - SpatialHashGrid: lines 50-103
  - Region caching: lines 138-171
  - Config parsing: line 176
  - getBiome() per species: line 310
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

ConfigMain.java:
  - region_scan_chance: line 18 (default 1.0 = 100%)
  - tree_location: controls marker spawning
  - living_tree_mechanics: master switch
```

---

## Notes

### Constraints
- **No chunk-level deterministic placement** - Stick with region-based approach
- **C2ME compatibility required** - All operations must be thread-safe
- **Memory budget: 100 MB** - Sufficient for extensive caching

### Key Insights
1. The 32×32 chunk scan is the main blocking phase
2. ~35,840 unnecessary getBiome() calls per region
3. String operations dominate CPU time
4. Disk I/O (File.listFiles, config parsing) is easily eliminated
5. With 100 MB, we can cache 200+ regions in memory
6. Many regions can be computed independently in parallel

---

*Document updated: December 2025*
*Branch: 1.20.1-2025.2-sisterpc-perf-review*
