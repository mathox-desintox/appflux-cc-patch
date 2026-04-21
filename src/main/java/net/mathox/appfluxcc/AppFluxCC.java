package net.mathox.appfluxcc;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Companion mod entry point. Server-only: adds long-precision ComputerCraft
 * peripheral methods to AppliedFlux's flux_accessor block so a CC computer can
 * read an AE2 network's stored FE beyond the int32 ceiling.
 *
 * Peripheral capability registration lives in {@link PeripheralCapabilityHandler}.
 * A per-tick snapshotting loop registered here populates
 * {@link FluxAccessorPeripheral}'s cache so Lua can read live FE numbers and
 * rate deltas without the 50ms round-trip that {@code mainThread=true} incurs.
 */
@Mod(AppFluxCC.MODID)
public class AppFluxCC {

    public static final String MODID = "appflux_cc";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AppFluxCC(IEventBus modBus) {
        LOGGER.info("[appflux_cc] loading: reflective bridge to AppliedFlux long-FE readings");
        modBus.register(PeripheralCapabilityHandler.class);

        // Fires every server tick on the main thread. We snapshot each
        // live accessor's grid state (stored, capacity, online) into
        // its per-instance cache + ring buffer so Lua can then read
        // those values without blocking. Using Post so any flux
        // movement during the tick has already settled.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            long tick = event.getServer().getTickCount();
            FluxAccessorPeripheral.tickAll(tick);
        });
    }
}
