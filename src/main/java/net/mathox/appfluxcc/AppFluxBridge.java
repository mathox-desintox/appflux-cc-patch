package net.mathox.appfluxcc;

import appeng.api.stacks.AEKey;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * Reflective bridge to AppliedFlux internals. We keep AppliedFlux off the
 * compile classpath because it isn't published to a Maven repo we can target;
 * instead we look up its classes at runtime via Class.forName.
 *
 * What we need from AppliedFlux at runtime:
 *   1. {@code FluxKey.of(EnergyType.FE)} — AEKey used to query the AE2 storage
 *      service's cached inventory (for network-total FE).
 *   2. {@link BlockEntityType} registered under {@code appflux:flux_accessor}
 *      (the full block) — target for the peripheral capability.
 *   3. {@code PartFluxAccessor} class — for {@code isInstance} checks when
 *      dispatching the peripheral on an {@code ae2:cable_bus}.
 *   4. {@code IFluxCell} interface (from the stable {@code .api} package) —
 *      for identifying FE cell items and reading their per-cell capacity.
 *   5. {@code AFSingletons.FE_ENERGY} — the DataComponentType that 1.21+
 *      AppliedFlux uses to store per-cell stored FE on cell item stacks.
 *   6. {@code FluxKeyType.TYPE.getAmountPerByte()} — FE-per-byte ratio used
 *      to convert a cell's byte capacity to FE capacity (1.21 removed the
 *      {@code IFluxCell.getKeyType()} shortcut).
 *
 * AE2's public API types ({@link AEKey}, {@link BlockEntityType}) and vanilla
 * types ({@link DataComponentType}, {@link ItemStack}, …) are used directly.
 * Only AppliedFlux classes are reflected.
 */
public final class AppFluxBridge {

    private static final String FLUX_KEY_CLASS = "com.glodblock.github.appflux.common.me.key.FluxKey";
    private static final String ENERGY_TYPE_CLASS = "com.glodblock.github.appflux.common.me.key.type.EnergyType";
    private static final String FLUX_KEY_TYPE_CLASS = "com.glodblock.github.appflux.common.me.key.type.FluxKeyType";
    private static final String PART_FLUX_ACCESSOR_CLASS = "com.glodblock.github.appflux.common.parts.PartFluxAccessor";
    private static final String FLUX_CELL_API = "com.glodblock.github.appflux.api.IFluxCell";
    private static final String AF_SINGLETONS_CLASS = "com.glodblock.github.appflux.common.AFSingletons";

    private static final ResourceLocation FLUX_ACCESSOR_BE_ID = ResourceLocation.fromNamespaceAndPath("appflux", "flux_accessor");
    private static final ResourceLocation CABLE_BUS_BE_ID = ResourceLocation.fromNamespaceAndPath("ae2", "cable_bus");

    private static final List<ResourceLocation> DRIVE_BE_IDS = List.of(
        ResourceLocation.fromNamespaceAndPath("ae2", "drive"),
        ResourceLocation.fromNamespaceAndPath("extendedae", "ex_drive")
    );

    // One-shot resolution flags + caches.
    private static boolean keyResolved = false;
    private static AEKey feKey = null;
    private static boolean partClassResolved = false;
    private static Class<?> partFluxAccessorClass = null;
    private static boolean fluxCellInterfaceResolved = false;
    private static Class<?> fluxCellInterface = null;
    private static boolean fluxKeyTypeClassResolved = false;
    private static Class<?> fluxKeyTypeClass = null;
    private static boolean feEnergyComponentResolved = false;
    private static DataComponentType<Long> feEnergyComponent = null;

    private AppFluxBridge() {}

    // ─── FluxKey(FE) — for network total via IStorageService ──────────────

    /** @return FluxKey for EnergyType.FE, or null if AppliedFlux isn't loaded. */
    public static AEKey feKey() {
        if (keyResolved) return feKey;
        keyResolved = true;
        try {
            Class<?> energyTypeClass = Class.forName(ENERGY_TYPE_CLASS);
            Object feValue = null;
            for (Object constant : energyTypeClass.getEnumConstants()) {
                if ("FE".equals(((Enum<?>) constant).name())) {
                    feValue = constant;
                    break;
                }
            }
            if (feValue == null) {
                AppFluxCC.LOGGER.error("[appflux_cc] EnergyType.FE enum constant not found");
                return null;
            }
            Class<?> fluxKeyClass = Class.forName(FLUX_KEY_CLASS);
            Object key = fluxKeyClass.getMethod("of", energyTypeClass).invoke(null, feValue);
            if (!(key instanceof AEKey aek)) {
                AppFluxCC.LOGGER.error("[appflux_cc] FluxKey.of did not return an AEKey subtype");
                return null;
            }
            feKey = aek;
            AppFluxCC.LOGGER.info("[appflux_cc] resolved FluxKey(FE) via reflection");
        } catch (ClassNotFoundException e) {
            AppFluxCC.LOGGER.warn("[appflux_cc] AppliedFlux classes not on classpath; peripheral will no-op");
        } catch (ReflectiveOperationException e) {
            AppFluxCC.LOGGER.error("[appflux_cc] reflective lookup of FluxKey failed", e);
        }
        return feKey;
    }

