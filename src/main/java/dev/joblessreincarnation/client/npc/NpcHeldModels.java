package dev.joblessreincarnation.client.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Props NPCs carry, side-loaded as standalone models. Roxy's staff is not an item: nothing for it is registered or
 * obtainable. Paul's practice sword is the practice wooden sword's own item model, so he holds what the player holds.
 */
public final class NpcHeldModels {
    /** Roxy's own staff (white crook, blue gem), {@code assets/jobless_reincarnation/models/npc/roxy_staff.json}. */
    public static final ModelResourceLocation ROXY_STAFF =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "npc/roxy_staff"));

    /** The practice wooden sword's item model, {@code assets/jobless_reincarnation/models/item/practice_wooden_sword.json}. */
    public static final ModelResourceLocation PAUL_PRACTICE_SWORD =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "item/practice_wooden_sword"));

    public static void register(ModelEvent.RegisterAdditional event) {
        event.register(ROXY_STAFF);
        event.register(PAUL_PRACTICE_SWORD);
    }

    private NpcHeldModels() {}
}
