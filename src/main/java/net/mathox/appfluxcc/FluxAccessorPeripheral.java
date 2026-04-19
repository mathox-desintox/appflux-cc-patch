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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public FluxAccessorPeripheral(IActionHost host) {
        this.host = host;
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
