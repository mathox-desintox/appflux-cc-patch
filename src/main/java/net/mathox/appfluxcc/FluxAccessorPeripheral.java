package net.mathox.appfluxcc;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ComputerCraft peripheral attached to an AppliedFlux flux accessor — works
 * for both forms of the accessor:
 *
 *   - Full block (appflux:flux_accessor, host is a {@code TileFluxAccessor}
 *     via {@code AENetworkBlockEntity})
 *   - Cable part (appflux:part_flux_accessor, host is a {@code PartFluxAccessor}
 *     via AE2's {@code AEBasePart})
 *
 * Both extend AE2 classes that implement {@link IActionHost}, which exposes
 * {@code getActionableNode()} — our one handle into the AE2 grid.
 *
 * Reads stored FE and capacity at long precision by going directly to AE2's
 * {@link IStorageService}, bypassing AppliedFlux's int32-clamping
 * {@code IEnergyStorage} wrapper.
 *
 * Lua numbers are 64-bit doubles (53-bit mantissa), so any FE value up to
 * 2^53 ≈ 9 PFE round-trips losslessly.
 */
public final class FluxAccessorPeripheral implements IPeripheral {

    private final IActionHost host;

    // ─── Per-tick cache ──────────────────────────────────────────────
    //
    // All the fields below are populated once per MC server tick by
    // `onServerTick`, which is called from a NeoForge tick handler
    // running on the server thread. Lua threads then read them via
    // the non-mainThread `getCached*` / `getRate*` methods - instant
    // and thread-safe via volatile, so the CC computer can sample at
    // its full 20 Hz tick rate instead of being throttled to ~8-10
    // Hz by mainThread=true round-trips on every read.

    private volatile long    cachedStored   = 0L;
    private volatile long    cachedCapacity = 0L;
    private volatile boolean cachedOnline   = false;
    private volatile long    cachedServerTick = 0L;

    // Ring buffer of (stored, serverTick, ts_ms) for the last
    // RING_SIZE MC ticks. RING_SIZE = 600 = 30 seconds, enough for
    // any short-window rate calc we're likely to ask for from Lua.
    // We keep both the server tick number (for tick-count-based
    // rate math) and the wall-clock millisecond timestamp (so Lua
    // clients can line up the samples against os.epoch("utc")
    // without maintaining their own tick-to-ms mapping).
    //
    // Writer is the tick handler (server thread); readers are Lua
    // threads, so we synchronise on ringLock for every read/write.
    private static final int RING_SIZE = 600;
    private final long[] ringStored = new long[RING_SIZE];
    private final long[] ringTick   = new long[RING_SIZE];
    private final long[] ringTsMs   = new long[RING_SIZE];
    private int ringHead  = 0;
    private int ringCount = 0;
    private final Object ringLock = new Object();

    // Weak registry of live peripherals. The tick handler iterates
    // this every tick to snapshot each one; WeakHashMap lets an
    // instance GC naturally when CC-Tweaked drops its reference
    // (e.g. computer disconnected / accessor broken).
    private static final Set<FluxAccessorPeripheral> ACTIVE =
        Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    public FluxAccessorPeripheral(IActionHost host) {
        this.host = host;
        ACTIVE.add(this);
    }

    // ─── Server-tick hook ────────────────────────────────────────────

    /**
     * Snapshot current grid state into the cache + ring for one
     * peripheral. Called by the global tick handler on the server
     * thread - no yielding, no scheduling. AE2 reads here are safe
     * because we ARE the server thread.
     */
    public void onServerTick(long serverTick) {
        IStorageService storage = storage();
        AEKey key = AppFluxBridge.feKey();
        if (storage == null || key == null) {
            cachedOnline = false;
            return;
        }
        long stored = storage.getCachedInventory().get(key);
        long headroom = Math.max(0L, Long.MAX_VALUE - 1L - stored);
        long free = storage.getInventory().insert(
            key, headroom, Actionable.SIMULATE, IActionSource.ofMachine(host));
        long capacity = stored + free;

        cachedStored     = stored;
        cachedCapacity   = capacity;
        cachedServerTick = serverTick;
        IGridNode node = host.getActionableNode();
        cachedOnline = node != null && node.isOnline();

        long ts = System.currentTimeMillis();
        synchronized (ringLock) {
            ringStored[ringHead] = stored;
            ringTick[ringHead]   = serverTick;
            ringTsMs[ringHead]   = ts;
            ringHead = (ringHead + 1) % RING_SIZE;
            if (ringCount < RING_SIZE) ringCount++;
        }
    }

    /**
     * Entry point for the mod's ServerTickEvent listener. Snapshots
     * everyone currently in the active registry. Exceptions from
     * any individual peripheral are swallowed so one misbehaving
     * grid doesn't wedge the rest.
     */
    public static void tickAll(long serverTick) {
        FluxAccessorPeripheral[] snapshot;
        synchronized (ACTIVE) {
            snapshot = ACTIVE.toArray(new FluxAccessorPeripheral[0]);
        }
        for (FluxAccessorPeripheral p : snapshot) {
            try {
                p.onServerTick(serverTick);
            } catch (Throwable t) {
                AppFluxCC.LOGGER.debug("[appflux_cc] tick snapshot failed", t);
            }
        }
    }

    /**
     * @return (dt_ticks, dv) from the ring: newest sample back to the
     * first sample whose tick is <= (newest - lookbackTicks). Returns
     * null if the ring doesn't have enough data yet.
     */
    private long[] ringLookback(int lookbackTicks) {
        synchronized (ringLock) {
            if (ringCount < 2) return null;
            int newestIdx = (ringHead - 1 + RING_SIZE) % RING_SIZE;
            long newestTick  = ringTick[newestIdx];
            long newestValue = ringStored[newestIdx];
            long targetTick  = newestTick - lookbackTicks;
            int pastIdx = newestIdx;
            for (int i = 1; i < ringCount; i++) {
                int idx = (newestIdx - i + RING_SIZE) % RING_SIZE;
                pastIdx = idx;
                if (ringTick[idx] <= targetTick) break;
            }
            long dt = newestTick - ringTick[pastIdx];
            if (dt <= 0) return null;
            return new long[] { dt, newestValue - ringStored[pastIdx] };
        }
    }

    @Override
    public String getType() {
        return "flux_accessor_ext";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other
            || (other instanceof FluxAccessorPeripheral p && p.host == this.host);
    }

    // ─── Lua API ──────────────────────────────────────────────────────────

    /**
     * @return current stored FE in the AE2 network the accessor is attached
     * to, as a Lua double. Returns 0 if the grid isn't online / available.
     */
    @LuaFunction(mainThread = true)
    public final double getEnergyLong() {
        IStorageService storage = storage();
        AEKey key = AppFluxBridge.feKey();
        if (storage == null || key == null) return 0.0;
        return (double) storage.getCachedInventory().get(key);
    }

    /**
     * @return total FE capacity of the network (currently stored + free space
     * still available for insertion), as a Lua double.
     */
    @LuaFunction(mainThread = true)
    public final double getEnergyCapacityLong() {
        IStorageService storage = storage();
        AEKey key = AppFluxBridge.feKey();
        if (storage == null || key == null) return 0.0;
        long stored = storage.getCachedInventory().get(key);
        long headroom = Math.max(0L, Long.MAX_VALUE - 1L - stored);
        long free = storage.getInventory().insert(key, headroom, Actionable.SIMULATE, IActionSource.ofMachine(host));
        return (double) (stored + free);
    }

    /**
     * @return current stored FE as a decimal string. Preserves full {@code long}
     * precision (any value up to 2^63-1 ≈ 9.2 EFE). Use this when the value
     * may exceed 2^53 ≈ 9 PFE and you need exact digits (e.g. to display with
     * no rounding, or to round-trip through a database).
     *
     * Returns "0" on error / grid unavailable.
     */
    @LuaFunction(mainThread = true)
    public final String getEnergyString() {
        IStorageService storage = storage();
        AEKey key = AppFluxBridge.feKey();
        if (storage == null || key == null) return "0";
        return Long.toString(storage.getCachedInventory().get(key));
    }

    /**
     * @return total FE capacity as a decimal string. Full long precision.
     * See {@link #getEnergyString()} for when to use this over the double variant.
     */
    @LuaFunction(mainThread = true)
    public final String getEnergyCapacityString() {
        IStorageService storage = storage();
        AEKey key = AppFluxBridge.feKey();
        if (storage == null || key == null) return "0";
        long stored = storage.getCachedInventory().get(key);
        long headroom = Math.max(0L, Long.MAX_VALUE - 1L - stored);
        long free = storage.getInventory().insert(key, headroom, Actionable.SIMULATE, IActionSource.ofMachine(host));
        return Long.toString(stored + free);
    }

    /**
     * @return true if the accessor is connected to an online AE2 grid.
     */
    @LuaFunction(mainThread = true)
    public final boolean isOnline() {
        IGridNode node = host.getActionableNode();
        return node != null && node.isOnline();
    }

    // ─── Cached (non-mainThread) fast-path API ───────────────────────
    //
    // These read the cache populated by the server-tick handler.
    // They do NOT schedule onto the server thread - so each call
    // returns instantly, without the 50ms mainThread round-trip that
    // caps every `mainThread=true` peripheral method at 20 Hz best
    // case. A CC computer can therefore sample at its full
    // `sleep(0.05)` cadence instead of being throttled to ~8 Hz.
    //
    // Values reflect the STATE AT THE LAST COMPLETED MC TICK. For FE
    // (which changes per tick), that's the freshest value that was
    // ever observable anywhere else in the mod.

    /** Latest cached stored FE as a Lua double (precise to ~9 PFE). */
    @LuaFunction
    public final double getCachedEnergy() { return (double) cachedStored; }

    /** Latest cached stored FE as a full-precision decimal string. */
    @LuaFunction
    public final String getCachedEnergyString() { return Long.toString(cachedStored); }

    /** Latest cached network capacity (stored + free) as a double. */
    @LuaFunction
    public final double getCachedCapacity() { return (double) cachedCapacity; }

    @LuaFunction
    public final String getCachedCapacityString() { return Long.toString(cachedCapacity); }

    /** Latest cached "grid online" flag. */
    @LuaFunction
    public final boolean getCachedOnline() { return cachedOnline; }

    /**
     * The MC server tick number at which the cache was last updated.
     * Useful for Lua-side staleness detection if the server stalls.
     */
    @LuaFunction
    public final double getCachedTick() { return (double) cachedServerTick; }

    /**
     * Average FE change per SECOND over the last `lookbackTicks`
     * server ticks (default 20 = 1 second). Computed entirely server-
     * side off the tick ring buffer, so no CC-side bucketing /
     * filtering is needed to get a stable rate.
     *
     * @param lookbackTicks window width in ticks (1..600). Values
     *        outside that range are clamped; missing / zero-default
     *        → 20.
     * @return FE per real second (MC has 20 ticks/sec). Returns 0 if
     *         the ring hasn't accumulated enough samples yet.
     */
    @LuaFunction
    public final double getRateFEPerSecond(Optional<Integer> lookbackTicks) {
        int ticks = clampLookback(lookbackTicks.orElse(20));
        long[] d = ringLookback(ticks);
        if (d == null) return 0.0;
        // 20 ticks == 1 second
        return (double) d[1] / ((double) d[0] / 20.0);
    }

    /**
     * Average FE change per TICK over the last `lookbackTicks` ticks
     * (default 20). Same data as getRateFEPerSecond, divided by 20.
     */
    @LuaFunction
    public final double getRateFEPerTick(Optional<Integer> lookbackTicks) {
        int ticks = clampLookback(lookbackTicks.orElse(20));
        long[] d = ringLookback(ticks);
        if (d == null) return 0.0;
        return (double) d[1] / (double) d[0];
    }

    /**
     * Return the last `lookbackTicks` ring samples as an ordered list
     * of {stored, tick, ts} tables (oldest -> newest). `stored` is FE
     * as a double, `tick` is the MC server tick number the sample
     * was taken at, `ts` is the wall-clock millisecond timestamp
     * (System.currentTimeMillis) at that tick - Lua clients can line
     * this up directly against os.epoch("utc") without maintaining
     * their own tick-to-ms mapping.
     *
     * Returns an empty list if the ring hasn't populated yet (e.g.
     * the peripheral was just instantiated and the first server tick
     * hasn't fired). Clients should treat empty-list as "no data;
     * skip this broadcast" rather than "zero stored".
     *
     * This replaces the need for tick-by-tick polling on the CC
     * side: instead of calling getCachedEnergy() 20 times per second
     * each with its own mainThread-or-not latency, the collector can
     * fetch the whole window of samples in a single call right
     * before it broadcasts. No tick jitter, no cold-start race.
     */
    @LuaFunction
    public final List<Map<String, Object>> getStoredHistory(Optional<Integer> lookbackTicks) {
        int ticks = clampLookback(lookbackTicks.orElse(20));
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (ringLock) {
            if (ringCount < 1) return out;
            int available = Math.min(ticks, ringCount);
            for (int i = available - 1; i >= 0; i--) {
                int idx = (ringHead - 1 - i + RING_SIZE) % RING_SIZE;
                Map<String, Object> entry = new HashMap<>();
                entry.put("stored", (double) ringStored[idx]);
                entry.put("tick",   (double) ringTick[idx]);
                entry.put("ts",     (double) ringTsMs[idx]);
                out.add(entry);
            }
        }
        return out;
    }

    private static int clampLookback(int ticks) {
        if (ticks < 1) return 1;
        if (ticks > RING_SIZE) return RING_SIZE;
        return ticks;
    }

    // ─── Network-wide cell enumeration ────────────────────────────────────

    /**
     * @return count of all AppliedFlux FE cells currently present in any
     * drive attached to this accessor's AE2 grid. 0 if the grid is offline
     * or no recognised drives are connected.
     */
    @LuaFunction(mainThread = true)
    public final int getNetworkFluxCellCount() {
        int[] count = { 0 };
        forEachCellInGrid((slot, stack, be) -> count[0]++);
        return count[0];
    }

    /**
     * @return a list describing every FE cell in every drive on this grid.
     * Each entry has the same shape as {@link FluxDrivePeripheral#getFluxCell(int)}
     * plus two extra keys locating the drive:
     *
     *   drivePos   — table {{x, y, z}} (block position of the drive)
     *   driveType  — string, e.g. "ae2:drive" or "extendedae:ex_drive"
     */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> listNetworkFluxCells() {
        List<Map<String, Object>> out = new ArrayList<>();
        forEachCellInGrid((slot, stack, be) -> {
            Map<String, Object> entry = FluxDrivePeripheral.describeCell(slot, stack);
            BlockPos pos = be.getBlockPos();
            Map<String, Integer> xyz = new HashMap<>();
            xyz.put("x", pos.getX());
            xyz.put("y", pos.getY());
            xyz.put("z", pos.getZ());
            entry.put("drivePos", xyz);
            BlockEntityType<?> type = be.getType();
            var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            entry.put("driveType", id == null ? "?" : id.toString());
            out.add(entry);
        });
        return out;
    }

    // ─── internals ────────────────────────────────────────────────────────

    @Nullable
    private IStorageService storage() {
        IGridNode node = host.getActionableNode();
        if (node == null) return null;
        IGrid grid = node.getGrid();
        return grid == null ? null : grid.getStorageService();
    }

    @FunctionalInterface
    private interface CellVisitor {
        void visit(int slot, ItemStack stack, BlockEntity driveBe);
    }

    /**
     * Walk every node on the AE2 grid; for each node whose owner is a drive
     * block entity we recognise, enumerate its slots via the standard
     * IItemHandler capability and call {@code visitor} once per FE cell.
     */
    private void forEachCellInGrid(CellVisitor visitor) {
        IGridNode node = host.getActionableNode();
        if (node == null) return;
        IGrid grid = node.getGrid();
        if (grid == null) return;

        Set<BlockEntityType<?>> driveTypes = new HashSet<>(AppFluxBridge.driveBlockEntityTypes());
        if (driveTypes.isEmpty()) return;

        for (IGridNode n : grid.getNodes()) {
            Object owner = n.getOwner();
            if (!(owner instanceof BlockEntity be)) continue;
            if (!driveTypes.contains(be.getType())) continue;

            Level level = be.getLevel();
            if (level == null) continue;
            IItemHandler inv = level.getCapability(
                Capabilities.ItemHandler.BLOCK,
                be.getBlockPos(),
                be.getBlockState(),
                be,
                null
            );
            if (inv == null) continue;

            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (AppFluxBridge.isFluxCell(stack)) {
                    visitor.visit(i + 1, stack, be);
                }
            }
        }
    }
}
