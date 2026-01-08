# TreePlacer Optimization Plan

## Branch: `treePlacer-optimizations`

Created from `main` branch after completing initial performance optimizations (region scanning, config parsing, placement caching).

## Background

Profiling identified `WorldGenBeforePlants` as a significant time consumer. This Feature calls:
1. `TreeLocation.start()` - Region scanning (already optimized)
2. `TreePlacer.start()` - Tree placement (target of this plan)

### Current TreePlacer Issues

1. **Repeated disk reads**: `detailed_detection` file read per-tree instead of per-region
2. **Linear scans**: O(n) scan through all trees to find chunk-relevant ones
3. **Linear lookups**: O(m) scan through detection entries to find position match
4. **Redundant structure detection**: O(r^2) chunk scans repeated per tree
5. **No geometric filtering**: Trees impossibly far from chunk still processed

## Cache Size Estimates

| Cache | Per-Unit Size | Units Cached | Total Memory |
|-------|---------------|--------------|--------------|
| Species config | ~200 bytes | 50 species | ~10 KB (permanent) |
| detailed_detection | ~15 bytes/tree x 500 trees | per region | ~7.5 KB/region |
| Placement data | ~40 bytes/tree x 500 trees | per region | ~20 KB/region |
| Structure detection | ~8 bytes/chunk | per region (1024 chunks) | ~8 KB/region |
| Detection index (HashMap) | ~24 bytes/entry x 500 | per region | ~12 KB/region |

**Total per region**: ~50 KB
**With 25-region LRU cache**: ~1.25 MB
**With 81-region LRU cache**: ~4 MB

These are negligible on modern systems.

## Cache Eviction Strategy

Use odd-square cache sizes (9, 25, 49, 81) because chunk generation at region boundaries needs adjacent region data. Recommended: **25 regions** (5x5 grid around current).

| Cache Type | Strategy | Rationale |
|------------|----------|-----------|
| Species config | Permanent | Static, tiny, always needed |
| detailed_detection | LRU(25) | Cross-region access patterns |
| placement_by_chunk | LRU(25) | Same as above |
| structure_detection | Clear per-chunk | Only needed during chunk processing |
| heightmap | ThreadLocal, clear per-chunk | Chunk-local, no cross-chunk benefit |

---

## Phase 1: Region-Level Caching

### P1.1: Cache detailed_detection per region with LRU eviction

**Status**: [ ] Not Started

**Location**: `Cache.java` (new methods) + `TreePlacer.java` (consume cache)

**Implementation**:
```java
// In Cache.java
private static final int DETECTION_CACHE_SIZE = 25; // 5x5 region grid
private static final LinkedHashMap<String, Map<Long, DetectionResult>>
    detailed_detection_cache = new LinkedHashMap<>(DETECTION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > DETECTION_CACHE_SIZE;
        }
    };

public static class DetectionResult {
    public final boolean pass;
    public final int posY;
    public final int deadTreeLevel;

    public DetectionResult(boolean pass, int posY, int deadTreeLevel) {
        this.pass = pass;
        this.posY = posY;
        this.deadTreeLevel = deadTreeLevel;
    }
}

public static Map<Long, DetectionResult> getDetailedDetection(String dimension, String regionKey) {
    String cacheKey = dimension + "/" + regionKey;
    return detailed_detection_cache.computeIfAbsent(cacheKey, k -> loadAndIndexDetection(k));
}

private static Map<Long, DetectionResult> loadAndIndexDetection(String cacheKey) {
    Map<Long, DetectionResult> indexed = new HashMap<>();
    String path = Handcode.path_world_data + "/world_gen/detailed_detection/" + cacheKey + ".bin";
    ByteBuffer buffer = FileManager.readBIN(path);

    while (buffer.remaining() > 0) {
        try {
            boolean pass = buffer.get() == 1;
            int posX = buffer.getInt();
            int posY = buffer.getInt();
            int posZ = buffer.getInt();
            int deadTreeLevel = buffer.getShort();

            long key = ((long)posX << 32) | (posZ & 0xFFFFFFFFL);
            indexed.put(key, new DetectionResult(pass, posY, deadTreeLevel));
        } catch (Exception e) {
            break;
        }
    }
    return indexed;
}
```

