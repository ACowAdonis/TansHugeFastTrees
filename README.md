This is a forked version of Tan's Huge Trees from https://github.com/TannyJungMC/TansHugeTrees

Made with Tan's permission and shared (hopefully) with him.

All credit should go to Tan for creating such a great mod :)

# Goal of this fork

- This fork is an attempt to dramatically improve world generation performance, and that's pretty much it.  I wanted to see how far we could push things while still leaving it playable in practice and still with minimal bugs.

In our heavily modded environment, we measured in our benchmarks a generation time of 1 to 7 seconds of calculations per region.  This fork attempts to reduce that to ~300ms per region - a roughly 10x improvement in theoretical throughput.

# Key Optimizations

## Config Parsing (A1/B3)
- Pre-parse species configuration at startup instead of per-tree
- Eliminates ~7.7 million string comparisons per region

## Biome Caching (B1/D1)
- Sample biome once per chunk instead of once per species check
- Use long keys instead of string concatenation for cache lookups
- Reduces biome lookups from 35,840 to 1,024 per region (35× reduction)

## In-Memory Placement Cache (G5)
- Cache placement data in memory after generation
- Eliminates disk I/O for TreePlacer reading recently-written data
- Thread-safe with ConcurrentHashMap for C2ME compatibility

## Pre-Parsed Path Storage (B2)
- Parse species path_storage once during config load
- Eliminates repeated string splitting during tree placement

## Locale-Safe Number Formatting (which i'm pretty sure Tan already fixed in his later versions)
- Fixed crash on systems with European locale (comma decimal separator)
- Replaced Double.parseDouble(String.format()) pattern with arithmetic rounding

# Additional Changes
- Disabled generation overlay (no longer needed with fast generation)
- Disabled automatic update checks (locked to compatible tree pack version)
- Renamed to "Tan's Huge Fast Trees" for identification

# Compatibility
- Minecraft 1.20.1
- Forge 47.x
- C2ME compatible (thread-safe caching)
- Uses same mod_id as original for world/config compatibility

# Credits
TannyJung — Original Tan's Huge Trees mod

# Terms of Use

- This version of Tan's Huge Trees exists as a proof-of-concept in the Au Naturel modpack with Tan's permission and for Tan to decide if he wants to on-board any of the changes into his mainline.

- I am not the original author of Tan's Huge Trees, nor am i responsible for any licensing, permissions or restrictions.  Do not contact Tan for help with this version of the mod or any issues associated with its code.  

The official version of Tan's Huge Trees should still be accessed at https://modrinth.com/mod/tans-huge-trees or https://www.curseforge.com/minecraft/mc-mods/tans-huge-trees

