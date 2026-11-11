package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.MultiplayerSpellProbe;
import dev.joblessreincarnation.magic.EarthWallSpell;
import dev.joblessreincarnation.magic.AirBurstSpell;
import dev.joblessreincarnation.magic.HealingSpell;
import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.magic.FireBallProjectile;
import dev.joblessreincarnation.magic.EarthWallBlockEntity;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.network.OpenJournalPayload;
import dev.joblessreincarnation.network.SelectSpellPayload;
import net.minecraft.resources.ResourceLocation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Observes independently received packets in each real client process; never included in release jars. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class MultiplayerSpellProbeClient {
    private static final java.util.Set<java.util.UUID> fireSeen=new java.util.HashSet<>();
    private static boolean firePicture;
    private static final java.util.Map<java.util.UUID,net.minecraft.world.phys.Vec3> fireFirst=new java.util.HashMap<>();
    private static double fireTravel;
    private static int fireCheckAge;
    private static MultiplayerSpellProbe.Check pending;
    private static int waitTicks, stableTicks, age, nextConnect = 100, finishTicks = -1;
    private static boolean done, captureStarted, captureDone;
    private static String captureFailure;
    private static int journalRequestPhase = -1, journalRequestAge;
    private static String lastObserverDiagnostic;
    private static String lastPrivacyDiagnostic;
    private static String status = "等待另一客户端与服务器探针";

    /** Loopback listening deliberately does not publish the world to LAN, so vanilla still considers it pausable. */
    private static boolean privateHostWorld() {
        Minecraft client = Minecraft.getInstance();
        return MultiplayerSpellProbe.enabled() && !done && client.getSingleplayerServer() != null
                && "JR Magic 0.2.0 Test".equals(client.getSingleplayerServer().getWorldData().getLevelName());
    }

    @SubscribeEvent public static void preventProbePause(ClientPauseChangeEvent.Pre event) {
        if (event.isPaused() && privateHostWorld()) {
            event.setCanceled(true);
            // No persistent option is changed. Physics/packet tracking must continue while the second process is focused.
        }
    }

    @SubscribeEvent public static void keepProbeScreen(ScreenEvent.Opening event) {
        if (privateHostWorld() && event.getNewScreen() instanceof PauseScreen) {
            event.setNewScreen(new ProbeScreen());
            LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] PAUSE_MENU_REPLACED: explicit private-world probe remains non-pausing");
        }
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!MultiplayerSpellProbe.enabled() && !MultiplayerSpellProbe.observer()) return;
        Minecraft client = Minecraft.getInstance();
        MultiplayerSpellProbe.clientReceiver = MultiplayerSpellProbeClient::receive;
        age++;
        if (done) return;
        if (finishTicks >= 0) {
            if (--finishTicks == 0) {
                done = true;
                // Match vanilla PauseScreen: close the transport before Minecraft waits for integrated-server shutdown.
                net.minecraft.client.multiplayer.ClientLevel closingLevel = client.level;
                if (closingLevel != null) closingLevel.disconnect();
                client.disconnect(new TitleScreen());
                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] DISCONNECTED: temporary peer world ended after server cleanup");
                client.stop(); // This branch is reachable only in an explicitly enabled, completed probe process.
            }
            return;
        }
        if (MultiplayerSpellProbe.observer() && client.player == null && client.getOverlay() == null
                && age >= nextConnect && (client.screen instanceof TitleScreen || client.screen instanceof DisconnectedScreen)) {
            nextConnect = age + 200;
            String address = "127.0.0.1:" + MultiplayerSpellProbe.PORT;
            ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                    new ServerData("JR local spell probe", address, ServerData.Type.OTHER), false, null);
            LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] CONNECT: {} using separate observer process", address);
            return;
        }
        if (client.player == null || client.level == null) return;
        if (!(client.screen instanceof ProbeScreen) && (client.screen == null || client.screen instanceof TitleScreen || client.screen instanceof PauseScreen)) client.setScreen(new ProbeScreen());
        for(Entity entity:client.level.entitiesForRendering())if(entity instanceof FireBallProjectile ball){
            fireSeen.add(ball.getUUID());
            var first=fireFirst.computeIfAbsent(ball.getUUID(),id->ball.position());
            fireTravel=Math.max(fireTravel,first.distanceTo(ball.position()));
            if(!firePicture && ball.tickCount>=9) {
                firePicture=true;
                net.minecraft.client.Screenshot.grab(client.gameDirectory,"fire_multiplayer_012_"+(MultiplayerSpellProbe.observer()?"observer":"host")+"_"+System.currentTimeMillis()+".png",client.getMainRenderTarget(),message->LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] FIRE_FLIGHT_SCREENSHOT {}",message.getString()));
            }
        }
        if (pending == null) return;
        MultiplayerSpellProbe.Check check = pending;
        if (check.phase() == 14 || check.phase() == 15) {
            if (journalRequestPhase != check.phase()) {
                journalRequestPhase = check.phase(); journalRequestAge = age;
                ResourceLocation requested = check.phase() == 14 ? ResourceLocation.parse(check.selected())
                        : ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,
                                MultiplayerSpellProbe.observer() ? "healing" : "journal_invalid_test_spell");
                if (check.phase() == 14) PacketDistributor.sendToServer(OpenJournalPayload.INSTANCE);
                PacketDistributor.sendToServer(new SelectSpellPayload(requested, client.player.getData(ModAttachments.CASTING).revision()));
                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] JOURNAL_REQUEST phase={} id={}; normal C2S, no local attachment assignment; UI is a separate probe", check.phase(), requested);
            }
            if (age - journalRequestAge < 8) return;
        }        List<String> errors = inspect(client, check);
        if(check.phase()>=18 && ++fireCheckAge<24)return;
        if (errors.isEmpty()) {
            boolean capture = check.phase() == 3 || check.phase() == 6 || check.phase() == 7 || check.phase() == 9
                    || check.phase() == 10 || check.phase() == 12 || check.phase() == 17;
            // Capture the release frame as soon as ordinary tracked positions converge, while the real cloud pulse can remain visible.
            if (capture && check.phase() != 9 && ++stableTicks < 10) return;
            if (capture) {
                if (!captureStarted) {
                    captureStarted = true;
                    String role = MultiplayerSpellProbe.observer() ? "observer" : "host";
                    String view = switch (check.phase()) {
                        case 3 -> "earth_before_dismissal";
                        case 6 -> "earth_after_dismissal";
                        case 7 -> "air_before_release";
                        case 9 -> "air_after_release";
                        case 10 -> "healing_before_release";
                        case 17 -> "fire_focus_ready";
                        default -> "healing_after_release";
                    };
                    net.minecraft.client.Screenshot.grab(client.gameDirectory, "spell_multiplayer_" + role + "_" + view + "_" + System.currentTimeMillis() + ".png",
                            client.getMainRenderTarget(), message -> client.execute(() -> {
                                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] SCREENSHOT {}", message.getString());
                                if (pending == null || pending.phase() != check.phase()) return;
                                if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents content
                                        && "screenshot.success".equals(content.getKey())) captureDone = true;
                                else captureFailure = "Native screenshot was not saved: " + message.getString();
                            }));
                }
                if (captureFailure != null || (!captureDone && ++waitTicks > 240)) {
                    String detail = captureFailure == null ? "Native screenshot save callback timed out" : captureFailure;
                    PacketDistributor.sendToServer(new MultiplayerSpellProbe.Ack(check.phase(), false, detail));
                    LogUtils.getLogger().error("[JR_MULTIPLAYER_PROBE_CLIENT] FAIL phase={}: {}", check.phase(), detail);
                    pending = null;
                    return;
                }
                if (!captureDone) return;
            }
            PacketDistributor.sendToServer(new MultiplayerSpellProbe.Ack(check.phase(), true,
                    "Owner selection/practice/mana/active state and remote attachment/block-entity privacy verified; six terrain cells match; "
                            + (check.phase() >= 10 ? "ordinary owner and remote peer health plus owner-only healing state independently verified; "
                                    : check.phase() >= 7 ? "four ordinary tracked entity positions/health verified; near-release screenshot is visual evidence, not a particle packet counter; "
                                    : "ordinary nonmissing wall model verified; ")
                            + "real client process=" + client.player.getGameProfile().getName()));
            LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] PASS phase={} owner={} selected={} water={} earth={} air={} healing={} mana={} wall={} trackedTargets={}",
                    check.phase(), client.player.getGameProfile().getName(), check.selected(), check.water(), check.earth(), check.air(), check.healing(), check.mana(), check.wall(), check.entities().size());
            if (check.phase() >= 10 && check.phase() <= 15) {
                var peer = client.level.getPlayerByUUID(check.peer());
                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] HEALING_HEALTH_RECEIPT phase={} owner={} health={} expected={} peer={} health={} expected={} healing={} learned={}; ordinary vanilla packets, no client health assignment",
                        check.phase(), client.player.getGameProfile().getName(), client.player.getHealth(), check.ownerHealth(),
                        peer.getGameProfile().getName(), peer.getHealth(), check.peerHealth(),
                        HealingSpell.practiceCount(client.player), HealingSpell.isLearned(client.player));
            }
            if (check.phase() >= 7 && check.phase() <= 9) {
                var observer = MultiplayerSpellProbe.observer() ? client.player : client.level.getPlayerByUUID(check.peer());
                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] OBSERVER_RECEIPT phase={} actual={}/{} original={}/{}",
                        check.phase(), observer.position(), observer.getHealth(), check.observerOriginal(), check.observerHealth());
            }
            if (check.phase() >= 7) for (var sample : check.entities()) {
                Entity entity = findEntity(client, sample.id());
                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] ENTITY_RECEIPT phase={} id={} position={} original={} expected={} pushed={} health={}",
                        check.phase(), sample.id(), entity.position(), sample.original(), sample.expected(), sample.pushed(), ((LivingEntity) entity).getHealth());
            }
            if(check.phase()>=18&&check.phase()<=20) {
                var victim=MultiplayerSpellProbe.observer()?client.player:client.level.getPlayerByUUID(check.peer());
                LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] FIRE_RECEIPT phase={} trackedShots={} targetHealth={} burning={} firePractice={} learned={} maxTrackedTravel={}",check.phase(),fireSeen.size(),victim.getHealth(),victim.isOnFire(),FireMagic.practiceCount(client.player),FireMagic.isLearned(client.player),fireTravel);
            }
            if(check.phase()>=17&&check.phase()<=20)LogUtils.getLogger().info("[JR_FIRE_FOCUS_MULTIPLAYER] PASS phase={} particles={} maximumProgress={} readySounds={} live={}",check.phase(),FireFocusObservation.created(),FireFocusObservation.largestProgress(),FireFocusObservation.readySounds(),FireFocusObservation.alive());
            pending = null;
        } else if (++waitTicks > 240) {
            String detail = String.join("; ", errors);
            PacketDistributor.sendToServer(new MultiplayerSpellProbe.Ack(check.phase(), false, detail));
            LogUtils.getLogger().error("[JR_MULTIPLAYER_PROBE_CLIENT] FAIL phase={}: {}", check.phase(), detail);
            pending = null;
        }
        // A transparent Screen blocks ordinary unheld-button auto-release in this synthetic server-driven chant.
        // Only public item-use mirroring is performed; private attachment values above come exclusively from network sync.
        if (check.using() && client.player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) {
            if (!client.player.isUsingItem()) client.player.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (client.player.isUsingItem()) client.player.stopUsingItem();
    }

    private static List<String> inspect(Minecraft client, MultiplayerSpellProbe.Check check) {
        List<String> errors = new ArrayList<>();
        var player = client.player;
        if (!player.hasData(ModAttachments.KNOWLEDGE) || !player.hasData(ModAttachments.MANA) || !player.hasData(ModAttachments.CASTING)) {
            errors.add("owner attachments have not arrived; do not substitute defaults");
            return errors;
        }
        if (!player.getData(ModAttachments.CASTING).selectedSpell().toString().equals(check.selected())) errors.add("selected spell mismatch");
        if (WaterMagic.practiceCount(player) != check.water()) errors.add("owner water practice mismatch " + WaterMagic.practiceCount(player));
        if (EarthWallSpell.practiceCount(player) != check.earth()) errors.add("owner earth practice mismatch " + EarthWallSpell.practiceCount(player));
        if (EarthWallSpell.isLearned(player) != check.learnedEarth()) errors.add("owner earth knowledge mismatch");
        if (AirBurstSpell.practiceCount(player) != check.air()) errors.add("owner air practice mismatch " + AirBurstSpell.practiceCount(player));
        if (AirBurstSpell.isLearned(player) != check.learnedAir()) errors.add("owner air knowledge mismatch");
        if (HealingSpell.practiceCount(player) != check.healing()) errors.add("owner healing practice mismatch " + HealingSpell.practiceCount(player));
        if (HealingSpell.isLearned(player) != check.learnedHealing()) errors.add("owner healing knowledge mismatch");
        if(FireMagic.practiceCount(player)!=check.fire()||FireMagic.isLearned(player)!=check.learnedFire())errors.add("owner fire knowledge/practice mismatch");
        if (player.getData(ModAttachments.MANA).mana() != check.mana()) errors.add("owner mana mismatch " + player.getData(ModAttachments.MANA).mana());
        var active = player.getData(ModAttachments.CASTING).activeSpell();
        if (check.using() ? !check.selected().equals(String.valueOf(active)) : active != null) errors.add("owner active spell mismatch");
        var peer = client.level.getPlayerByUUID(check.peer());
        if (peer == null) errors.add("peer entity not received yet");
        else {
            boolean knowledgePresent = peer.hasData(ModAttachments.KNOWLEDGE);
            boolean manaPresent = peer.hasData(ModAttachments.MANA);
            boolean castingPresent = peer.hasData(ModAttachments.CASTING);
            if (knowledgePresent || manaPresent || castingPresent) {
                // Never getData() on an absent attachment: diagnostics must not create the observed state.
                String knowledge = knowledgePresent ? peer.getData(ModAttachments.KNOWLEDGE).snapshot().toString() : "absent";
                String mana = manaPresent ? peer.getData(ModAttachments.MANA).mana() + "/" + peer.getData(ModAttachments.MANA).maximum() : "absent";
                String casting = castingPresent ? peer.getData(ModAttachments.CASTING).selectedSpell() + "/active=" + peer.getData(ModAttachments.CASTING).activeSpell() : "absent";
                String expectedPrivate = "remote knowledge, mana and casting attachments must be wholly absent in every phase";
                String diagnostic = "private attachment on remote=" + peer.getGameProfile().getName() + "; has[k,m,c]="
                        + knowledgePresent + "," + manaPresent + "," + castingPresent + "; knowledge=" + knowledge
                        + "; mana=" + mana + "; casting=" + casting + "; fixture " + expectedPrivate;
                errors.add(diagnostic);
                if (!diagnostic.equals(lastPrivacyDiagnostic)) {
                    lastPrivacyDiagnostic = diagnostic;
                    LogUtils.getLogger().error("[JR_MULTIPLAYER_PROBE_CLIENT] REMOTE_ATTACHMENT_DIAGNOSTIC phase={}: {}", check.phase(), diagnostic);
                }
            }
        }
        // The probe is oriented south: width lies east-west, with exactly two y levels.
        for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) {
            BlockPos pos = check.center().offset(x, y, 0);
            if (!client.level.hasChunkAt(pos)) errors.add("wall chunk not received yet");
            else if (check.wall()) {
                var state = client.level.getBlockState(pos);
                if (!state.is(ModContent.EARTH_WALL.get())) errors.add("wall cell mismatch at " + pos);
                else {
                    if (state.getRenderShape() != RenderShape.MODEL
                            || client.getBlockRenderer().getBlockModel(state) == client.getModelManager().getMissingModel()) {
                        errors.add("wall must retain an ordinary, nonmissing block model at " + pos);
                    }
                    if (!(client.level.getBlockEntity(pos) instanceof EarthWallBlockEntity wall)) {
                        errors.add("client wall block entity not received/created at " + pos);
                    } else {
                        // Both clients, including the owner's process, must lack server-only UUID/cast NBT.
                        var persisted = wall.saveWithoutMetadata(client.level.registryAccess());
                        if (wall.identity().isPresent() || persisted.contains("WallIdentity")
                                || persisted.contains("owner") || persisted.contains("cast")
                                || !wall.getUpdateTag(client.level.registryAccess()).isEmpty()) {
                            errors.add("server-only wall provenance leaked into client block entity at " + pos);
                        }
                    }
                }
            } else if (check.phase() >= 7 && x == 0) {
                if (!client.level.getBlockState(pos).is(Blocks.STONE) || client.level.getBlockEntity(pos) != null)
                    errors.add("expected two ordinary stone occluder cells at " + pos);
            } else if (!client.level.getBlockState(pos).isAir() || client.level.getBlockEntity(pos) != null) {
                errors.add("expected air with no orphan wall block entity at " + pos);
            }
        }
        if (check.phase() >= 10 && check.phase() <= 15) {
            // Read only actual LocalPlayer health packets and ordinary remote player entity data, never the Check values as state.
            if (Math.abs(player.getHealth() - check.ownerHealth()) > 0.001)
                errors.add("owner ordinary health mismatch: actual=" + player.getHealth() + ", expected=" + check.ownerHealth());
            if (peer == null || Math.abs(peer.getHealth() - check.peerHealth()) > 0.001)
                errors.add("remote ordinary health mismatch: actual=" + (peer == null ? "missing" : peer.getHealth()) + ", expected=" + check.peerHealth());
        }
        if (check.phase() >= 7 && check.phase() <= 9) {
            var observer = MultiplayerSpellProbe.observer() ? player : peer;
            // Compare ordinary tracked state to the one immutable server baseline. Never snapshot an interpolating remote teleport.
            if (observer == null || observer.position().distanceToSqr(check.observerOriginal()) > 0.0064
                    || Math.abs(observer.getHealth() - check.observerHealth()) > 0.001) {
                String detail = "ordinary observer position/health mismatch: actual="
                        + (observer == null ? "missing" : observer.position() + "/" + observer.getHealth())
                        + ", original=" + check.observerOriginal() + "/" + check.observerHealth();
                errors.add(detail);
                if (!detail.equals(lastObserverDiagnostic)) {
                    lastObserverDiagnostic = detail;
                    LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] OBSERVER_WAIT phase={}: {}", check.phase(), detail);
                }
            }
        }
        if (check.phase() >= 7 && check.entities().size() != 4) errors.add("air scene requires exactly four bounded targets");
        for (var sample : check.entities()) {
            Entity entity = findEntity(client, sample.id());
            if (!(entity instanceof LivingEntity living)) { errors.add("ordinary tracked target has not arrived: " + sample.id()); continue; }
            if (Math.abs(living.getHealth() - sample.health()) > 0.001) errors.add("target health changed: " + sample.id());
            if (entity.position().distanceToSqr(sample.expected()) > 0.0064) errors.add("target tracked position not converged: " + sample.id());
            if (check.phase() == 9) {
                if (sample.pushed() ? entity.getZ() - sample.original().z <= 0.15
                        : entity.position().distanceToSqr(sample.original()) > 0.0064)
                    errors.add("target displacement/exclusion mismatch: " + sample.id());
            }
        }
        if(check.phase()==17) {
            if(FireFocusObservation.created()<1||FireFocusObservation.largestProgress()<1||FireFocusObservation.readySounds()!=1)
                errors.add("Native focus/one-shot cue not ready: "+FireFocusObservation.created()+"/"+FireFocusObservation.largestProgress()+"/"+FireFocusObservation.readySounds());
        }
        if(check.phase()>=18&&check.phase()<=20) {
            if(fireCheckAge>18&&FireFocusObservation.alive()!=0)errors.add("Released focus particles did not expire");
            var victim=MultiplayerSpellProbe.observer()?player:peer;
            int expectedShots=check.phase()-17;
            if(fireSeen.size()<expectedShots)errors.add("Ordinary tracked fire projectile missing: "+fireSeen.size()+"/"+expectedShots);
            if(fireTravel<4)errors.add("Actual client fire movement not observed: "+fireTravel);
            if(victim==null)errors.add("Fire peer missing");
            else if(check.phase()==18) {
                if(victim.getHealth()>15||victim.getHealth()<12||!victim.isOnFire())errors.add("Expected ordinary impact health/burn, got "+victim.getHealth()+"/"+victim.isOnFire());
            }else if(victim.getHealth()!=20||victim.isOnFire())errors.add("PVP/friendly-fire rejection must keep ordinary peer untouched");
        }
        return errors;
    }

    private static Entity findEntity(Minecraft client, java.util.UUID id) {
        // Read ordinary vanilla tracked entities. No custom packet writes their positions or health.
        for (Entity entity : client.level.entitiesForRendering()) if (entity.getUUID().equals(id)) return entity;
        return null;
    }

    private static void receive(MultiplayerSpellProbe.Check check) {
        if (!MultiplayerSpellProbe.enabled() && !MultiplayerSpellProbe.observer()) return;
        Minecraft client = Minecraft.getInstance();
        LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] CHECK_RECEIVED phase={} paused={} screen={}", check.phase(), client.isPaused(), client.screen == null ? "none" : client.screen.getClass().getSimpleName());
        if (client.player != null && client.level != null && !(client.screen instanceof ProbeScreen)) client.setScreen(new ProbeScreen());
        lastPrivacyDiagnostic = null; lastObserverDiagnostic = null; fireCheckAge=0;
        if(check.phase()==16)FireFocusObservation.reset();
        status = "阶段 " + check.phase() + " · " + check.message();
        if (check.phase() == 99) {
            pending = null;
            finishTicks = MultiplayerSpellProbe.observer() ? 30 : 60;
            LogUtils.getLogger().info("[JR_MULTIPLAYER_PROBE_CLIENT] SERVER_FINISHED {}", check.message());
        } else { pending = check; waitTicks = 0; stableTicks = 0; captureStarted = false; captureDone = false; captureFailure = null; }
    }

    private static final class ProbeScreen extends Screen {
        private ProbeScreen() { super(Component.literal("本机双客户端同步验收")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean shouldCloseOnEsc() { return false; }
        @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(4, height - 34, width - 4, height - 4, 0xB0000000);
            graphics.drawString(font, "开发验收：真实客户端收包；施法由服务端探针驱动", 10, height - 29, 0xFFF2D49A);
            graphics.drawString(font, status, 10, height - 17, 0xFFFFFFFF);
            super.render(graphics, mouseX, mouseY, partialTick);
        }
    }
    private MultiplayerSpellProbeClient() {}
}