    // ─── BE types + part class for capability registration ────────────────

    public static BlockEntityType<?> fluxAccessorBlockEntityType() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.get(FLUX_ACCESSOR_BE_ID);
    }

    public static BlockEntityType<?> cableBusBlockEntityType() {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.get(CABLE_BUS_BE_ID);
    }

    public static List<BlockEntityType<?>> driveBlockEntityTypes() {
        List<BlockEntityType<?>> out = new ArrayList<>(DRIVE_BE_IDS.size());
        for (ResourceLocation id : DRIVE_BE_IDS) {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
            if (type != null) out.add(type);
        }
        return out;
    }

    public static Class<?> partFluxAccessorClass() {
        if (partClassResolved) return partFluxAccessorClass;
        partClassResolved = true;
        try {
            partFluxAccessorClass = Class.forName(PART_FLUX_ACCESSOR_CLASS);
        } catch (ClassNotFoundException e) {
            AppFluxCC.LOGGER.warn("[appflux_cc] PartFluxAccessor class not found; cable-part support disabled");
        }
        return partFluxAccessorClass;
    }

    public static Class<?> fluxCellInterface() {
        if (fluxCellInterfaceResolved) return fluxCellInterface;
        fluxCellInterfaceResolved = true;
        try {
            fluxCellInterface = Class.forName(FLUX_CELL_API);
        } catch (ClassNotFoundException e) {
            AppFluxCC.LOGGER.warn("[appflux_cc] IFluxCell interface not found; drive cell reader disabled");
        }
        return fluxCellInterface;
    }

    private static Class<?> fluxKeyTypeClass() {
        if (fluxKeyTypeClassResolved) return fluxKeyTypeClass;
        fluxKeyTypeClassResolved = true;
        try {
            fluxKeyTypeClass = Class.forName(FLUX_KEY_TYPE_CLASS);
        } catch (ClassNotFoundException e) {
            AppFluxCC.LOGGER.warn("[appflux_cc] FluxKeyType class not found; cell capacity will be 0");
        }
        return fluxKeyTypeClass;
    }

    // ─── Cell introspection (per-cell stored/capacity) ────────────────────

    /** @return true if the stack's item implements AppliedFlux's IFluxCell. */
    public static boolean isFluxCell(ItemStack stack) {
        Class<?> cls = fluxCellInterface();
        if (cls == null || stack == null || stack.isEmpty()) return false;
        return cls.isInstance(stack.getItem());
    }

    /**
     * @return stored FE on this cell via the {@code AFSingletons.FE_ENERGY}
     * DataComponent (1.21+ uses data components instead of NBT tags).
     */
    public static long storedFE(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        DataComponentType<Long> component = feEnergyComponent();
        if (component == null) return 0L;
        Long stored = stack.getOrDefault(component, 0L);
        return stored == null ? 0L : stored;
    }

    /**
     * @return capacity of this cell in FE, computed as
     * {@code IFluxCell.getBytes(stack) × FluxKeyType.TYPE.getAmountPerByte()}.
     * The {@code getKeyType()} shortcut on IFluxCell was removed in 1.21,
     * so we go via the key type's static singleton instead.
     */
    public static long capacityFE(ItemStack stack) {
        Class<?> cellIface = fluxCellInterface();
        if (cellIface == null || stack == null || stack.isEmpty() || !cellIface.isInstance(stack.getItem())) {
            return 0L;
        }
        try {
            Object cell = stack.getItem();
            long bytes = (long) cellIface.getMethod("getBytes", ItemStack.class).invoke(cell, stack);
            int amountPerByte = fluxAmountPerByte();
            return bytes * (long) amountPerByte;
        } catch (ReflectiveOperationException e) {
            AppFluxCC.LOGGER.debug("[appflux_cc] capacityFE reflection failed", e);
            return 0L;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static DataComponentType<Long> feEnergyComponent() {
        if (feEnergyComponentResolved) return feEnergyComponent;
        feEnergyComponentResolved = true;
        try {
            Class<?> cls = Class.forName(AF_SINGLETONS_CLASS);
            Object value = cls.getField("FE_ENERGY").get(null);
            feEnergyComponent = (DataComponentType<Long>) value;
            AppFluxCC.LOGGER.info("[appflux_cc] resolved AFSingletons.FE_ENERGY DataComponent");
        } catch (ClassNotFoundException e) {
            AppFluxCC.LOGGER.warn("[appflux_cc] AFSingletons class not found; per-cell FE read disabled");
        } catch (ReflectiveOperationException e) {
            AppFluxCC.LOGGER.error("[appflux_cc] failed to resolve AFSingletons.FE_ENERGY", e);
        }
        return feEnergyComponent;
    }

    /**
     * Not cached: {@code FluxKeyType.getAmountPerByte()} reads live config, which
     * a server admin can change without a restart. One reflective call per
     * cell read is trivial overhead.
     */
    private static int fluxAmountPerByte() {
        Class<?> cls = fluxKeyTypeClass();
        if (cls == null) return 0;
        try {
            Object typeInstance = cls.getField("TYPE").get(null);
            return (int) typeInstance.getClass().getMethod("getAmountPerByte").invoke(typeInstance);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }
}