**Changes to TreePlacer.java**:
- Remove per-tree disk read at line ~107
- Get detection cache once at start of method
- Replace linear scan with HashMap lookup

**Risk**: Low
**Estimated Gain**: High (eliminates N disk reads per chunk where N = trees)

---

### P1.2: Index detailed_detection by position (HashMap) for O(1) lookup

**Status**: [ ] Not Started (included in P1.1)

**Key generation**:
```java
long key = ((long)posX << 32) | (posZ & 0xFFFFFFFFL);
```

**Risk**: Low
**Estimated Gain**: Medium (O(m) -> O(1) per lookup)

---

### P1.3: Cache structure detection results per chunk

**Status**: [ ] Not Started

**Location**: `TreePlacer.java`

**Implementation**:
```java
// ThreadLocal for C2ME compatibility
private static final ThreadLocal<Map<Long, Boolean>> structureCache =
    ThreadLocal.withInitial(HashMap::new);

// At start of TreePlacer.start():
structureCache.get().clear();

// Before structure detection loop (around line 234):
long structureKey = ((long)(center_chunkX + scanX) << 32) | ((center_chunkZ + scanZ) & 0xFFFFFFFFL);
Boolean cached = structureCache.get().get(structureKey);
if (cached != null) {
    if (cached) {
        // Has structure, skip this tree
        break test;
    }
    continue; // No structure, continue checking other chunks
}
// ... existing detection code ...
// After detection, cache result:
structureCache.get().put(structureKey, hasStructure);
```

**Risk**: Low
**Estimated Gain**: Medium (avoids repeated O(r^2) scans)

---

## Phase 2: Algorithmic Restructuring

### P2.1: Index placement data by chunk for O(1) tree lookup

**Status**: [ ] Not Started

**Location**: `Cache.java` + `TreePlacer.java`

**Implementation**:
```java
// In Cache.java
public static class TreePlacement {
    public final int fromChunkX, fromChunkZ, toChunkX, toChunkZ;
    public final String id, chosen;
    public final int centerPosX, centerPosZ;
    public final int rotation;
    public final boolean mirrored;
    public final int startHeightOffset, upSizeY;
    public final String groundBlock;
    public final int deadTreeLevel;
    // Constructor...
}

private static final int PLACEMENT_INDEX_CACHE_SIZE = 25;
private static final LinkedHashMap<String, Map<Long, List<TreePlacement>>>
    placement_by_chunk_cache = new LinkedHashMap<>(PLACEMENT_INDEX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > PLACEMENT_INDEX_CACHE_SIZE;
        }
    };

public static List<TreePlacement> getTreesForChunk(String dimension, String regionKey, int chunkX, int chunkZ) {
    String cacheKey = dimension + "/" + regionKey;
    Map<Long, List<TreePlacement>> regionIndex = placement_by_chunk_cache.computeIfAbsent(
        cacheKey, k -> buildChunkIndex(dimension, regionKey));

    long chunkKey = ((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    return regionIndex.getOrDefault(chunkKey, Collections.emptyList());
}

private static Map<Long, List<TreePlacement>> buildChunkIndex(String dimension, String regionKey) {
    Map<Long, List<TreePlacement>> index = new HashMap<>();
    ByteBuffer buffer = getPlacement(dimension, regionKey); // Use existing G5 cache

    while (buffer.remaining() > 0) {
        TreePlacement tree = parseTreePlacement(buffer);
        if (tree == null) break;

        // Add to all chunks this tree spans
        for (int cx = tree.fromChunkX; cx <= tree.toChunkX; cx++) {
            for (int cz = tree.fromChunkZ; cz <= tree.toChunkZ; cz++) {
                long chunkKey = ((long)cx << 32) | (cz & 0xFFFFFFFFL);
                index.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(tree);
            }
        }
    }
    return index;
}
```

**Changes to TreePlacer.java**:
- Replace `while (get.remaining() > 0)` loop with direct chunk lookup
- Iterate only over trees relevant to current chunk

**Risk**: Medium (significant refactor)
**Estimated Gain**: High (O(n) -> O(k) where k << n)

---

### P2.2: Add footprint bounds to SpeciesWorldGenConfig

**Status**: [ ] Not Started

**Location**: `Cache.java` (SpeciesWorldGenConfig class)

