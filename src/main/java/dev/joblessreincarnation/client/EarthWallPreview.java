package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.magic.EarthWallSpell;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Geometry preview only; the server decides permissions and revalidates placement on release. */
public final class EarthWallPreview {
    public static EarthWallSpell.Plan currentPlan() {
        if (DismissInputState.blocksUse()) return null;
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || !player.isAlive() || player.isSpectator() || minecraft.screen != null
                || minecraft.getOverlay() != null || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return null;
        SpellCatalog active = player.isUsingItem() ? SpellCatalog.active(player) : null;
        if ((active == null ? SpellCatalog.selected(player) : active) != SpellCatalog.EARTH_WALL
                || !SpellCatalog.EARTH_WALL.knows(player)) return null;
        return EarthWallSpell.plan(player);
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) return;
        EarthWallSpell.Plan plan = currentPlan();
        var dismissPositions = DismissInputState.positions();
        boolean dismissing = !dismissPositions.isEmpty();
        if (!dismissing && (plan == null || plan.positions().isEmpty())) return;
        if (minecraft.level == null || minecraft.screen != null || minecraft.getOverlay() != null) return;
        var camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = minecraft.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        float red = dismissing ? 0.96F : plan.valid() ? 0.76F : 1.0F;
        float green = dismissing ? 0.62F : plan.valid() ? 0.76F : 0.25F;
        float blue = dismissing ? 0.20F : plan.valid() ? 0.38F : 0.25F;
        for (var pos : dismissing ? dismissPositions : plan.positions()) {
            if (dismissing && !minecraft.level.getBlockState(pos).is(ModContent.EARTH_WALL.get())) continue;
            LevelRenderer.renderLineBox(pose, lines, new AABB(pos).inflate(0.002), red, green, blue, 0.7F);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
    private EarthWallPreview() {}
}
