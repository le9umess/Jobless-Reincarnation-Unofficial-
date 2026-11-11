package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Registers the development-only display entity. Only present when the gameTest source set is loaded. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class NpcDisplayRegistry {
    public static final ResourceLocation ROXY_ID = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "dev_roxy_display");
    private static EntityType<RoxyDisplayEntity> roxy;

    public static EntityType<RoxyDisplayEntity> roxy() { return roxy; }

    /**
     * Adds one temporary display Roxy for a development fixture at {@code position}, facing {@code yaw}
     * (0 = south). It keeps the no-AI, invulnerable, never-saved display behaviour and starts on the idle clip.
     * The caller checks the site first and later discards exactly the returned entity; nothing else is touched.
     *
     * @return the entity now in {@code level}, or null if the type is missing or the level did not accept it
     */
    public static RoxyDisplayEntity spawnRoxy(ServerLevel level, Vec3 position, float yaw) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("spawnRoxy must run on the server thread");
        RoxyDisplayEntity npc = roxy == null ? null : roxy.create(level);
        if (npc == null) return null;
        npc.moveTo(position.x, position.y, position.z, yaw, 0.0F);
        npc.setYHeadRot(yaw); npc.setYBodyRot(yaw);
        return level.addFreshEntity(npc) ? npc : null;
    }

    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, helper -> {
            // Body is about 2 blocks; the hat adds the rest. noSave keeps it out of the test world.
            roxy = EntityType.Builder.<RoxyDisplayEntity>of(RoxyDisplayEntity::new, MobCategory.MISC)
                    .sized(0.6F, 2.0F).clientTrackingRange(8).noSave().build(ROXY_ID.toString());
            helper.register(ROXY_ID, roxy);
        });
    }
    @SubscribeEvent public static void attributes(EntityAttributeCreationEvent event) {
        event.put(roxy, RoxyDisplayEntity.attributes().build());
    }
    private NpcDisplayRegistry() {}
}
