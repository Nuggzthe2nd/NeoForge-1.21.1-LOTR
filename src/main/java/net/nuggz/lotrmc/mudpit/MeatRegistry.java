package net.nuggz.lotrmc.mudpit;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines biomass values for meat items thrown into mudpits.
 *
 * Two tiers:
 *
 * SOFT WHITELIST — used for basic/unseeded orc spawning.
 *   Named vanilla meats have defined values.
 *   Any item tagged forge:foods/meat but not on the list defaults to 1 biomass.
 *
 * STRICT WHITELIST — used per seed type for advanced creatures.
 *   Only exact items defined in that seed's whitelist count.
 *   Everything else is ignored entirely, including other meats.
 *   Each MudpitSeedItem defines its own strict whitelist.
 *
 * To add a new meat value: add it to SOFT_WHITELIST below.
 * To add a strict whitelist for a new creature type: define it in
 * the relevant MudpitSeedItem subclass and pass it to getBiomassStrict().
 */
public class MeatRegistry {

    /**
     * Soft whitelist — vanilla meat biomass values.
     * Adjust these numbers to tune progression pacing.
     */
    private static final Map<ResourceLocation, Integer> SOFT_WHITELIST = new HashMap<>();

    static {
        // Weak meats — only usable for basic orcs
        SOFT_WHITELIST.put(Items.ROTTEN_FLESH.builtInRegistryHolder()
                .key().location(),  1);
        SOFT_WHITELIST.put(Items.CHICKEN.builtInRegistryHolder()
                .key().location(),  2);
        SOFT_WHITELIST.put(Items.RABBIT.builtInRegistryHolder()
                .key().location(),  2);

        // Standard meats
        SOFT_WHITELIST.put(Items.PORKCHOP.builtInRegistryHolder()
                .key().location(),  4);
        SOFT_WHITELIST.put(Items.BEEF.builtInRegistryHolder()
                .key().location(),  5);
        SOFT_WHITELIST.put(Items.MUTTON.builtInRegistryHolder()
                .key().location(),  4);
        SOFT_WHITELIST.put(Items.COD.builtInRegistryHolder()
                .key().location(),  2);
        SOFT_WHITELIST.put(Items.SALMON.builtInRegistryHolder()
                .key().location(),  2);

        // Cooked variants — reward cooking
        SOFT_WHITELIST.put(Items.COOKED_CHICKEN.builtInRegistryHolder()
                .key().location(),  3);
        SOFT_WHITELIST.put(Items.COOKED_PORKCHOP.builtInRegistryHolder()
                .key().location(),  6);
        SOFT_WHITELIST.put(Items.COOKED_BEEF.builtInRegistryHolder()
                .key().location(),  8);
        SOFT_WHITELIST.put(Items.COOKED_MUTTON.builtInRegistryHolder()
                .key().location(),  6);
        SOFT_WHITELIST.put(Items.COOKED_RABBIT.builtInRegistryHolder()
                .key().location(),  3);
        SOFT_WHITELIST.put(Items.COOKED_COD.builtInRegistryHolder()
                .key().location(),  3);
        SOFT_WHITELIST.put(Items.COOKED_SALMON.builtInRegistryHolder()
                .key().location(),  3);
    }

    // Default biomass for any forge:foods/meat tagged item not on the soft whitelist
    private static final int DEFAULT_MEAT_BIOMASS = 1;

    // -------------------------------------------------------------------------
    // Soft whitelist query (basic orcs)
    // -------------------------------------------------------------------------

    /**
     * Returns the biomass value of this item under the soft whitelist rules.
     * Returns 0 if the item is not meat at all (should not be accepted).
     *
     * Callers should check isMeat() first if they want to distinguish
     * "not meat" from "meat with 0 value" — though currently all meats
     * have value >= 1.
     */
    public static int getBiomassBasic(ItemStack stack) {
        if (stack.isEmpty()) return 0;

        ResourceLocation id = stack.getItem().builtInRegistryHolder().key().location();

        // Named value takes priority
        if (SOFT_WHITELIST.containsKey(id)) {
            return SOFT_WHITELIST.get(id);
        }

        // Fall back to default if tagged as meat
        if (isMeat(stack)) return DEFAULT_MEAT_BIOMASS;

        return 0; // not meat
    }

    /**
     * Returns true if this item counts as meat under the soft whitelist —
     * either explicitly listed or tagged forge:foods/meat.
     */
    public static boolean isMeat(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = stack.getItem().builtInRegistryHolder().key().location();
        return SOFT_WHITELIST.containsKey(id) || stack.is(net.minecraft.tags.ItemTags.MEAT);
    }

    // -------------------------------------------------------------------------
    // Strict whitelist query (advanced creatures)
    // -------------------------------------------------------------------------

    /**
     * Returns the biomass value of this item under a strict whitelist.
     * strictWhitelist maps ResourceLocation → biomass value.
     * Returns 0 if the item is not on the strict list (should be ignored).
     */
    public static int getBiomassStrict(ItemStack stack,
                                       Map<ResourceLocation, Integer> strictWhitelist) {
        if (stack.isEmpty()) return 0;
        ResourceLocation id = stack.getItem().builtInRegistryHolder().key().location();
        return strictWhitelist.getOrDefault(id, 0);
    }

    /**
     * Convenience: returns true if this item appears on the given strict whitelist.
     */
    public static boolean isOnStrictList(ItemStack stack,
                                         Map<ResourceLocation, Integer> strictWhitelist) {
        return getBiomassStrict(stack, strictWhitelist) > 0;
    }

    // -------------------------------------------------------------------------
    // Weak meat check
    // -------------------------------------------------------------------------

    /**
     * Returns true if this meat is "weak" — rotten flesh, chicken, rabbit, fish.
     * Used to enforce that weak meats cannot feed advanced creature seeds.
     * Seeds can also define their own strict whitelist instead of using this.
     */
    public static boolean isWeakMeat(ItemStack stack) {
        ResourceLocation id = stack.getItem().builtInRegistryHolder().key().location();
        return id.equals(Items.ROTTEN_FLESH.builtInRegistryHolder().key().location())
                || id.equals(Items.CHICKEN.builtInRegistryHolder().key().location())
                || id.equals(Items.COOKED_CHICKEN.builtInRegistryHolder().key().location())
                || id.equals(Items.RABBIT.builtInRegistryHolder().key().location())
                || id.equals(Items.COOKED_RABBIT.builtInRegistryHolder().key().location())
                || id.equals(Items.COD.builtInRegistryHolder().key().location())
                || id.equals(Items.COOKED_COD.builtInRegistryHolder().key().location())
                || id.equals(Items.SALMON.builtInRegistryHolder().key().location())
                || id.equals(Items.COOKED_SALMON.builtInRegistryHolder().key().location());
    }
}