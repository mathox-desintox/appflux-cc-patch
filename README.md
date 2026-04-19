# appflux-cc-patch

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.222-orange.svg)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-LGPL--3.0-blue.svg)](LICENSE)

Server-side companion mod for [AppliedFlux](https://github.com/GlodBlock/ExtendedAE) that adds **long-precision ComputerCraft peripheral methods** to the flux accessor and to AE2 drives holding FE storage cells, so CC:Tweaked computers can read an AE2 network's stored FE beyond the `int32` ceiling.

## Why

AppliedFlux stores FE as a `long` internally, but exposes it to external mods through NeoForge's `IEnergyStorage` capability, whose `getEnergyStored()` returns `int`. AppliedFlux clamps to `Integer.MAX_VALUE` (2,147,483,647 ≈ 2.1 GFE), so any network storing more than that saturates and you can't read the real number from a CC:Tweaked computer. For setups storing tens of GFE, TFE, or PFE this makes CC-based energy monitoring unusable.

This mod ignores the `IEnergyStorage` path entirely and reads:
- **Network total FE** directly from AE2's `IStorageService.getCachedInventory()` (long).
- **Per-cell stored FE** directly from AppliedFlux's `AFSingletons.FE_ENERGY` DataComponent on each cell's item stack (long).

Both are exposed as new CC peripheral types with long-precision methods.

## What it adds

Two new CC peripherals, both read-only, both server-side:

### `flux_accessor_ext` — network-level FE readings

Attached to both forms of AppliedFlux's flux accessor:

- `appflux:flux_accessor` (full block)
- `appflux:part_flux_accessor` (AE2 cable-bus part — side-dispatched on the `ae2:cable_bus` BE)

| Method | Returns | Notes |
|---|---|---|
| `getEnergyLong()` | `number` (double) | Current stored FE across the whole AE2 network. Lossless up to 2⁵³ ≈ 9 PFE; degrades linearly above |
| `getEnergyCapacityLong()` | `number` (double) | Total FE capacity (stored + free) |
| `getEnergyString()` | `string` | Current stored FE as decimal digits — full long precision (up to 2⁶³-1 ≈ 9.2 EFE) |
| `getEnergyCapacityString()` | `string` | Total capacity as decimal digits — full long precision |
| `isOnline()` | `boolean` | Whether the grid is powered/online |
| `getNetworkFluxCellCount()` | `number` | Count of FE cells across every recognised drive on this grid |
| `listNetworkFluxCells()` | `table[]` | Info for every FE cell on the grid — same shape as `flux_drive.listFluxCells()` plus `drivePos={x,y,z}` and `driveType` keys |

The last two methods traverse the AE2 grid to find all drives, so one CC computer connected to a single flux accessor can enumerate every FE cell in every drive on the network — no wired-modem-to-each-drive setup needed.

### `flux_drive` — per-drive cell readings (optional)

Same data as `listNetworkFluxCells()` but scoped to a single drive. Use this only if you'd rather wire a computer directly to one drive than query everything from the flux accessor.

Attached to every registered drive block-entity type present at runtime:
- `ae2:drive` (stock AE2 drive, 10 slots)
- `extendedae:ex_drive` (ExtendedAE drive, 20 slots)

Non-FE-cell slots are ignored. Stored FE is read from each cell item's `AFSingletons.FE_ENERGY` DataComponent. Capacity is computed from `IFluxCell.getBytes(stack) × FluxKeyType.TYPE.getAmountPerByte()`.

| Method | Returns | Notes |
|---|---|---|
| `getSlotCount()` | `number` | Total cell slots on this drive (all slots, not just FE) |
| `getFluxCellCount()` | `number` | Number of slots currently occupied by an FE cell |
| `getFluxCell(slot)` | `table` or `nil` | Cell info at 1-based slot; nil if empty or not an FE cell |
| `listFluxCells()` | `table[]` | Info for every FE cell on the drive |

Cell info tables contain: `slot`, `name`, `stored`, `storedString`, `capacity`, `capacityString`, `fillPercent`, `full`, `empty`.

### When to use which return type

Lua's `number` is a 64-bit double with 53 bits of mantissa, so the double variants are exact up to ~9 PFE. Above that, precision degrades as `value × 2⁻⁵²` — at 40 PFE that's ~9 FE of rounding per reading, which is invisible on any dashboard (your transfer rate is orders of magnitude larger than that noise floor) but might matter if you store values in a database or need exact display down to the last FE.

**Rule of thumb:** use the double methods for all math (rate calc, ETA, fill%), and the string methods whenever you need exact digits in display or storage.

The original `getEnergy()` / `getEnergyCapacity()` peripheral methods from CC:Tweaked's generic `IEnergyStorage` wrapper remain attached and still return `int`-clamped values — nothing is removed.

## Server-only install

The mod declares itself with `displayTest = "IGNORE_ALL_VERSION"` and all dependencies marked `side = "SERVER"`. This means:

- **Install the built JAR only on the server.** Drop it in the server's `mods/` folder. Restart the server.
- **Clients don't need any changes.** Players keep running their stock pack. The handshake ignores this mod.
- No client-side behavior is added — the peripherals are read-only server-side capabilities.
- Rolling it back is as simple as deleting the JAR and restarting.

## Building

Requires JDK 21.

```bash
git clone https://github.com/mathox-desintox/appflux-cc-patch.git
cd appflux-cc-patch
./gradlew build
```

Output JAR: `build/libs/appflux_cc-<version>.jar`

The Gradle wrapper is checked into the repo, so no separate Gradle install is needed.

## Version targets

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.222 |
| AE2 | 19.2.17 |
| CC:Tweaked | 1.117.1 |
| AppliedFlux | 1.21-2.1.x-neoforge |
| Java | 21 |

## Project layout

```
appflux-cc-patch/
├── .github/workflows/build.yml              # CI (Java 21 + ./gradlew build)
├── build.gradle                             # ModDevGradle + AE2 + CC:Tweaked (compileOnly)
├── gradle.properties                        # Version pins
├── gradlew, gradlew.bat, gradle/            # Gradle wrapper
├── settings.gradle
└── src/main/
    ├── java/net/mathox/appfluxcc/
    │   ├── AppFluxCC.java                   # @Mod entry point
    │   ├── AppFluxBridge.java               # Reflective lookup of AppliedFlux classes + cell reads
    │   ├── FluxAccessorPeripheral.java      # IPeripheral for network-level FE
    │   ├── FluxDrivePeripheral.java         # IPeripheral for per-cell FE in drives
    │   └── PeripheralCapabilityHandler.java # RegisterCapabilitiesEvent handler
    └── resources/
        ├── META-INF/neoforge.mods.toml      # Mod manifest (server-only displayTest)
        └── pack.mcmeta
```

## Why reflection for AppliedFlux?

AppliedFlux isn't published to a public Maven repo we can target, so pulling it in as a compile-time dependency would require bundling its jar or adding a local `includeBuild`. To keep the build self-contained and resilient to AppliedFlux updates, we resolve AppliedFlux-specific symbols (`FluxKey`, `EnergyType`, `FluxKeyType`, `AFSingletons`, `PartFluxAccessor`, `IFluxCell`) at runtime through reflection. AE2 and CC:Tweaked are both on Maven and referenced as `compileOnly`.

If AppliedFlux ever publishes a proper API module, replace the `Class.forName` lookups in `AppFluxBridge` with direct imports.

## Testing quick-start (in-game)

After installing the JAR and restarting the server:

1. Place a flux accessor connected to your AE2 network, and/or place a CC computer against an AE2/ExtendedAE drive.
2. Put a CC:Tweaked advanced computer adjacent to it (or wire via a wired modem network).
3. Terminal:

```lua
local a = peripheral.find("flux_accessor_ext")
if not a then error("no flux_accessor_ext peripheral found") end

-- Network-level total
print(string.format("FE:    %15.0f", a.getEnergyLong()))
print(string.format("Cap:   %15.0f", a.getEnergyCapacityLong()))
print("online: " .. tostring(a.isOnline()))
print(string.format("cells: %d", a.getNetworkFluxCellCount()))

-- Per-cell detail, every drive on the AE2 network, from one peripheral
for _, c in ipairs(a.listNetworkFluxCells()) do
  print(string.format("  [%s @ %d,%d,%d slot %2d]  %5.1f%%  %s / %s  %s",
    c.driveType, c.drivePos.x, c.drivePos.y, c.drivePos.z, c.slot,
    c.fillPercent, c.storedString, c.capacityString,
    c.full and "FULL" or (c.empty and "EMPTY" or "")))
end
```

If `getEnergyLong()` returns your real stored FE (not 2,147,483,647), the network total path is working. If `listNetworkFluxCells()` returns non-empty entries, the grid-traversal path is working. You can also wire a computer directly to a drive and use the `flux_drive` peripheral if you prefer per-drive granularity, but it's not required.

## License

LGPL-3.0 — matches AppliedFlux's license (this mod is a downstream add-on).