**New fields**:
```java
public final int maxFootprintRadius; // Maximum horizontal extent in blocks
public final int maxChunkSpan;       // (maxFootprintRadius + 15) >> 4
```

**Computation**: Parse tree settings once at startup, extract maximum possible footprint.

**Risk**: Low
**Estimated Gain**: Enables P2.3

---

### P2.3: Filter trees by geometric bounds before processing

**Status**: [ ] Not Started

**Location**: `TreePlacer.java`

**Implementation**:
```java
// Before processing each tree:
int treeCenterChunkX = tree.centerPosX >> 4;
int treeCenterChunkZ = tree.centerPosZ >> 4;
int dx = Math.abs(treeCenterChunkX - chunkX);
int dz = Math.abs(treeCenterChunkZ - chunkZ);

SpeciesWorldGenConfig species = Cache.getSpeciesConfig(tree.id);
if (species != null && (dx > species.maxChunkSpan || dz > species.maxChunkSpan)) {
    continue; // Geometrically impossible to affect this chunk
}
```

**Risk**: Low
**Estimated Gain**: Medium (skip impossible candidates early)

---

## Phase 3: Advanced Optimizations

### P3.1: Cache heightmap results within chunk

**Status**: [ ] Not Started

**Location**: `TreePlacer.java`

**Implementation**:
```java
private static final ThreadLocal<Map<Long, Integer>> heightmapCache =
    ThreadLocal.withInitial(HashMap::new);

// At start of chunk processing:
heightmapCache.get().clear();

// Wrapper for getBaseHeight:
private static int getCachedHeight(ChunkGenerator gen, int x, int z, Heightmap.Types type,
                                    LevelAccessor level, RandomState state) {
    long key = ((long)x << 32) | (z & 0xFFFFFFFFL);
    return heightmapCache.get().computeIfAbsent(key,
        k -> gen.getBaseHeight(x, z, type, level, state));
}
```

**Risk**: Low
**Estimated Gain**: Low-Medium (avoid redundant heightmap queries)

---

## Implementation Order

```
P1.1 + P1.2 --> P2.1 --> P1.3 --> P2.2 --> P2.3 --> P3.1
    |             |        |        |        |        |
    v             v        v        v        v        v
  TEST          TEST     TEST     TEST     TEST     TEST
```

**Recommended sequence**:
1. **P1.1 + P1.2**: Detection cache + index (biggest immediate win, low risk)
2. **P2.1**: Placement chunk index (major algorithmic improvement)
3. **P1.3**: Structure detection cache (incremental improvement)
4. **P2.2 + P2.3**: Footprint bounds + geometric filter (uses static species data)
5. **P3.1**: Heightmap cache (polish)

---

## Testing Strategy

After each phase:
1. Run Chunky pregeneration benchmark (1000 block radius)
2. Compare THT-PERF metrics if still enabled, or overall generation time
3. Verify tree placement correctness (visual inspection, count comparison)
4. Test C2ME compatibility (parallel chunk generation)

---

## Thread Safety Notes

All caches must be thread-safe for C2ME compatibility:
- Use `ConcurrentHashMap` or synchronized `LinkedHashMap` for shared caches
- Use `ThreadLocal` for per-thread working data
- Clear ThreadLocal caches at start/end of chunk processing

---

## Rollback Plan

If issues arise:
1. Each phase is independent and can be reverted
2. Branch `main` contains stable pre-optimization state
3. Each commit should be atomic and testable

---

## Progress Tracking

- [ ] P1.1: Cache detailed_detection per region
- [ ] P1.2: Index detailed_detection by position
- [ ] P2.1: Index placement data by chunk
- [ ] P1.3: Cache structure detection results
- [ ] P2.2: Add footprint bounds to SpeciesWorldGenConfig
- [ ] P2.3: Filter trees by geometric bounds
- [ ] P3.1: Cache heightmap results

---

## Session Notes

(Add notes here as implementation progresses across sessions)

### Session 1 (Initial Planning)
- Created branch `treePlacer-optimizations`
- Analyzed TreePlacer code structure
- Identified 5 major optimization opportunities
- Estimated cache sizes (~50KB/region, negligible on modern systems)
- Decided on LRU(25) for region-level caches (5x5 grid)
