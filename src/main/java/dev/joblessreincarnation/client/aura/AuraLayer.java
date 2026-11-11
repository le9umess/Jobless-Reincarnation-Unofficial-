package dev.joblessreincarnation.client.aura;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.training.AuraVisual;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.FirstPersonRenderer;
import yesman.epicfight.client.renderer.patched.entity.PPlayerRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;

/**
 * Battle aura as a glow around the body. Each shell is the body pushed out along its smoothed surface direction and drawn
 * from the inside, so only what stands outside the silhouette shows and the skin stays clean.
 */
public final class AuraLayer<E extends AbstractClientPlayer, T extends AbstractClientPlayerPatch<E>>
        extends PatchedLayer<E, T, PlayerModel<E>, RenderLayer<E, PlayerModel<E>>> {
    private static final ResourceLocation HULL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/entity/aura_hull.png");
    static final float RED = 1.0F, GREEN = 0.82F, BLUE = 0.46F;
    private static final float[] THICKNESS = {0.026F, 0.058F, 0.1F};
    private static final float[] WEIGHTS = {1.0F, 0.7F, 0.42F};
    /** The everyday strength the user chose from the captures: “常驻中档”. */
    private static final float IDLE = 0.6F;
    private static final float GATHER_BEAT_TICKS = 16.0F;
    private static final Map<SkinnedMesh, float[]> SMOOTH_NORMALS = new WeakHashMap<>();

    private final Function<T, HumanoidMesh> meshOf;
    private final boolean firstPerson;

    private AuraLayer(Function<T, HumanoidMesh> meshOf, boolean firstPerson) {
        this.meshOf = meshOf;
        this.firstPerson = firstPerson;
    }

    public static void install() {
        EpicFightClientEventHooks.Registry.MODIFY_PATCHED_ENTITY.registerEvent(event -> {
            if (event.get(net.minecraft.world.entity.EntityType.PLAYER) instanceof PPlayerRenderer player) {
                player.addCustomLayer(new AuraLayer<AbstractClientPlayer, AbstractClientPlayerPatch<AbstractClientPlayer>>(patch -> player.getMeshProvider(patch).get(), false));
            }
        });
        // The first-person renderer is rebuilt with the others, just before this event fires.
        EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(event -> {
            FirstPersonRenderer view = RenderEngine.getInstance().getFirstPersonRenderer();
            if (view != null) view.addCustomLayer(new AuraLayer<LocalPlayer, LocalPlayerPatch>(patch -> view.getMeshProvider(patch).get(), true));
        });
    }

    @Override
    protected void renderLayer(T patch, E player, RenderLayer<E, PlayerModel<E>> layer, PoseStack pose, MultiBufferSource buffers, int light,
            OpenMatrix4f[] joints, float bob, float yawRot, float pitch, float partialTick) {
        AuraVisual cue = AuraVisual.of(player);
        boolean gathering = cue.phase() == AuraVisual.Phase.GATHER;
        if (player.isInvisible() || (cue.phase() == AuraVisual.Phase.OFF && !gathering)) return;
        HumanoidMesh mesh = meshOf.apply(patch);
        Armature armature = patch.getArmature();
        OpenMatrix4f[] skin = new OpenMatrix4f[joints.length];
        for (int i = 0; i < joints.length; i++) skin[i] = OpenMatrix4f.mul(joints[i], armature.searchJointById(i).getToOrigin(), null);

        float time = player.tickCount + partialTick;
        if (!firstPerson) sword(patch, player, cue, pose, buffers, light, joints, time, partialTick);
        if (gathering) {
            // From the feet up through the body to the sword arm, as the force travels in 227 P140.
            float age = player.level().getGameTime() - cue.startedAt() + partialTick;
            float beat = (age % GATHER_BEAT_TICKS) / GATHER_BEAT_TICKS;
            if (!firstPerson) {
                shells(mesh, player, pose, buffers, light, skin, time, List.of(mesh.leftLeg, mesh.rightLeg), 0.25F + 0.9F * pulse(beat, 0.18F), 0.0F, false);
                shells(mesh, player, pose, buffers, light, skin, time, List.of(mesh.torso, mesh.head, mesh.leftArm), 0.25F + 0.9F * pulse(beat, 0.46F), 0.0F, false);
            }
            // Each tier reached lights the sword arm once more, and the whole body carries a little more as it fills.
            float tier = AuraCues.gatherTier(player), flash = AuraCues.tierFlash(player, partialTick);
            shells(mesh, player, pose, buffers, light, skin, time, List.of(mesh.rightArm), 0.25F + 0.12F * tier + 1.1F * pulse(beat, 0.74F) + 0.9F * flash, 0.03F * flash, false);
            return;
        }
        float flash = cue.flash(player.level().getGameTime(), partialTick);
        float strength;
        float swell;
        if (cue.phase() == AuraVisual.Phase.BREAK) {
            // Bursts outward and goes out; nothing is left until the server says it can gather again.
            if (flash <= 0.0F) return;
            strength = flash * 1.5F;
            swell = (1.0F - flash) * 0.16F;
        } else {
            strength = IDLE + flash * 1.2F;
            swell = flash * 0.05F;
        }
        // In first person only the arms carry it: the torso shell would wrap the camera.
        List<SkinnedMesh.SkinnedMeshPart> parts = firstPerson
                ? List.of(mesh.leftArm, mesh.rightArm)
                : List.of(mesh.head, mesh.torso, mesh.leftArm, mesh.rightArm, mesh.leftLeg, mesh.rightLeg);
        shells(mesh, player, pose, buffers, light, skin, time, parts, strength, swell, !firstPerson);
    }

    private static <E extends AbstractClientPlayer, T extends AbstractClientPlayerPatch<E>> void sword(T patch, E player, AuraVisual cue, PoseStack pose, MultiBufferSource buffers, int light,
            OpenMatrix4f[] joints, float time, float partialTick) {
        float level = AuraBlade.level(cue, player.level().getGameTime(), partialTick);
        if (cue.phase() == AuraVisual.Phase.GATHER) level += 0.2F * AuraCues.gatherTier(player) + 0.9F * AuraCues.tierFlash(player, partialTick);
        ItemStack held = player.getMainHandItem();
        if (level <= 0.0F || held.isEmpty()) return;
        AuraBlade.Shape shape = new AuraBlade.Shape();
        RenderEngine.getInstance().getItemRenderer(held).renderItemInHand(held, patch, InteractionHand.MAIN_HAND, joints, shape.collector(), pose, light, partialTick);
        shape.draw(buffers, held, time, level, light);
    }

    static RenderType ribbonType(float u, float v) {
        return Types.ribbon(HULL, u, v);
    }

    static RenderType bladeType(float u, float v) {
        return Types.shell(HULL, u, v, VertexFormat.Mode.QUADS);
    }

    private static float pulse(float beat, float centre) {
        return Math.max(0.0F, 1.0F - Math.abs(beat - centre) / 0.2F);
    }

    private static void shells(HumanoidMesh mesh, AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers, int light, OpenMatrix4f[] joints, float time,
            List<SkinnedMesh.SkinnedMeshPart> parts, float strength, float swell, boolean armor) {
        for (int shell = 0; shell < THICKNESS.length; shell++) {
            RenderType type = Types.shell(HULL, (time * 0.0011F + shell * 0.37F) % 1.0F, (time * (0.012F + 0.005F * shell) + shell * 0.21F) % 1.0F, VertexFormat.Mode.TRIANGLES);
            VertexConsumer out = buffers.getBuffer(type);
            float level = Math.min(1.0F, strength * WEIGHTS[shell]);
            float thickness = THICKNESS[shell] + swell;
            for (SkinnedMesh.SkinnedMeshPart part : parts) {
                draw(mesh, part, out, pose.last(), joints, thickness, time, level, light);
            }
            if (armor) {
                // Armour stands out from the body, so each worn piece carries its own shell.
                for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                    if (!(player.getItemBySlot(slot).getItem() instanceof ArmorItem)) continue;
                    SkinnedMesh worn = mesh.getHumanoidArmorModel(slot).get();
                    for (SkinnedMesh.SkinnedMeshPart part : worn.getAllParts()) {
                        draw(worn, part, out, pose.last(), joints, thickness, time, level, light);
                    }
                }
            }
        }
    }

    private static void draw(SkinnedMesh mesh, SkinnedMesh.SkinnedMeshPart part, VertexConsumer out, PoseStack.Pose pose, OpenMatrix4f[] joints, float thickness, float time, float level, int light) {
        float[] positions = mesh.positions(), uvs = mesh.uvs(), weights = mesh.weights();
        float[] smooth = SMOOTH_NORMALS.computeIfAbsent(mesh, AuraLayer::smoothNormals);
        int[] counts = mesh.affectingJointCounts();
        int[][] jointIndices = mesh.affectingJointIndices(), weightIndices = mesh.affectingWeightIndices();
        Vector4f at = new Vector4f();
        for (VertexBuilder vertex : part.getVertices()) {
            int index = vertex.position;
            float px = positions[index * 3], py = positions[index * 3 + 1], pz = positions[index * 3 + 2];
            float nx = smooth[index * 3], ny = smooth[index * 3 + 1], nz = smooth[index * 3 + 2];
            // The outer edge wavers and tops lean upward, which is what makes it read as rising rather than as a hard outline.
            float waver = 1.0F + 0.3F * (float) Math.sin(time * 0.31F + (px * 7.0F + py * 11.0F + pz * 5.0F));
            float push = thickness * waver;
            float x = px + nx * push, y = py + ny * push + Math.max(0.0F, ny) * thickness * 1.4F * waver, z = pz + nz * push;
            float sx = 0.0F, sy = 0.0F, sz = 0.0F;
            for (int i = 0; i < counts[index]; i++) {
                OpenMatrix4f joint = joints[jointIndices[index][i]];
                float weight = weights[weightIndices[index][i]];
                sx += weight * (joint.m00 * x + joint.m10 * y + joint.m20 * z + joint.m30);
                sy += weight * (joint.m01 * x + joint.m11 * y + joint.m21 * z + joint.m31);
                sz += weight * (joint.m02 * x + joint.m12 * y + joint.m22 * z + joint.m32);
            }
            pose.pose().transform(sx, sy, sz, 1.0F, at);
            out.addVertex(at.x, at.y, at.z).setColor(RED * level, GREEN * level, BLUE * level, 1.0F).setUv(uvs[vertex.uv * 2], uvs[vertex.uv * 2 + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
        }
    }

    /** A box's faces each have their own normal; averaging them where the corners meet gives one direction per point, so the pushed-out shell stays closed. */
    private static float[] smoothNormals(SkinnedMesh mesh) {
        float[] positions = mesh.positions(), normals = mesh.normals();
        Map<Long, float[]> sums = new HashMap<>();
        for (SkinnedMesh.SkinnedMeshPart part : mesh.getAllParts()) {
            for (VertexBuilder vertex : part.getVertices()) {
                float[] sum = sums.computeIfAbsent(cornerKey(positions, vertex.position), key -> new float[3]);
                sum[0] += normals[vertex.normal * 3];
                sum[1] += normals[vertex.normal * 3 + 1];
                sum[2] += normals[vertex.normal * 3 + 2];
            }
        }
        float[] result = new float[positions.length];
        for (int index = 0; index < positions.length / 3; index++) {
            float[] sum = sums.get(cornerKey(positions, index));
            if (sum == null) continue;
            float length = (float) Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
            if (length < 1.0E-4F) continue;
            result[index * 3] = sum[0] / length;
            result[index * 3 + 1] = sum[1] / length;
            result[index * 3 + 2] = sum[2] / length;
        }
        return result;
    }

    private static long cornerKey(float[] positions, int index) {
        long x = Math.round(positions[index * 3] * 2000.0F) & 0x1FFFFF;
        long y = Math.round(positions[index * 3 + 1] * 2000.0F) & 0x1FFFFF;
        long z = Math.round(positions[index * 3 + 2] * 2000.0F) & 0x1FFFFF;
        return x << 42 | y << 21 | z;
    }

    private static final class Types extends RenderType {
        private static final RenderStateShard.CullStateShard INSIDE_ONLY = new RenderStateShard.CullStateShard(true) {
            @Override
            public void setupRenderState() {
                RenderSystem.enableCull();
                GL11.glCullFace(GL11.GL_FRONT);
            }

            @Override
            public void clearRenderState() {
                GL11.glCullFace(GL11.GL_BACK);
            }
        };

        private Types() {
            super("jobless_aura", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.TRIANGLES, 256, false, false, () -> {}, () -> {});
        }

        static RenderType ribbon(ResourceLocation texture, float u, float v) {
            return create("jobless_aura_ribbon", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENERGY_SWIRL_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTexturingState(new OffsetTexturingStateShard(u, v))
                            .setTransparencyState(ADDITIVE_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setWriteMaskState(COLOR_WRITE)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .createCompositeState(false));
        }

        static RenderType shell(ResourceLocation texture, float u, float v, VertexFormat.Mode mode) {
            return create("jobless_aura_shell", DefaultVertexFormat.NEW_ENTITY, mode, 1536, false, true,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENERGY_SWIRL_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTexturingState(new OffsetTexturingStateShard(u, v))
                            .setTransparencyState(ADDITIVE_TRANSPARENCY)
                            .setCullState(INSIDE_ONLY)
                            .setWriteMaskState(COLOR_WRITE)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .createCompositeState(false));
        }
    }
}
