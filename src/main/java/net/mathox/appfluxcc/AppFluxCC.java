package net.mathox.appfluxcc;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Companion mod entry point. Server-only: adds long-precision ComputerCraft
 * peripheral methods to AppliedFlux's flux_accessor block so a CC computer can
 * read an AE2 network's stored FE beyond the int32 ceiling.
 *
 * All runtime behavior lives in {@link PeripheralCapabilityHandler}.
 */
@Mod(AppFluxCC.MODID)
public class AppFluxCC {

    public static final String MODID = "appflux_cc";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AppFluxCC(IEventBus modBus) {
        LOGGER.info("[appflux_cc] loading: reflective bridge to AppliedFlux long-FE readings");
        modBus.register(PeripheralCapabilityHandler.class);
    }
}
