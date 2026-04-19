package net.mathox.appfluxcc;

import appeng.api.networking.security.IActionHost;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Registers the ComputerCraft peripheral capability on three targets:
 *
 *   - {@code appflux:flux_accessor} — full-block flux accessor, direct
 *   - {@code ae2:cable_bus} — cable-part flux accessor, side-dispatched:
 *     only returns a peripheral when a {@code PartFluxAccessor} is on the
 *     queried face; null otherwise (lets other providers fall through)
 *   - {@code ae2:drive} and {@code extendedae:ex_drive} — AE2 drives,
 *     exposes a {@code flux_drive} peripheral for per-slot FE cell info
 *
 * Runs once at mod-bus setup via {@code RegisterCapabilitiesEvent}.
 */
public final class PeripheralCapabilityHandler {

    private PeripheralCapabilityHandler() {}

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        registerFullBlock(event);
        registerCableBusPart(event);
        registerDrives(event);
    }

    private static void registerFullBlock(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> type = AppFluxBridge.fluxAccessorBlockEntityType();
        if (type == null) {
            AppFluxCC.LOGGER.warn("[appflux_cc] appflux:flux_accessor BE type not found — full-block peripheral not registered");
            return;
        }
        event.registerBlockEntity(
            PeripheralCapability.get(),
            type,
            (be, side) -> {
                if (be instanceof IActionHost host) {
                    return new FluxAccessorPeripheral(host);
                }
                return null;
            }
        );
        AppFluxCC.LOGGER.info("[appflux_cc] registered peripheral capability on appflux:flux_accessor");
    }

    private static void registerCableBusPart(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> cableBusType = AppFluxBridge.cableBusBlockEntityType();
        if (cableBusType == null) {
            AppFluxCC.LOGGER.warn("[appflux_cc] ae2:cable_bus BE type not found — cable-part peripheral not registered");
            return;
        }
        event.registerBlockEntity(
            PeripheralCapability.get(),
            cableBusType,
            PeripheralCapabilityHandler::resolveCablePart
        );
        AppFluxCC.LOGGER.info("[appflux_cc] registered peripheral capability on ae2:cable_bus (part dispatch)");
    }

    /**
     * Given a cable bus BE and a side, return a peripheral only if a flux
     * accessor part is on that side — or, when side is null, any side.
     */
    private static FluxAccessorPeripheral resolveCablePart(net.minecraft.world.level.block.entity.BlockEntity be, Direction side) {
        Class<?> partClass = AppFluxBridge.partFluxAccessorClass();
        if (partClass == null) return null;
        if (!(be instanceof IPartHost host)) return null;

        if (side != null) {
            IPart part = host.getPart(side);
            if (part != null && partClass.isInstance(part) && part instanceof IActionHost ah) {
                return new FluxAccessorPeripheral(ah);
            }
            return null;
        }

        // Null side: scan all 6 faces, return the first matching part.
        for (Direction d : Direction.values()) {
            IPart part = host.getPart(d);
            if (part != null && partClass.isInstance(part) && part instanceof IActionHost ah) {
                return new FluxAccessorPeripheral(ah);
            }
        }
        return null;
    }

    /**
     * Attach the FE-cell-reader peripheral to every drive block-entity type
     * we know about that's actually registered. Silently skips types that
     * aren't present (e.g. ExtendedAE not installed → we only bind to ae2:drive).
     */
    private static void registerDrives(RegisterCapabilitiesEvent event) {
        var types = AppFluxBridge.driveBlockEntityTypes();
        if (types.isEmpty()) {
            AppFluxCC.LOGGER.warn("[appflux_cc] no drive BE types found — flux_drive peripheral not registered");
            return;
        }
        if (AppFluxBridge.fluxCellInterface() == null) {
            AppFluxCC.LOGGER.warn("[appflux_cc] IFluxCell interface missing — flux_drive peripheral would be useless, skipping");
            return;
        }
        for (BlockEntityType<?> type : types) {
            event.registerBlockEntity(
                PeripheralCapability.get(),
                type,
                (be, side) -> new FluxDrivePeripheral(be)
            );
        }
        AppFluxCC.LOGGER.info("[appflux_cc] registered flux_drive peripheral on {} drive BE type(s)", types.size());
    }
}
