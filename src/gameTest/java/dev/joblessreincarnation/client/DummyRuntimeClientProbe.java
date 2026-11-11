package dev.joblessreincarnation.client;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/** Actual renderer/controller checks on private unregistered props; synthetic frame times are not multiplayer proof. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class DummyRuntimeClientProbe {
    private static final boolean ENABLED = Boolean.getBoolean("jobless_reincarnation.swordDemoPreview");
    private static final ResourceLocation GEO = id("geo/entity/practice_dummy.geo.json");
    private static final ResourceLocation ANIMATIONS = id("animations/entity/practice_dummy.animation.json");
    private static boolean finished;
    private static int waitTicks;

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if ((!GeckoLibCache.getBakedModels().containsKey(GEO)
                || !GeckoLibCache.getBakedAnimations().containsKey(ANIMATIONS)) && ++waitTicks < 120) return;
        finished = true;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("createdAtUtc", Instant.now().toString());
        report.put("scope", "Actual registered renderer matrices + AnimationProcessor and formal resources; private unregistered props and synthetic frame times, not actual network timing");
        List<Map<String, Object>> cases = new ArrayList<>();
        report.put("cases", cases);
        run(cases, "saved_yaw_drives_actual_renderer_north_face", () -> orientation(client));
        run(cases, "hit_at_tick_4_reacts_again_for_another_player", () -> repeatedHit(client, 4, false));
        run(cases, "hit_at_tick_7_reacts_at_completion_boundary", () -> repeatedHit(client, 7, false));
        run(cases, "hit_at_tick_8_with_zero_idle_frame_replays", () -> repeatedHit(client, 8, false));
        run(cases, "hit_at_tick_12_after_idle_replays", () -> repeatedHit(client, 12, true));
        long passed = cases.stream().filter(value -> "PASS".equals(value.get("status"))).count();
        report.put("passed", passed);
        report.put("total", cases.size());
        report.put("status", passed == cases.size() ? "PASS" : "FAILED");
        try {
            Files.writeString(client.gameDirectory.toPath().resolve("dummy-runtime-client-probe.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardCharsets.UTF_8);
        } catch (Exception error) {
            JoblessReincarnation.LOGGER.error("[JR_DUMMY_RUNTIME_PROBE] Failed to write result", error);
        }
        if (passed == cases.size()) JoblessReincarnation.LOGGER.info("[JR_DUMMY_RUNTIME_PROBE] PASS cases={}/{}", passed, cases.size());
        else JoblessReincarnation.LOGGER.error("[JR_DUMMY_RUNTIME_PROBE] FAILED cases={}/{}; inspect dummy-runtime-client-probe.json", passed, cases.size());
    }

    @FunctionalInterface private interface Check { Map<String, Object> run() throws Exception; }
    private static void run(List<Map<String, Object>> cases, String name, Check check) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        cases.add(result);
        try {
            result.putAll(check.run());
            result.put("status", "PASS");
        } catch (Exception | AssertionError | LinkageError error) {
            result.put("status", "FAILED");
            result.put("error", error.toString());
            JoblessReincarnation.LOGGER.error("[JR_DUMMY_RUNTIME_PROBE] CASE_FAILED {}", name, error);
        }
    }

    private static Map<String, Object> orientation(Minecraft client) {
        PracticeDummyEntity dummy = ModContent.PRACTICE_DUMMY.get().create(client.level);
        require(dummy != null, "Registered dummy factory must be available");
        var actual = client.getEntityRenderDispatcher().getRenderer(dummy);
        require(actual instanceof PracticeDummyRenderer, "The actual entity registration must select the formal renderer");
        PracticeDummyRenderer renderer = (PracticeDummyRenderer) actual;
        List<Map<String, Object>> rows = new ArrayList<>();
        // Expected world directions are independent cardinal/diagonal vectors, not the renderer's rotation formula.
        float[] yaws = {0, 90, 180, -90, 45, 137};
        Vector3f[] expected = {
            new Vector3f(0, 0, 1), new Vector3f(-1, 0, 0), new Vector3f(0, 0, -1), new Vector3f(1, 0, 0),
            new Vector3f(-1, 0, 1).normalize(), new Vector3f(-0.6819984F, 0, -0.7313537F)
        };
        for (int i = 0; i < yaws.length; i++) {
            dummy.setYRot(yaws[i]);
            dummy.yRotO = yaws[i];
            PoseStack stack = new PoseStack();
            renderer.applyRotations(dummy, stack, 0, 0, 1, 1);
            Vector3f front = stack.last().pose().transformDirection(new Vector3f(0, 0, -1));
            rows.add(Map.of("yaw", yaws[i], "renderedNorthFace", List.of(front.x, front.y, front.z),
                    "expected", List.of(expected[i].x, expected[i].y, expected[i].z)));
            require(front.distance(expected[i]) < 0.00002, "Rendered front must follow saved yaw " + yaws[i] + ": " + front);
        }
        require(!dummy.isAddedToLevel(), "Orientation probe never spawns a world entity");
        return Map.of("samples", rows);
    }

    private static Map<String, Object> repeatedHit(Minecraft client, int interval, boolean idleFrame) {
        Fixture f = new Fixture(client);
        f.frame("idle", 100.25);
        f.hit();
        f.frame("first_trigger", 100.5);
        f.frame("first_running_zero", 100.75);
        double firstPeak = f.frame("first_clip_tick_2", 102.75);
        require(Math.abs(firstPeak) > 3.5, "The actual formal clip must first reach its 4.5-degree response");
        if (interval >= 8) {
            f.frame("first_completed_frame", 107.75);
            require(f.controller.hasAnimationFinished(), "The first hit must reach the real stopped completion boundary");
            if (idleFrame) f.frame("ordinary_idle_cleanup", 108.0);
        }
        double next = 100.75 + interval;
        f.hit();
        f.frame("next_trigger", next);
        f.frame("next_running_zero", next + 0.25);
        double nextPeak = f.frame("next_clip_tick_2", next + 2.25);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intervalTicks", interval);
        result.put("ordinaryIdleFrameBeforeRetrigger", idleFrame);
        result.put("samples", f.rows);
        result.put("firstPeakX", firstPeak);
        result.put("nextPeakX", nextPeak);
        result.put("playingAfterNextTwoTicks", f.controller.isPlayingTriggeredAnimation());
        // Record all poses even when this assertion reveals the dependency's replay bug.
        if (!f.controller.isPlayingTriggeredAnimation() || Math.abs(nextPeak) < 3.5) {
            JoblessReincarnation.LOGGER.error("[JR_DUMMY_RUNTIME_PROBE] REPLAY_EVIDENCE {}", new GsonBuilder().create().toJson(result));
            throw new AssertionError("An accepted hit at tick " + interval + " must restart the real reaction; next peak=" + nextPeak
                    + ", playing=" + f.controller.isPlayingTriggeredAnimation() + "; samples=" + new GsonBuilder().create().toJson(f.rows));
        }
        f.independent();
        return result;
    }

    private static final class Fixture {
        final PracticeDummyEntity entity;
        final DefaultedEntityGeoModel<PracticeDummyEntity> model = new DefaultedEntityGeoModel<>(id("practice_dummy"));
        final AnimatableManager<PracticeDummyEntity> manager;
        final AnimationController<PracticeDummyEntity> controller;
        final GeoBone body;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<Witness> witnesses = new ArrayList<>();
        Fixture(Minecraft client) {
            entity = ModContent.PRACTICE_DUMMY.get().create(client.level);
            require(entity != null, "Registered dummy factory must exist");
            BakedGeoModel shared = GeckoLibCache.getBakedModels().get(GEO);
            require(shared != null, "Formal main geometry must be baked");
            List<GeoBone> roots = new ArrayList<>();
            for (GeoBone root : shared.topLevelBones()) roots.add(copy(root, null, witnesses));
            model.getAnimationProcessor().setActiveModel(new BakedGeoModel(roots, shared.properties()));
            body = model.getAnimationProcessor().getBone("body");
            require(body != null, "Formal geometry must expose body bone");
            manager = entity.getAnimatableInstanceCache().getManagerForId(entity.getId());
            controller = manager.getAnimationControllers().get(PracticeDummyEntity.CONTROLLER);
            require(controller != null, "Production practice controller must exist");
            var animation = model.getAnimation(entity, "animation.practice_dummy.hit_candidate");
            require(animation != null && Math.abs(animation.length() - 7.0) < 0.000001, "Formal hit must be the delivered 7-tick clip");
        }
        void hit() { require(controller.tryTriggerAnimation(PracticeDummyEntity.HIT_TRIGGER), "The production controller must accept hit"); }
        double frame(String label, double time) {
            AnimationState<PracticeDummyEntity> state = new AnimationState<>(entity, 0, 0, 0, false);
            state.animationTick = time;
            state.setData(DataTickets.TICK, time);
            state.setData(DataTickets.ENTITY, entity);
            model.getAnimationProcessor().tickAnimation(entity, model, manager, time, state, true);
            double x = Math.toDegrees(body.getRotX() - body.getInitialSnapshot().getRotX());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label); row.put("inputTick", time); row.put("bodyXDegrees", x);
            row.put("state", controller.getAnimationState().name());
            row.put("playingTriggered", controller.isPlayingTriggeredAnimation());
            row.put("animation", controller.getCurrentAnimation() == null ? null : controller.getCurrentAnimation().animation().name());
            rows.add(row);
            independent();
            return x;
        }
        void independent() {
            require(!entity.isAddedToLevel(), "Animation fixture never enters the scene");
            for (Witness witness : witnesses) require(witness.unchanged(), "Shared baked geometry was mutated");
        }
    }

    private static GeoBone copy(GeoBone source, GeoBone parent, List<Witness> witnesses) {
        witnesses.add(new Witness(source, source.getRotX(), source.getRotY(), source.getRotZ(), source.getPosX(), source.getPosY(),
                source.getPosZ(), source.getScaleX(), source.getScaleY(), source.getScaleZ()));
        GeoBone bone = new GeoBone(parent, source.getName(), source.getMirror(), source.getInflate(), source.shouldNeverRender(), source.getReset());
        BoneSnapshot initial = source.getInitialSnapshot();
        bone.updateRotation(initial == null ? source.getRotX() : initial.getRotX(), initial == null ? source.getRotY() : initial.getRotY(), initial == null ? source.getRotZ() : initial.getRotZ());
        bone.updatePosition(initial == null ? source.getPosX() : initial.getOffsetX(), initial == null ? source.getPosY() : initial.getOffsetY(), initial == null ? source.getPosZ() : initial.getOffsetZ());
        bone.updateScale(initial == null ? source.getScaleX() : initial.getScaleX(), initial == null ? source.getScaleY() : initial.getScaleY(), initial == null ? source.getScaleZ() : initial.getScaleZ());
        bone.updatePivot(source.getPivotX(), source.getPivotY(), source.getPivotZ());
        bone.getCubes().addAll(source.getCubes());
        for (GeoBone child : source.getChildBones()) bone.getChildBones().add(copy(child, bone, witnesses));
        return bone;
    }
    private record Witness(GeoBone bone, float x, float y, float z, float px, float py, float pz, float sx, float sy, float sz) {
        boolean unchanged() { return bone.getRotX() == x && bone.getRotY() == y && bone.getRotZ() == z
                && bone.getPosX() == px && bone.getPosY() == py && bone.getPosZ() == pz
                && bone.getScaleX() == sx && bone.getScaleY() == sy && bone.getScaleZ() == sz; }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private DummyRuntimeClientProbe() { }
}
