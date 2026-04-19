package net.mathox.appfluxcc;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ComputerCraft peripheral attached to any AE2-style drive block (ae2:drive,
 * extendedae:ex_drive, …). Reports which slots contain AppliedFlux FE cells
 * and how full each is, at long precision, by reading the cell item's NBT
 * directly and its capacity via the IFluxCell API.
 *
 * Non-FE-cell slots are ignored. Works purely via the drive's own IItemHandler
 * capability — we don't need drive-mod-specific APIs.
 */
public final class FluxDrivePeripheral implements IPeripheral {

    private final BlockEntity drive;

    public FluxDrivePeripheral(BlockEntity drive) {
        this.drive = drive;
    }

    @Override
    public String getType() {
        return "flux_drive";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other
            || (other instanceof FluxDrivePeripheral p && p.drive == this.drive);
    }

    // ─── Lua API ──────────────────────────────────────────────────────────

    /** @return total number of cell slots on this drive (not just FE cells). */
    @LuaFunction(mainThread = true)
    public final int getSlotCount() {
        IItemHandler inv = inventory();
        return inv == null ? 0 : inv.getSlots();
    }

    /** @return number of slots currently occupied by an AppliedFlux FE cell. */
    @LuaFunction(mainThread = true)
    public final int getFluxCellCount() {
        IItemHandler inv = inventory();
        if (inv == null) return 0;
        int count = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            if (AppFluxBridge.isFluxCell(inv.getStackInSlot(i))) count++;
        }
        return count;
    }

    /**
     * @return a table describing the FE cell in {@code slot} (1-based), or nil
     * if the slot is empty or not an FE cell.
     *
     * Table fields:
     *   slot          — 1-based slot index
     *   name          — item registry id, e.g. "appflux:fe_256m_cell"
     *   stored        — stored FE as Lua number (double — lossless up to 9 PFE)
     *   storedString  — stored FE as exact decimal digits
     *   capacity      — total FE capacity as Lua number
     *   capacityString — total FE capacity as exact decimal digits
     *   fillPercent   — stored/capacity × 100, 0 if capacity == 0
     *   full          — true iff stored == capacity
     *   empty         — true iff stored == 0
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getFluxCell(int slot) {
        IItemHandler inv = inventory();
        if (inv == null) return null;
        int idx = slot - 1;
        if (idx < 0 || idx >= inv.getSlots()) return null;
        ItemStack stack = inv.getStackInSlot(idx);
        if (!AppFluxBridge.isFluxCell(stack)) return null;
        return describeCell(slot, stack);
    }

    /**
     * @return a list of table describing every FE cell present on this drive.
     * Each entry has the same shape as {@link #getFluxCell(int)}.
     */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> listFluxCells() {
        IItemHandler inv = inventory();
        List<Map<String, Object>> out = new ArrayList<>();
        if (inv == null) return out;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (AppFluxBridge.isFluxCell(stack)) {
                out.add(describeCell(i + 1, stack));
            }
        }
        return out;
    }

    // ─── internals ────────────────────────────────────────────────────────

    /**
     * Shape one cell for the Lua API. Public+static so {@link FluxAccessorPeripheral}'s
     * network-wide enumeration reuses the exact same table shape.
     */
    public static Map<String, Object> describeCell(int slot, ItemStack stack) {
        long stored = AppFluxBridge.storedFE(stack);
        long capacity = AppFluxBridge.capacityFE(stack);
        String name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        Map<String, Object> entry = new HashMap<>();
        entry.put("slot", slot);
        entry.put("name", name);
        entry.put("stored", (double) stored);
        entry.put("storedString", Long.toString(stored));
        entry.put("capacity", (double) capacity);
        entry.put("capacityString", Long.toString(capacity));
        entry.put("fillPercent", capacity == 0 ? 0.0 : (stored * 100.0 / capacity));
        entry.put("full", capacity > 0 && stored >= capacity);
        entry.put("empty", stored == 0);
        return entry;
    }

    @Nullable
    private IItemHandler inventory() {
        Level level = drive.getLevel();
        if (level == null) return null;
        BlockPos pos = drive.getBlockPos();
        return level.getCapability(
            Capabilities.ItemHandler.BLOCK,
            pos,
            drive.getBlockState(),
            drive,
            null
        );
    }
}
