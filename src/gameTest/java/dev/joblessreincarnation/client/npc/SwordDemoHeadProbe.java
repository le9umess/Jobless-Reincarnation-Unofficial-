package dev.joblessreincarnation.client.npc;

import com.google.gson.GsonBuilder;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.npc.SwordDemoAnimationController;
import dev.joblessreincarnation.registry.ModContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * Opt-in client-only timing probe. It drives the real processor and PaulGeoModel with resource-loaded
 * animations and private copies of the baked skeleton. Its Pauls never enter the world, no renderer
 * state/timer field is injected, and the scene's entities and shared mutable bones are never changed.
 * This checks resulting bone poses at synthetic frame times, not screen quality or multiplayer input.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class SwordDemoHeadProbe {
    private static final boolean ENABLED = Boolean.getBoolean("jobless_reincarnation.swordDemoPreview.interaction");
    private static final ResourceLocation GEO = id("geo/entity/paul_greyrat.geo.json");
    private static final ResourceLocation ANIMATIONS = id("animations/entity/paul_greyrat.animation.json");
    private static final String DEMO = "animation.paul_greyrat.sword_demo";
    private static final float YAW = 30.0F, PITCH = 20.0F;
    private static final double EPSILON_DEGREES = 0.002;
    private static boolean finished;
    private static int readyTicks;

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, path);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        try {
            if (!GeckoLibCache.getBakedModels().containsKey(GEO)
                    || !GeckoLibCache.getBakedAnimations().containsKey(ANIMATIONS)) {
                if (++readyTicks <= 120) return;
                finished = true;
                writeFailure(client, new IllegalStateException("Paul's baked model/animations were not ready after 120 world ticks"));
                return;
            }
            finished = true;
            Map<String, Object> report = baseReport();
            List<Map<String, Object>> cases = new ArrayList<>();
            report.put("cases", cases);
            runCase(client, cases, "cold_trigger_and_ease_in", SwordDemoHeadProbe::coldTrigger);
            runCase(client, cases, "skipped_transition_and_arm_safe_ease_out", SwordDemoHeadProbe::skippedTransition);
            runCase(client, cases, "double_speed_completion_and_normal_retrigger", SwordDemoHeadProbe::doubleSpeedAndRetrigger);
            List<Map<String, Object>> observations = new ArrayList<>();
            observations.add(zeroIdleRetriggerObservation(client, false));
            observations.add(zeroIdleRetriggerObservation(client, true));
            report.put("zeroIdleRetriggerObservations", observations);
            boolean retriggersPassed = observations.stream().allMatch(value -> Boolean.FALSE.equals(value.get("wholeRetriggerLost"))
                    && Boolean.TRUE.equals(value.get("playingAfterSixInputTicks")));
            report.put("zeroIdleRetriggerRegressionPassed", retriggersPassed);
            long passed = cases.stream().filter(value -> "PASS".equals(value.get("status"))).count();
            report.put("passed", passed);
            report.put("total", cases.size());
            report.put("status", passed == cases.size() && retriggersPassed ? "PASS" : "FAILED");
            write(client, report);
            if (passed == cases.size() && retriggersPassed) JoblessReincarnation.LOGGER.info("[JR_SWORD_HEAD_PROBE] PASS cases={}/{} actual_processor=true actual_head_model=true", passed, cases.size());
            else JoblessReincarnation.LOGGER.error("[JR_SWORD_HEAD_PROBE] FAILED cases={}/{}; inspect sword-demo-head-probe.json before accepting the preview", passed, cases.size());
        } catch (Exception | AssertionError | LinkageError failure) {
            finished = true;
            writeFailure(client, failure);
        }
    }

    private static Map<String, Object> baseReport() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("probe", "B01 Paul actual-controller/head-bone timing");
        result.put("createdAtUtc", Instant.now().toString());
        result.put("geometry", GEO.toString());
        result.put("animationResource", ANIMATIONS.toString());
        result.put("inputHeadYawDegrees", YAW);
        result.put("inputHeadPitchDegrees", PITCH);
        result.put("method", "Private baked skeleton + unregistered client Paul; AnimationProcessor.tickAnimation then PaulGeoModel.setCustomAnimations; no controller field injection");
        result.put("scope", "Synthetic frame-time client self-check; screenshots, manual play, dedicated-server restart and multiplayer remain separate evidence");
        result.put("retriggerBoundary", "Normal retrigger follows one real idle frame; both zero-idle native and explicit-reset control must now resume playback");
        return result;
    }

    @FunctionalInterface
    private interface Check { void run(Fixture fixture); }

    private static void runCase(Minecraft client, List<Map<String, Object>> cases, String name, Check check) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        result.put("name", name);
        result.put("samples", samples);
        cases.add(result);
        try {
            Fixture fixture = new Fixture(client, samples);
            check.run(fixture);
            fixture.assertIndependent();
            result.put("status", "PASS");
            JoblessReincarnation.LOGGER.info("[JR_SWORD_HEAD_PROBE] CASE_PASS {} samples={}", name, samples.size());
        } catch (Exception | AssertionError | LinkageError failure) {
            result.put("status", "FAILED");
            result.put("error", failure.toString());
            JoblessReincarnation.LOGGER.error("[JR_SWORD_HEAD_PROBE] CASE_FAILED {}", name, failure);
        }
    }

    /** Observe the dependency's exact boundary separately; it must not be relabelled as normal-retrigger PASS. */
    private static Map<String, Object> zeroIdleRetriggerObservation(Minecraft client, boolean forceReset) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        result.put("name", forceReset ? "zero_idle_with_public_forceAnimationReset" : "zero_idle_native_tryTriggerAnimation");
        result.put("samples", samples);
        result.put("publicForceAnimationResetBeforeTrigger", forceReset);
        try {
            Fixture f = new Fixture(client, samples);
            f.frame("before_trigger", 400.25);
            f.trigger();
            phase(f.frame("first_trigger_frame", 400.5), 0);
            phase(f.frame("running_clip_zero", 404.5), 4);
            Pose raised = f.frame("real_raised_pose", 415.5);
            require(Math.abs(raised.armX()) > 90, "Observation must first play the actual raised-arm clip");
            stopped(f, f.frame("first_completed_frame", 438.5));
            // There is deliberately NO intervening idle processing pass. Only public library API is used.
            if (forceReset) f.gesture.forceAnimationReset();
            boolean accepted = f.gesture.tryTriggerAnimation(NpcAnimations.SWORD_DEMO);
            result.put("triggerAccepted", accepted);
            Pose first = f.frame("zero_idle_retrigger_first_frame", 439.0);
            result.put("firstFrameHasCurrentAnimation", f.gesture.getCurrentAnimation() != null);
            result.put("firstFrameHasPresentationPhase", Double.isFinite(first.phase()));
            f.frame("zero_idle_retrigger_after_transition", 443.0);
            Pose after = f.frame("zero_idle_retrigger_clip_tick_2", 445.0);
            boolean playing = f.gesture.isPlayingTriggeredAnimation()
                    && f.gesture.getCurrentAnimation() != null && Double.isFinite(after.phase());
            result.put("playingAfterSixInputTicks", playing);
            result.put("wholeRetriggerLost", accepted && !playing);
            result.put("observedOutcome", playing ? "RETRIGGER_PLAYING" : "RETRIGGER_NOT_PLAYING");
            if (forceReset) {
                phase(after, 6);
                straight(after);
                require(Math.abs(after.armX()) > 5, "Public force-reset recovery must really advance the delivered arm clip");
            }
            f.assertIndependent();
            result.put("status", "OBSERVED");
            JoblessReincarnation.LOGGER.info("[JR_SWORD_HEAD_PROBE] ZERO_IDLE_OBSERVATION publicForceReset={} accepted={} playingAfterSixTicks={}",
                    forceReset, accepted, playing);
        } catch (Exception | AssertionError | LinkageError failure) {
            result.put("status", "OBSERVATION_FAILED");
            result.put("error", failure.toString());
            JoblessReincarnation.LOGGER.error("[JR_SWORD_HEAD_PROBE] ZERO_IDLE_OBSERVATION_FAILED publicForceReset={}", forceReset, failure);
        }
        return result;
    }

    private static final class Fixture {
        final Minecraft client;
        final FamilyResidentEntity paul;
        final PaulGeoModel model = new PaulGeoModel();
        final AnimatableManager<FamilyResidentEntity> manager;
        final SwordDemoAnimationController gesture;
        final GeoBone head, arm;
        final List<Map<String, Object>> samples;
        final BakedGeoModel sharedSource;
        final List<BoneWitness> sharedWitnesses = new ArrayList<>();
        final long instanceId;

        Fixture(Minecraft client, List<Map<String, Object>> samples) {
            this.client = client;
            this.samples = samples;
            paul = ModContent.PAUL_GREYRAT.get().create(client.level);
            require(paul != null, "Registered Paul factory must be available on the client");
            instanceId = paul.getId();
            sharedSource = GeckoLibCache.getBakedModels().get(GEO);
            require(sharedSource != null, "The real resource-loaded Paul geometry must exist");
            List<GeoBone> privateRoots = new ArrayList<>();
            for (GeoBone root : sharedSource.topLevelBones()) privateRoots.add(copyBone(root, null, sharedWitnesses));
            model.getAnimationProcessor().setActiveModel(new BakedGeoModel(privateRoots, sharedSource.properties()));
            head = model.getAnimationProcessor().getBone("head");
            arm = model.getAnimationProcessor().getBone("right_arm");
            require(head != null && arm != null, "Actual Paul geometry must expose head and right_arm");
            manager = paul.getAnimatableInstanceCache().getManagerForId(instanceId);
            var controller = manager.getAnimationControllers().get(NpcAnimations.GESTURE_CONTROLLER);
            require(controller instanceof SwordDemoAnimationController, "Production Paul registration must select the custom sword controller");
            gesture = (SwordDemoAnimationController) controller;
            var clip = model.getAnimation(paul, DEMO);
            require(clip != null && Math.abs(clip.length() - 34.0) < 0.00001,
                    "This probe's timing contract requires the delivered 34-tick sword clip");
            assertIndependent();
        }

        void trigger() { require(gesture.tryTriggerAnimation(NpcAnimations.SWORD_DEMO), "The real controller must accept its registered sword trigger"); }

        Pose frame(String label, double now) {
            AnimationState<FamilyResidentEntity> state = new AnimationState<>(paul, 0, 0, 0, false);
            state.animationTick = now;
            state.setData(DataTickets.TICK, now);
            state.setData(DataTickets.ENTITY, paul);
            state.setData(DataTickets.ENTITY_MODEL_DATA, new EntityModelData(false, false, YAW, PITCH));
            // The delivered clips have numeric keyframes. Directly use the same processor/custom-model
            // order as GeoModel.handleAnimations, with explicit frame times instead of advancing the world.
            model.getAnimationProcessor().tickAnimation(paul, model, manager, now, state, true);
            model.setCustomAnimations(paul, instanceId, state);
            double phase = gesture.presentationTicks(now);
            Pose pose = new Pose(Math.toDegrees(head.getRotY()), Math.toDegrees(head.getRotX()),
                    Math.toDegrees(arm.getRotX() - arm.getInitialSnapshot().getRotX()),
                    Math.toDegrees(arm.getRotY() - arm.getInitialSnapshot().getRotY()),
                    Math.toDegrees(arm.getRotZ() - arm.getInitialSnapshot().getRotZ()), phase);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("label", label);
            sample.put("inputFrameTick", now);
            sample.put("state", gesture.getAnimationState().name());
            sample.put("animation", gesture.getCurrentAnimation() == null ? null : gesture.getCurrentAnimation().animation().name());
            sample.put("playingTriggered", gesture.isPlayingTriggeredAnimation());
            sample.put("presentationTick", Double.isFinite(phase) ? phase : null);
            sample.put("headYawDegrees", pose.yaw());
            sample.put("headPitchDegrees", pose.pitch());
            sample.put("rightArmOffsetDegrees", List.of(pose.armX(), pose.armY(), pose.armZ()));
            samples.add(sample);
            require(Double.isFinite(pose.yaw()) && Double.isFinite(pose.pitch()) && Double.isFinite(pose.armX())
                    && Double.isFinite(pose.armY()) && Double.isFinite(pose.armZ()), "Actual bone output must remain finite");
            return pose;
        }

        void assertIndependent() {
            require(!paul.isAddedToLevel() && client.level.getEntity(paul.getId()) != paul,
                    "Probe Paul must never join or replace any scene entity");
            for (BoneWitness witness : sharedWitnesses) require(witness.unchanged(), "A cached geometry bone was mutated by the private fixture: " + witness.bone().getName());
        }
    }

    /** Copy mutable bones from their original baked pose, retaining read-only geometry metadata/cubes. */
    private static GeoBone copyBone(GeoBone source, GeoBone parent, List<BoneWitness> witnesses) {
        witnesses.add(new BoneWitness(source, source.getRotX(), source.getRotY(), source.getRotZ(),
                source.getPosX(), source.getPosY(), source.getPosZ(), source.getScaleX(), source.getScaleY(), source.getScaleZ()));
        GeoBone copy = new GeoBone(parent, source.getName(), source.getMirror(), source.getInflate(), source.shouldNeverRender(), source.getReset());
        BoneSnapshot initial = source.getInitialSnapshot();
        copy.updateRotation(initial == null ? source.getRotX() : initial.getRotX(),
                initial == null ? source.getRotY() : initial.getRotY(), initial == null ? source.getRotZ() : initial.getRotZ());
        copy.updatePosition(initial == null ? source.getPosX() : initial.getOffsetX(),
                initial == null ? source.getPosY() : initial.getOffsetY(), initial == null ? source.getPosZ() : initial.getOffsetZ());
        copy.updateScale(initial == null ? source.getScaleX() : initial.getScaleX(),
                initial == null ? source.getScaleY() : initial.getScaleY(), initial == null ? source.getScaleZ() : initial.getScaleZ());
        copy.updatePivot(source.getPivotX(), source.getPivotY(), source.getPivotZ());
        copy.getCubes().addAll(source.getCubes());
        for (GeoBone child : source.getChildBones()) copy.getChildBones().add(copyBone(child, copy, witnesses));
        return copy;
    }

    private record BoneWitness(GeoBone bone, float x, float y, float z, float px, float py, float pz, float sx, float sy, float sz) {
        boolean unchanged() {
            return bone.getRotX() == x && bone.getRotY() == y && bone.getRotZ() == z
                    && bone.getPosX() == px && bone.getPosY() == py && bone.getPosZ() == pz
                    && bone.getScaleX() == sx && bone.getScaleY() == sy && bone.getScaleZ() == sz;
        }
    }

    private record Pose(double yaw, double pitch, double armX, double armY, double armZ, double phase) { }

    private static void coldTrigger(Fixture f) {
        normalLook(f.frame("before_trigger", 100.25));
        require(f.gesture.getCurrentAnimation() == null, "Fresh idle gesture must have no current animation before its first trigger");
        f.trigger();
        Pose first = f.frame("first_trigger_frame", 100.5);
        phase(first, 0);
        normalLook(first);
        require(f.gesture.getCurrentAnimation() != null, "The actual first process call must resolve a current animation");
        Pose partial = f.frame("blend_tick_2", 102.5);
        phase(partial, 2);
        require(partial.yaw() > 0 && partial.yaw() < first.yaw() && partial.pitch() > 0 && partial.pitch() < first.pitch(),
                "Actual head angles must begin easing rather than snapping during blend");
        Pose running = f.frame("first_running_frame", 104.5);
        phase(running, 4);
        require(running.yaw() > 0 && running.yaw() < partial.yaw(), "Head easing must continue across the genuine transition boundary");
        straight(f.frame("clip_tick_2_head_ready", 106.5));
        Pose raised = f.frame("clip_tick_11_raised_arm", 115.5);
        straight(raised);
        require(Math.abs(raised.armX()) > 90, "The actual delivered arm keyframes must really reach their raised pose while the head stays straight");
    }

    private static void skippedTransition(Fixture f) {
        f.frame("before_trigger", 200.25);
        f.trigger();
        phase(f.frame("first_trigger_frame", 200.5), 0);
        Pose skipped = f.frame("skip_ten_ticks_over_blend", 210.5);
        phase(skipped, 4);
        neutralArm(skipped);
        require(f.gesture.getAnimationState() == AnimationController.State.RUNNING && skipped.yaw() > 0 && skipped.yaw() < YAW,
                "A skipped transition must start the real clip at zero while preserving its corresponding intermediate head ease");
        Pose beforeReturn = f.frame("clip_tick_28_before_arm_settles", 238.5);
        phase(beforeReturn, 32);
        straight(beforeReturn);
        require(Math.abs(beforeReturn.armX()) > 3, "The real arm is still returning on clip tick 28");
        Pose down = f.frame("clip_tick_29_arm_down_head_still_straight", 239.5);
        phase(down, 33);
        straight(down);
        neutralArm(down);
        Pose returning = f.frame("clip_tick_30_head_return_begins", 240.5);
        phase(returning, 34);
        neutralArm(returning);
        require(returning.yaw() > 0 && returning.yaw() < YAW && returning.pitch() > 0 && returning.pitch() < PITCH,
                "Look returns only after actual right-arm keyframes have reached neutral");
        Pose almost = f.frame("clip_tick_33_late_head_return", 243.5);
        neutralArm(almost);
        require(almost.yaw() > returning.yaw() && almost.yaw() < YAW, "Late actual head pose approaches normal without an early snap");
        Pose ended = f.frame("clip_tick_34_stopped", 244.5);
        stopped(f, ended);
        require(Math.abs(ended.armX()) < 3 && Math.abs(ended.armY()) < 3 && Math.abs(ended.armZ()) < 3,
                "At gesture completion the real locomotion controller has already restored a resting arm");
    }

    private static void doubleSpeedAndRetrigger(Fixture f) {
        f.frame("before_trigger", 300.25);
        f.gesture.setAnimationSpeed(2.0);
        f.trigger();
        phase(f.frame("double_speed_first_frame", 300.5), 0);
        Pose blend = f.frame("double_speed_blend_tick_2", 301.5);
        phase(blend, 2);
        require(blend.yaw() > 0 && blend.yaw() < YAW, "Two-times speed affects the real blend and final head pose together");
        phase(f.frame("double_speed_running_clip_zero", 302.5), 4);
        straight(f.frame("double_speed_clip_tick_2", 303.5));
        Pose down = f.frame("double_speed_clip_tick_29", 317.0);
        phase(down, 33);
        straight(down);
        neutralArm(down);
        Pose returning = f.frame("double_speed_clip_tick_31_5", 318.25);
        require(returning.yaw() > 0 && returning.yaw() < YAW, "Actual accelerated head return remains between its endpoints");
        neutralArm(returning);
        stopped(f, f.frame("double_speed_completion", 319.5));
        // GeckoLib clears a finished triggered RawAnimation in the next ordinary state-handler pass.
        // Keep that real idle frame; do not inject a queue/reset flag to manufacture retrigger success.
        normalLook(f.frame("ordinary_idle_cleanup_frame", 320.0));
        f.gesture.setAnimationSpeed(1.0);
        f.trigger();
        Pose restarted = f.frame("normal_speed_retrigger_first_frame", 320.5);
        phase(restarted, 0);
        normalLook(restarted);
        require(f.gesture.getCurrentAnimation() != null, "Normal retrigger must repopulate its animation through the real process path");
        phase(f.frame("retrigger_transition_completed", 324.5), 4);
        straight(f.frame("retrigger_head_ready", 326.5));
    }

    private static void phase(Pose pose, double expected) {
        require(Double.isFinite(pose.phase()) && Math.abs(pose.phase() - expected) < 0.00001,
                "Actual controller presentation phase expected " + expected + ", got " + pose.phase());
    }
    private static void normalLook(Pose pose) {
        require(Math.abs(pose.yaw() - YAW) < EPSILON_DEGREES && Math.abs(pose.pitch() - PITCH) < EPSILON_DEGREES,
                "Actual default head look must remain/restored at yaw 30 and pitch 20; got " + pose.yaw() + "/" + pose.pitch());
    }
    private static void straight(Pose pose) {
        require(Math.abs(pose.yaw()) < EPSILON_DEGREES && Math.abs(pose.pitch()) < EPSILON_DEGREES,
                "Actual head must be straight while the sword arm is active; got " + pose.yaw() + "/" + pose.pitch());
    }
    private static void neutralArm(Pose pose) {
        require(Math.abs(pose.armX()) < EPSILON_DEGREES && Math.abs(pose.armY()) < EPSILON_DEGREES && Math.abs(pose.armZ()) < EPSILON_DEGREES,
                "Actual right arm must be at the delivered neutral keyframe; got " + pose.armX() + "/" + pose.armY() + "/" + pose.armZ());
    }
    private static void stopped(Fixture f, Pose pose) {
        require(f.gesture.getAnimationState() == AnimationController.State.STOPPED && !f.gesture.isPlayingTriggeredAnimation()
                && !Double.isFinite(pose.phase()), "The real PLAY_ONCE completion must release the head override");
        normalLook(pose);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void write(Minecraft client, Map<String, Object> report) throws IOException {
        // The report goes into the game directory of the run that started the probe, never elsewhere.
        Path run = client.gameDirectory.toPath().toAbsolutePath().normalize();
        Path output = run.resolve("sword-demo-head-probe.json");
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report) + "\n", StandardCharsets.UTF_8);
        JoblessReincarnation.LOGGER.info("[JR_SWORD_HEAD_PROBE] REPORT {} status={}", output, report.get("status"));
    }

    private static void writeFailure(Minecraft client, Throwable failure) {
        JoblessReincarnation.LOGGER.error("[JR_SWORD_HEAD_PROBE] FAILED; this preview must not be accepted as head-timing verification", failure);
        Map<String, Object> report = baseReport();
        report.put("status", "FAILED");
        report.put("error", failure.toString());
        try { write(client, report); }
        catch (Exception writeFailure) { JoblessReincarnation.LOGGER.error("[JR_SWORD_HEAD_PROBE] FAILED_REPORT_WRITE", writeFailure); }
    }

    private SwordDemoHeadProbe() { }
}