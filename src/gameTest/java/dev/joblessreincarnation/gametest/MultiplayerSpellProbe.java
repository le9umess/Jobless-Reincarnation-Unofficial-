package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.AirBurstSpell;
import dev.joblessreincarnation.magic.HealingSpell;
import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.magic.FireBallProjectile;
import dev.joblessreincarnation.magic.EarthWallSpell;
import dev.joblessreincarnation.magic.EarthWallBlockEntity;
import dev.joblessreincarnation.magic.EarthWallDismissal;
import dev.joblessreincarnation.magic.EarthWallDismantling;
import dev.joblessreincarnation.magic.WallIdentity;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

/** Opt-in, localhost-only two-process integration probe. This whole source set is excluded from release jars. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class MultiplayerSpellProbe {
    public static final String PROPERTY = "jobless_reincarnation.multiplayerProbe";
    public static final int PORT = 25577;
    private static final String WORLD = "JR Magic 0.2.0 Test";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "[JR_MULTIPLAYER_PROBE]";
    public static volatile Consumer<Check> clientReceiver = ignored -> {};
    private static Session session;
    private static boolean attempted;

    public static boolean enabled() { return "host".equals(System.getProperty(PROPERTY)); }
    public static boolean observer() { return "observer".equals(System.getProperty(PROPERTY)); }

    /** Four bounded entity samples are public scene expectations, never copied into client entities. */
    public record EntitySample(UUID id, Vec3 original, Vec3 expected, float health, boolean pushed) {
        public EntitySample {
            if (id == null || !validPosition(original) || !validPosition(expected)
                    || !Float.isFinite(health) || health < 0 || health > 1024) throw new IllegalArgumentException("Invalid entity sample");
        }
        private EntitySample(FriendlyByteBuf buf) {
            this(buf.readUUID(), readPosition(buf), readPosition(buf), buf.readFloat(), buf.readBoolean());
        }
        private void write(FriendlyByteBuf buf) {
            buf.writeUUID(id); writePosition(buf, original); writePosition(buf, expected); buf.writeFloat(health); buf.writeBoolean(pushed);
        }
        private static boolean validPosition(Vec3 value) {
            return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                    && Math.abs(value.x) <= 30000000 && Math.abs(value.z) <= 30000000 && Math.abs(value.y) <= 4096;
        }
        private static Vec3 readPosition(FriendlyByteBuf buf) { return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()); }
        private static void writePosition(FriendlyByteBuf buf, Vec3 value) { buf.writeDouble(value.x); buf.writeDouble(value.y); buf.writeDouble(value.z); }
    }

    public record Check(int phase, UUID peer, String selected, int water, int earth, boolean learnedEarth,
            int mana, BlockPos center, boolean wall, boolean using, int air, boolean learnedAir,
            List<EntitySample> entities, Vec3 observerOriginal, float observerHealth,
            int healing, boolean learnedHealing, float ownerHealth, float peerHealth, int fire, boolean learnedFire, String message) implements CustomPacketPayload {
        public Check {
            entities = List.copyOf(entities);
            if (entities.size() > 4 || air < 0 || air > 1000000 || healing < 0 || healing > 1000000 || fire < 0 || fire > 1000000
                    || !EntitySample.validPosition(observerOriginal)
                    || !Float.isFinite(observerHealth) || observerHealth < 0 || observerHealth > 1024
                    || !Float.isFinite(ownerHealth) || ownerHealth < 0 || ownerHealth > 1024
                    || !Float.isFinite(peerHealth) || peerHealth < 0 || peerHealth > 1024)
                throw new IllegalArgumentException("Unbounded development check");
        }
        public static final Type<Check> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "dev_multiplayer_check"));
        public static final StreamCodec<FriendlyByteBuf, Check> STREAM_CODEC = CustomPacketPayload.codec(Check::write, Check::new);
        private Check(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readUUID(), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readVarInt(), buf.readBlockPos(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(),
                    readEntities(buf), EntitySample.readPosition(buf), buf.readFloat(), buf.readVarInt(), buf.readBoolean(),
                    buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(1024));
        }
        private static List<EntitySample> readEntities(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > 4) throw new IllegalArgumentException("Entity sample count must be 0..4");
            List<EntitySample> entities = new ArrayList<>(count);
            for (int index = 0; index < count; index++) entities.add(new EntitySample(buf));
            return entities;
        }
        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(phase); buf.writeUUID(peer); buf.writeUtf(selected, 128); buf.writeVarInt(water); buf.writeVarInt(earth);
            buf.writeBoolean(learnedEarth); buf.writeVarInt(mana); buf.writeBlockPos(center); buf.writeBoolean(wall); buf.writeBoolean(using);
            buf.writeVarInt(air); buf.writeBoolean(learnedAir); buf.writeVarInt(entities.size()); entities.forEach(entity -> entity.write(buf));
            EntitySample.writePosition(buf, observerOriginal); buf.writeFloat(observerHealth);
            buf.writeVarInt(healing); buf.writeBoolean(learnedHealing); buf.writeFloat(ownerHealth); buf.writeFloat(peerHealth);
            buf.writeVarInt(fire); buf.writeBoolean(learnedFire); buf.writeUtf(message, 1024);
        }
        @Override public Type<Check> type() { return TYPE; }
    }

    public record Ack(int phase, boolean passed, String detail) implements CustomPacketPayload {
        public static final Type<Ack> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "dev_multiplayer_ack"));
        public static final StreamCodec<FriendlyByteBuf, Ack> STREAM_CODEC = CustomPacketPayload.codec(Ack::write, Ack::new);
        private Ack(FriendlyByteBuf buf) { this(buf.readVarInt(), buf.readBoolean(), buf.readUtf(1024)); }
        private void write(FriendlyByteBuf buf) { buf.writeVarInt(phase); buf.writeBoolean(passed); buf.writeUtf(detail, 1024); }
        @Override public Type<Ack> type() { return TYPE; }
    }

    @EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Network {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
            event.registrar("dev-multiplayer-5")
                    .playToClient(Check.TYPE, Check.STREAM_CODEC, (packet, context) -> clientReceiver.accept(packet))
                    .playToServer(Ack.TYPE, Ack.STREAM_CODEC, (packet, context) -> {
                        if (context.player() instanceof ServerPlayer player) acknowledge(player, packet);
                    });
        }
    }

    private record Snapshot(CompoundTag player, ServerLevel level, GameType mode, Vec3 position, float yaw, float pitch) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID host;
        final Map<UUID, Snapshot> snapshots = new HashMap<>();
        final Map<UUID, Boolean> acks = new HashMap<>();
        final Difficulty difficulty;
        final long dayTime;
        final boolean authentication, mobSpawning, naturalRegeneration, pvp;
        net.minecraft.world.scores.PlayerTeam fireTeam;
        String hostOriginalTeam, peerOriginalTeam;
        boolean fireReleased;
        final Map<BlockPos, BlockState> temporaryBlocks = new HashMap<>();
        final Map<UUID, EntitySample> airEntities = new HashMap<>();
        Vec3 airPeerPosition;
        float airPeerHealth;
        boolean airReleased, healingRejectStarted;
        int airReleaseTick;
        UUID observer;
        BlockPos center = BlockPos.ZERO;
        List<BlockPos> cells = List.of();
        int phase, ticks, phaseTicks;
        boolean restored, wallCreated;
        WallIdentity wallIdentity;
        Session(MinecraftServer server, UUID host) {
            this.server = server; this.host = host;
            difficulty = server.getWorldData().getDifficulty(); dayTime = server.overworld().getDayTime();
            authentication = server.usesAuthentication(); pvp = server.isPvpAllowed();
            mobSpawning = server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            naturalRegeneration = server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        }
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || attempted || !(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.isDedicatedServer() || server.isPublished()
                || !server.isSingleplayerOwner(player.getGameProfile()) || !WORLD.equals(server.getWorldData().getLevelName())
                || player.level().dimension() != Level.OVERWORLD || !player.isAlive()) return;
        attempted = true;
        session = new Session(server, player.getUUID());
        LOGGER.info("{} HOST_SCHEDULED: exact private test world, localhost port {} only", PREFIX, PORT);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (!enabled() || current == null || current.server != event.getServer() || current.restored) return;
        try {
            current.ticks++; current.phaseTicks++;
            if (current.ticks > 12000 || (current.phase >= 1 && current.phaseTicks > 500)) throw new IllegalStateException("Probe phase timed out: " + current.phase);
            ServerPlayer host = current.server.getPlayerList().getPlayer(current.host);
            if (host == null) throw new IllegalStateException("Host left");
            if (current.phase == 0) {
                if (current.ticks == 80) {
                    current.server.setUsesAuthentication(false);
                    current.server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), PORT);
                    LOGGER.info("{} READY: TCP listening only on loopback; waiting for JRObserver", PREFIX);
                }
                if (current.ticks < 80) return;
                ServerPlayer peer = current.server.getPlayerList().getPlayers().stream()
                        .filter(player -> !player.getUUID().equals(current.host) && player.getGameProfile().getName().equals("JRObserver")).findFirst().orElse(null);
                if (peer != null && current.ticks > 100) prepare(current, host, peer);
                return;
            }
            ServerPlayer peer = current.server.getPlayerList().getPlayer(current.observer);
            if (peer == null) throw new IllegalStateException("Observer disconnected before cleanup");
            if (current.phase == 7 || current.phase == 8) {
                for (EntitySample sample : current.airEntities.values()) {
                    var target = current.server.overworld().getEntity(sample.id());
                    if (current.phaseTicks == 20 || current.phaseTicks == 120) LOGGER.info(
                            "{} AIR_ENTITY_DIAGNOSTIC phase={} id={} present={} type={} alive={} position={} health={} difficulty={}",
                            PREFIX, current.phase, sample.id(), target != null, target == null ? "missing" : target.getType(),
                            target != null && target.isAlive(), target == null ? "missing" : target.position(),
                            target instanceof LivingEntity living ? living.getHealth() : -1, current.server.overworld().getDifficulty());
                    require(target instanceof LivingEntity living && living.isAlive() && living.getHealth() == sample.health(),
                            "Temporary air target lost health/aliveness before completion: " + sample.id()
                                    + "; type=" + (target == null ? "missing" : target.getType())
                                    + "; health=" + (target instanceof LivingEntity living ? living.getHealth() : -1)
                                    + "; difficulty=" + current.server.overworld().getDifficulty());
                }
            }
            // The test fixture deliberately pauses mana regeneration; resource assertions below check actual release first.
            if (current.phase == 1 && bothAcked(current)) {
                ModContent.TRAINING_STAFF.get().use(host.level(), host, InteractionHand.MAIN_HAND);
                require(host.isUsingItem() && SpellCatalog.active(host) == SpellCatalog.EARTH_WALL, "Real staff entry must begin the earth chant");
                phase(current, 2, "real staff chant locked; each client checks only its owner's active spell");
            } else if (current.phase == 2 && bothAcked(current) && host.getTicksUsingItem() >= EarthWallSpell.chantTicks(host) + 2) {
                int before = host.getData(ModAttachments.MANA).mana();
                host.releaseUsingItem();
                current.wallCreated = host.getData(ModAttachments.MANA).mana() == before - MagicConfig.EARTH_WALL_COST.getAsInt() && EarthWallSpell.practiceCount(host) == 8;
                require(host.getData(ModAttachments.MANA).mana() == before - MagicConfig.EARTH_WALL_COST.getAsInt(), "Real release must pay exactly one wall fee");
                require(EarthWallSpell.practiceCount(host) == 8 && WaterMagic.practiceCount(host) == 3, "Earth release must advance only host earth practice");
                require(host.serverLevel().getBlockEntity(current.center) instanceof EarthWallBlockEntity, "Created center must have its real server block entity");
                current.wallIdentity = ((EarthWallBlockEntity) host.serverLevel().getBlockEntity(current.center)).identity().orElseThrow();
                require(current.wallIdentity.owner().equals(host.getUUID()), "Created wall must belong to the host");
                requireWall(current);
                freezeMana(host);
                LOGGER.info("{} STAFF_RELEASE_PASS: actual item use/release, mana {}->{}, earth 7->8, water remains 3", PREFIX, before, host.getData(ModAttachments.MANA).mana());
                phase(current, 3, "real wall block updates and independent private data");
            } else if (current.phase == 3 && bothAcked(current)) {
                peer.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
                peer.getData(ModAttachments.CASTING).select(EarthWallSpell.SPELL);
                peer.teleportTo(host.serverLevel(), host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
                int before = peer.getData(ModAttachments.MANA).mana();
                require(!EarthWallSpell.plan(peer).valid(), "Contender must be aimed at the already occupied wall, not a new site");
                require(!EarthWallSpell.cast(peer), "Second server-ordered release cannot build into the completed wall");
                require(peer.getData(ModAttachments.MANA).mana() == before && EarthWallSpell.practiceCount(peer) == 0
                        && WaterMagic.practiceCount(peer) == 11, "Losing contender must pay nothing and gain no practice");
                sync(peer);
                LOGGER.info("{} CONTENTION_PASS: two real players, server-ordered same-site attempts, one wall/one fee; second attempt uses direct spell service", PREFIX);
                phase(current, 4, "observer learned/selected earth independently; no second fee or practice");
            } else if (current.phase == 4 && bothAcked(current)) {
                // This is a service-level ownership attack by the actual observer player, not a physical H-key test.
                aim(peer, Vec3.atCenterOf(current.center));
                require(EarthWallSpell.isLearned(peer) && SpellCatalog.selected(peer) == SpellCatalog.EARTH_WALL
                        && peer.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                        && !peer.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                        && peer.getData(ModAttachments.MANA).canCast(MagicConfig.EARTH_DISMISS_COST.getAsInt(), false),
                        "Observer must satisfy knowledge, wand and resources; rejection cannot be a cooldown or unknown-spell no-op");
                var rejected = EarthWallDismissal.plan(peer);
                require(!rejected.valid() && rejected.identity() == null
                        && rejected.reason().equals(Component.translatable("message.jobless_reincarnation.dismiss.not_owner")),
                        "Observer must ray-hit the host wall and receive the explicit ownership rejection without identity disclosure");
                int hostMana = host.getData(ModAttachments.MANA).mana(), peerMana = peer.getData(ModAttachments.MANA).mana();
                require(!EarthWallDismissal.dismiss(peer, current.wallIdentity, peer.getMainHandItem()), "Observer cannot remove the host's wall even with its server identity");
                requireWall(current);
                require(peer.getData(ModAttachments.MANA).mana() == peerMana && peerMana == 83
                        && host.getData(ModAttachments.MANA).mana() == hostMana
                        && EarthWallSpell.practiceCount(peer) == 0 && EarthWallSpell.practiceCount(host) == 8
                        && WaterMagic.practiceCount(peer) == 11 && WaterMagic.practiceCount(host) == 3,
                        "Foreign-wall dismissal must cost neither player mana and award no practice");
                sync(peer); sync(host);
                LOGGER.info("{} FOREIGN_DISMISSAL_PASS: learned observer ray-hit host wall, explicit not_owner, six owned cells retained, no payment or practice; direct service, not manual H", PREFIX);
                phase(current, 5, "foreign-wall dismissal rejected; both clients retain wall; private block-entity provenance absent remotely");
            } else if (current.phase == 5 && bothAcked(current)
                    && host.getData(ModAttachments.MANA).cooldown() == 0
                    && !host.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                // Wait for the real shared cooldown above; never clear it to manufacture a successful dismissal.
                aim(host, Vec3.atCenterOf(current.center));
                var removal = EarthWallDismissal.plan(host);
                require(removal.valid() && current.wallIdentity.equals(removal.identity()) && removal.positions().size() == 6,
                        "Owner must target all six remaining cells of the original wall");
                requireWall(current);
                int before = host.getData(ModAttachments.MANA).mana(), peerMana = peer.getData(ModAttachments.MANA).mana();
                int cost = MagicConfig.EARTH_DISMISS_COST.getAsInt();
                require(EarthWallDismissal.dismiss(host, current.wallIdentity, host.getMainHandItem()), "Actual owner dismissal must commit, not no-op");
                require(host.getData(ModAttachments.MANA).mana() == before - cost
                        && peer.getData(ModAttachments.MANA).mana() == peerMana && peerMana == 83,
                        "Only the owner must pay exactly one dismissal fee");
                require(EarthWallSpell.practiceCount(host) == 8 && EarthWallSpell.practiceCount(peer) == 0
                        && WaterMagic.practiceCount(host) == 3 && WaterMagic.practiceCount(peer) == 11,
                        "Dismissal must award neither player earth or water practice");
                for (BlockPos pos : current.cells) require(host.serverLevel().getBlockState(pos).isAir()
                        && host.serverLevel().getBlockEntity(pos) == null, "Committed removal must leave six actual air cells with no orphan block entities");
                freezeMana(host); sync(host); sync(peer);
                LOGGER.info("{} OWNER_DISMISSAL_PASS: after natural shared cooldown, actual direct service removed six cells; host mana {}->{}, cost={}, observer mana=83, earth remains host8/observer0; not manual H", PREFIX, before, host.getData(ModAttachments.MANA).mana(), cost);
                phase(current, 6, "owner wall dismissed; both clients receive six air cells; only owner pays; no practice awarded");
            } else if (current.phase == 6 && bothAcked(current)) {
                prepareAir(current, host, peer);
                phase(current, 7, "air selected only by host; four temporary entities, two stone occluders, original owner privacy retained");
            } else if (current.phase == 7 && bothAcked(current)
                    && host.getData(ModAttachments.MANA).cooldown() == 0
                    && !host.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                ModContent.TRAINING_STAFF.get().use(host.level(), host, InteractionHand.MAIN_HAND);
                require(host.isUsingItem() && SpellCatalog.active(host) == SpellCatalog.AIR_BURST, "Real staff must lock the air chant");
                phase(current, 8, "actual staff air chant; ordinary tracked mobs and owner-only air state");
            } else if (current.phase == 8 && !current.airReleased && bothAcked(current)
                    && host.getTicksUsingItem() >= AirBurstSpell.chantTicks(host) + 2) {
                int before = host.getData(ModAttachments.MANA).mana();
                host.releaseUsingItem();
                require(host.getData(ModAttachments.MANA).mana() == before - MagicConfig.AIR_BURST_COST.getAsInt(),
                        "Real staff release must pay exactly one air fee");
                require(AirBurstSpell.practiceCount(host) == 1 && AirBurstSpell.practiceCount(peer) == 0
                        && WaterMagic.practiceCount(host) == 3 && WaterMagic.practiceCount(peer) == 11
                        && EarthWallSpell.practiceCount(host) == 8 && EarthWallSpell.practiceCount(peer) == 0,
                        "Only host air practice may advance");
                for (EntitySample sample : current.airEntities.values()) {
                    LivingEntity entity = airEntity(current, sample.id());
                    require(entity.getHealth() == sample.health(), "Air release must not directly damage any target");
                    if (sample.pushed()) require(entity.getDeltaMovement().z > 0.1 && entity.getDeltaMovement().horizontalDistanceSqr() > 0.01,
                            "Both clear hostile targets must acquire real southward motion");
                    else require(entity.getDeltaMovement().lengthSqr() < 0.000001,
                            "Occluded hostile target and ordinary animal must not acquire motion");
                }
                require(peer.getHealth() == current.airPeerHealth && peer.position().distanceToSqr(current.airPeerPosition) < 0.0001
                        && peer.getDeltaMovement().horizontalDistanceSqr() < 0.000001,
                        "Ordinary observer player must not be pushed");
                current.airReleased = true; current.airReleaseTick = current.ticks;
                freezeMana(host); sync(host); sync(peer);
                LOGGER.info("{} AIR_RELEASE_PASS: real staff use/release; mana {}->{}, host air 0->1, no direct damage, two hostile impulses, occluded hostile/cow/player retained; not manual mouse", PREFIX, before, host.getData(ModAttachments.MANA).mana());
            } else if (current.phase == 8 && current.airReleased && current.ticks - current.airReleaseTick >= 4) {
                freezeAirTargets(current, peer);
                phase(current, 9, "air release: real tracked hostile displacement; occluded hostile/cow/player unchanged; native near-pulse frame");
            } else if (current.phase == 9 && bothAcked(current)) {
                prepareHealing(current, host, peer);
                phase(current, 10, "healing selected only by host; host health 10, observer health 12; natural regeneration temporarily disabled");
            } else if (current.phase == 10 && bothAcked(current)
                    && host.getData(ModAttachments.MANA).cooldown() == 0
                    && !host.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                ModContent.TRAINING_STAFF.get().use(host.level(), host, InteractionHand.MAIN_HAND);
                require(host.isUsingItem() && SpellCatalog.active(host) == SpellCatalog.HEALING,
                        "Real staff must lock the healing chant");
                phase(current, 11, "actual staff healing chant aimed at injured observer; ordinary health plus owner-only healing knowledge");
            } else if (current.phase == 11 && bothAcked(current)
                    && host.getTicksUsingItem() >= HealingSpell.chantTicks(host) + 2) {
                int before = host.getData(ModAttachments.MANA).mana();
                host.releaseUsingItem();
                require(before == 48 && host.getData(ModAttachments.MANA).mana() == 24,
                        "Real healing release must pay exactly one 24-mana fee from the original air remainder");
                require(host.getHealth() == 10.0F && peer.getHealth() == 16.0F,
                        "Healing must restore only the aimed observer from 12 to 16 health, not the injured caster");
                require(HealingSpell.practiceCount(host) == 1 && HealingSpell.practiceCount(peer) == 0,
                        "Only the caster may gain one healing practice");
                requirePreviousPractice(host, peer);
                freezeMana(host); sync(host); sync(peer);
                LOGGER.info("{} HEALING_RELEASE_PASS: actual staff use/release; host mana 48->24, healing 0->1; observer health 12->16, injured caster remains 10; natural regeneration disabled; not manual mouse", PREFIX);
                phase(current, 12, "healing release: observer health 12 to 16 through ordinary health sync; injured caster stays 10; only caster spends mana/practices");
            } else if (current.phase == 12 && bothAcked(current) && !current.healingRejectStarted
                    && host.getData(ModAttachments.MANA).cooldown() == 0
                    && !host.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) {
                // Actual server-driven staff use is retained; the transparent probe screen blocks unheld-button auto-release.
                // No extra Check phase is needed while we hold this second chant, and no client health is ever fabricated.
                peer.setHealth(peer.getMaxHealth());
                aim(host, peer.getBoundingBox().getCenter());
                ModContent.TRAINING_STAFF.get().use(host.level(), host, InteractionHand.MAIN_HAND);
                require(host.isUsingItem() && SpellCatalog.active(host) == SpellCatalog.HEALING,
                        "Full-health rejection must run through a real healing staff chant after natural cooldown");
                current.healingRejectStarted = true;
            } else if (current.phase == 12 && current.healingRejectStarted
                    && host.getTicksUsingItem() >= HealingSpell.chantTicks(host) + 2) {
                host.releaseUsingItem();
                require(peer.getHealth() == peer.getMaxHealth() && host.getHealth() == 10.0F,
                        "Full-health peer must block self fallback even though caster is injured");
                require(host.getData(ModAttachments.MANA).mana() == 24 && host.getData(ModAttachments.MANA).cooldown() == 0
                        && !host.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())
                        && HealingSpell.practiceCount(host) == 1 && HealingSpell.practiceCount(peer) == 0,
                        "Full-health rejection must be free, with no cooldown or healing practice");
                requirePreviousPractice(host, peer);
                freezeMana(host); sync(host); sync(peer);
                LOGGER.info("{} HEALING_FULL_REJECT_PASS: actual staff use/release at full observer; host remains injured at 10, mana remains 24, healing remains 1, no cooldown or fallback", PREFIX);
                phase(current, 13, "full-health observer rejection: actual second chant/release costs nothing; injured caster receives no fallback heal");
            } else if (current.phase == 13 && bothAcked(current)) {
                phase(current, 14, "each real client sends a normal direct-selection request; owner-only Air/Water selection must arrive");
            } else if (current.phase == 14 && bothAcked(current)) {
                requireJournalState(host, peer);
                LOGGER.info("{} JOURNAL_SELECT_PASS: actual C2S OpenJournal/SelectSpell requests selected host Air and observer Water; no mana, health, knowledge or practice changes", PREFIX);
                phase(current, 15, "host unknown id and observer unlearned Healing requests must be rejected without state changes");
            } else if (current.phase == 15 && bothAcked(current)) {
                requireJournalState(host, peer);
                require(!HealingSpell.isLearned(peer), "Rejected journal selection cannot grant knowledge");
                LOGGER.info("{} JOURNAL_REJECT_PASS: actual C2S unknown/unlearned requests retained selection and all resources; peer healing remains unknown", PREFIX);
                prepareFire(current, host, peer);
                phase(current, 16, "fire selected only by host; ordinary peer at 20 health twelve blocks ahead; no private data on remote players");
            } else if (current.phase == 16 && bothAcked(current)) {
                ModContent.TRAINING_STAFF.get().use(host.level(), host, InteractionHand.MAIN_HAND);
                require(SpellCatalog.active(host) == SpellCatalog.FIRE_BALL, "Fire staff chant lock");
                phase(current, 17, "actual staff fire chant, with server-owned release; both clients must see only their own private state");
            } else if (current.phase == 17 && bothAcked(current) && host.getTicksUsingItem() >= FireMagic.chantTicks(host) + 3) {
                host.releaseUsingItem();
                require(host.getData(ModAttachments.MANA).mana() == 84 && FireMagic.practiceCount(host) == 1, "Fire release pays once");
                current.fireReleased = true;
                phase(current, 18, "ordinary tracked fire projectile must arrive on both clients, then peer loses direct health and receives vanilla fire flag");
            } else if (current.phase == 18 && bothAcked(current)) {
                require(peer.getHealth() <= 15 && peer.getHealth() >= 12 && peer.isOnFire(), "Real player receives fire damage and burn");
                current.server.setPvpAllowed(false);
                prepareFireRejection(current, host, peer);
                require(FireMagic.cast(host), "PVP-disabled spell still releases into world");
                phase(current, 19, "PVP disabled: second genuine projectile may travel but must never hurt or ignite peer");
            } else if (current.phase == 19 && bothAcked(current) && current.phaseTicks >= 35) {
                require(peer.getHealth() == 20 && !peer.isOnFire(), "PVP-disabled real player protected");
                current.server.setPvpAllowed(true);
                var board=current.server.getScoreboard();
                current.hostOriginalTeam=host.getTeam()==null?null:host.getTeam().getName();
                current.peerOriginalTeam=peer.getTeam()==null?null:peer.getTeam().getName();
                current.fireTeam=board.addPlayerTeam("jr_fire_"+UUID.randomUUID().toString().substring(0,8));
                current.fireTeam.setAllowFriendlyFire(false);
                board.addPlayerToTeam(host.getScoreboardName(),current.fireTeam);board.addPlayerToTeam(peer.getScoreboardName(),current.fireTeam);
                prepareFireRejection(current, host, peer);
                require(FireMagic.cast(host), "Friendly-fire-disabled spell still releases");
                phase(current, 20, "same team with friendly fire disabled: third genuine projectile must never hurt or ignite peer");
            } else if (current.phase == 20 && bothAcked(current) && current.phaseTicks >= 35) {
                require(peer.getHealth() == 20 && !peer.isOnFire(), "Same-team real player protected");
                finish(current, true, "Two real clients confirmed 20 stages, including tracked fire, ordinary player health/fire, PVP-off and friendly-fire-off rejection; native input checked separately by FireProbe");
            }
        } catch (Exception failure) {
            LOGGER.error("{} FAILED", PREFIX, failure);
            finish(current, false, failure.toString());
        }
    }

    private static void prepareFire(Session current, ServerPlayer host, ServerPlayer peer) {
        current.server.setPvpAllowed(true);
        host.getData(ModAttachments.KNOWLEDGE).learn(FireMagic.SPELL);
        host.getData(ModAttachments.CASTING).select(FireMagic.SPELL);
        host.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));
        host.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        double x=current.center.getX()+3.5,y=current.center.getY(),z=current.center.getZ()-3.5;
        host.teleportTo(host.serverLevel(),x,y,z,0,0);host.setYHeadRot(0);host.setDeltaMovement(Vec3.ZERO);
        peer.teleportTo(peer.serverLevel(),x,y,z+12,180,0);peer.setYHeadRot(180);peer.setDeltaMovement(Vec3.ZERO);
        peer.setHealth(20);peer.clearFire();peer.invulnerableTime=0;
        aim(host,peer.getEyePosition().add(0,-0.2,0));sync(host);sync(peer);
    }
    private static void prepareFireRejection(Session current,ServerPlayer host,ServerPlayer peer) {
        host.setData(ModAttachments.MANA,new ManaData(100,100,6000,0));host.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        peer.setHealth(20);peer.clearFire();peer.invulnerableTime=0;peer.setDeltaMovement(Vec3.ZERO);
        peer.teleportTo(peer.serverLevel(),host.getX(),host.getY(),host.getZ()+12,180,0);
        aim(host,peer.getEyePosition().add(0,-0.2,0));sync(host);sync(peer);
    }

    private static void requireJournalState(ServerPlayer host, ServerPlayer peer) {
        require(SpellCatalog.selected(host) == SpellCatalog.AIR_BURST && SpellCatalog.selected(peer) == SpellCatalog.WATER_BOLT,
                "Each real client must change only its own selected spell");
        require(host.getData(ModAttachments.MANA).mana() == 24 && peer.getData(ModAttachments.MANA).mana() == 83
                && host.getData(ModAttachments.MANA).cooldown() == 0 && peer.getData(ModAttachments.MANA).cooldown() == 0,
                "Journal selection must not spend or restore mana or cooldown");
        require(host.getHealth() == 10 && peer.getHealth() == 20 && !host.isUsingItem() && !peer.isUsingItem()
                && SpellCatalog.active(host) == null && SpellCatalog.active(peer) == null,
                "Journal packets must not cast, heal, or retain active spell locks");
        requirePreviousPractice(host, peer);
        require(HealingSpell.practiceCount(host) == 1 && HealingSpell.practiceCount(peer) == 0,
                "Journal packets must not grant healing practice");
    }
    private static void prepare(Session current, ServerPlayer host, ServerPlayer peer) throws Exception {
        current.observer = peer.getUUID();
        takeSnapshot(current, host); takeSnapshot(current, peer);
        journal(current, false); // Persist original player NBT before any temporary changes.
        current.server.setDifficulty(Difficulty.PEACEFUL, true);
        current.server.overworld().setDayTime(6000);
        host.closeContainer(); peer.closeContainer();
        preparePlayer(host, 3, 7, true);
        preparePlayer(peer, 11, 0, false);
        boolean found = false;
        BlockPos origin = host.blockPosition();
        for (int offset = 10; offset <= 28 && !found; offset += 3) {
            int x = origin.getX() + offset, z = origin.getZ() + 8;
            BlockPos probe = new BlockPos(x, origin.getY(), z);
            if (!host.serverLevel().hasChunkAt(probe) || !host.serverLevel().hasChunkAt(probe.offset(5, 0, -5))) continue;
            int y = host.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            host.teleportTo(current.server.overworld(), x + 0.5, y, z - 3.5, 0, 0);
            Vec3 target = new Vec3(x + 0.5, y, z + 0.5);
            aim(host, target);
            EarthWallSpell.Plan plan = EarthWallSpell.plan(host);
            if (!plan.valid()) continue;
            require(plan.positions().stream().allMatch(pos -> host.serverLevel().getBlockState(pos).isAir()
                    && host.serverLevel().getBlockEntity(pos) == null), "Fixture must occupy only original air without block entities");
            current.center = plan.positions().getFirst(); current.cells = plan.positions(); found = true;
            peer.teleportTo(current.server.overworld(), x + 4.5, y, z - 2.5, 25, 15);
        }
        require(found, "Could not find a fully empty supported wall site near the original player");
        journal(current, false); // Also records the six originally-air cells for crash recovery.
        sync(host); sync(peer);
        LOGGER.info("{} PREPARED: host={}, observer={}, wall center={}, original state journaled", PREFIX, current.host, current.observer, current.center);
        phase(current, 1, "baseline private knowledge/practice/selection; host mana 100, observer mana 83");
    }

    private static void prepareAir(Session current, ServerPlayer host, ServerPlayer peer) throws Exception {
        current.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, current.server);
        current.server.setDifficulty(Difficulty.EASY, true);
        BlockPos barrier = current.center;
        for (BlockPos pos : List.of(barrier, barrier.above())) {
            require(host.serverLevel().getBlockState(pos).isAir() && host.serverLevel().getBlockEntity(pos) == null,
                    "Only the original two air cells may become temporary stone occluders");
            current.temporaryBlocks.put(pos.immutable(), host.serverLevel().getBlockState(pos));
        }
        double x = barrier.getX() + 0.5, y = barrier.getY(), z = barrier.getZ() - 3.5;
        host.teleportTo(host.serverLevel(), x, y, z, 0, 0);
        host.setYHeadRot(0); host.setDeltaMovement(Vec3.ZERO);
        peer.teleportTo(host.serverLevel(), x + 0.35, y, z + 1.6, 0, 0);
        peer.setYHeadRot(0); peer.setDeltaMovement(Vec3.ZERO);
        current.airPeerPosition = peer.position(); current.airPeerHealth = peer.getHealth();
        host.getData(ModAttachments.KNOWLEDGE).learn(AirBurstSpell.SPELL);
        host.getData(ModAttachments.CASTING).select(AirBurstSpell.SPELL);
        List<Mob> mobs = List.of(
                createAirMob(current, EntityType.HUSK, new Vec3(x - 1.0, y, z + 2.7), true),
                createAirMob(current, EntityType.HUSK, new Vec3(x + 1.0, y, z + 2.7), true),
                createAirMob(current, EntityType.HUSK, new Vec3(x, y, z + 5.0), false),
                createAirMob(current, EntityType.COW, new Vec3(x, y, z + 3.0), false));
        journal(current, false); // Journal UUIDs and original block states before adding anything to the world.
        for (BlockPos pos : current.temporaryBlocks.keySet()) host.serverLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Mob mob : mobs) {
            // The rear husk must clear the placed wall with its whole .6-wide body, not only its center point.
            require(host.serverLevel().noCollision(mob), "Placed occluders must not intersect a temporary target: " + mob.getUUID());
            require(host.serverLevel().addFreshEntity(mob), "Temporary entity must enter ordinary server tracking");
            require(mob.isAlive() && mob.getHealth() == current.airEntities.get(mob.getUUID()).health(), "Spawned target health must equal its original baseline");
            LOGGER.info("{} AIR_ENTITY_SPAWN: id={} type={} position={} bounds={} health={} pushable={} difficulty={}",
                    PREFIX, mob.getUUID(), mob.getType(), mob.position(), mob.getBoundingBox(), mob.getHealth(), mob.isPushable(), host.serverLevel().getDifficulty());
        }
        sync(host); sync(peer);
        LOGGER.info("{} AIR_FIXTURE: 3 no-gravity husks and 1 cow with empty goals but normal vanilla travel; exactly two original-air stone occluders; Easy plus disabled natural spawning, all journaled for restoration", PREFIX);
    }

    private static Mob createAirMob(Session current, EntityType<? extends Mob> type, Vec3 position, boolean pushed) {
        Mob entity = type.create(current.server.overworld());
        require(entity != null, "Temporary entity type must be creatable");
        entity.moveTo(position.x, position.y, position.z, 180, 0);
        // NoAI disables Mob.isEffectiveAi -> Entity.isControlledByLocalInstance -> LivingEntity.travel.
        // Keep normal vanilla physics and remove only autonomous goals on these fresh temporary mobs.
        entity.setNoAi(false); entity.goalSelector.removeAllGoals(goal -> true); entity.targetSelector.removeAllGoals(goal -> true);
        entity.setTarget(null); entity.getNavigation().stop();
        entity.setNoGravity(true); entity.setPersistenceRequired(); entity.setDeltaMovement(Vec3.ZERO);
        require(entity.isEffectiveAi() && entity.isControlledByLocalInstance() && entity.isPushable(),
                "Fresh goal-free targets must retain ordinary server-authoritative travel and pushability");
        require(current.server.overworld().noCollision(entity), "Temporary target must have an empty collision volume");
        current.airEntities.put(entity.getUUID(), new EntitySample(entity.getUUID(), position, position, entity.getHealth(), pushed));
        return entity;
    }

    private static LivingEntity airEntity(Session current, UUID id) {
        var entity = current.server.overworld().getEntity(id);
        require(entity instanceof LivingEntity && entity.isAlive(), "Tracked temporary entity must remain alive");
        return (LivingEntity) entity;
    }

    private static void freezeAirTargets(Session current, ServerPlayer peer) {
        for (EntitySample sample : List.copyOf(current.airEntities.values())) {
            LivingEntity entity = airEntity(current, sample.id());
            require(entity.getHealth() == sample.health(), "Temporary targets must retain original health after release");
            if (sample.pushed()) require(entity.getZ() - sample.original().z > 0.15,
                    "Two ordinary tracked hostiles must physically move south after the impulse");
            else require(entity.position().distanceToSqr(sample.original()) < 0.0001,
                    "Occluded hostile and ordinary animal must remain at their original positions");
            entity.setDeltaMovement(Vec3.ZERO); entity.hurtMarked = true;
            current.airEntities.put(sample.id(), new EntitySample(sample.id(), sample.original(), entity.position(), sample.health(), sample.pushed()));
        }
        require(peer.getHealth() == current.airPeerHealth && peer.position().distanceToSqr(current.airPeerPosition) < 0.0001,
                "The in-cone observer player must retain their position");
        LOGGER.info("{} AIR_PHYSICS_PASS: four real server ticks produced two hostile displacements, occluded hostile/cow/player unchanged, all four target health values unchanged", PREFIX);
    }

    private static void prepareHealing(Session current, ServerPlayer host, ServerPlayer peer) throws Exception {
        journal(current, false); // Original gamerules and both full player NBT snapshots already exist before health changes.
        current.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, current.server);
        host.setHealth(10.0F); peer.setHealth(12.0F);
        host.setDeltaMovement(Vec3.ZERO); peer.setDeltaMovement(Vec3.ZERO);
        host.getData(ModAttachments.KNOWLEDGE).learn(HealingSpell.SPELL);
        host.getData(ModAttachments.CASTING).select(HealingSpell.SPELL);
        aim(host, peer.getBoundingBox().getCenter());
        require(host.getEyePosition().distanceTo(peer.getBoundingBox().getCenter()) < 4.0,
                "Healing peer must be inside the four-block targeting range");
        require(!HealingSpell.isLearned(peer) && HealingSpell.practiceCount(host) == 0
                && HealingSpell.practiceCount(peer) == 0 && host.getData(ModAttachments.MANA).mana() == 48,
                "Healing baseline must retain independent peer knowledge and original air remainder");
        requirePreviousPractice(host, peer);
        sync(host); sync(peer);
        LOGGER.info("{} HEALING_FIXTURE: host health=10, observer health=12; host-only healing learned/selected; no natural regeneration; original gamerule and player NBT journaled", PREFIX);
    }

    private static void requirePreviousPractice(ServerPlayer host, ServerPlayer peer) {
        require(WaterMagic.practiceCount(host) == 3 && WaterMagic.practiceCount(peer) == 11
                && EarthWallSpell.practiceCount(host) == 8 && EarthWallSpell.practiceCount(peer) == 0
                && AirBurstSpell.practiceCount(host) == 1 && AirBurstSpell.practiceCount(peer) == 0
                && peer.getData(ModAttachments.MANA).mana() == 83,
                "Healing must leave all old practice and observer mana unchanged");
    }

    private static void takeSnapshot(Session current, ServerPlayer player) {
        current.snapshots.put(player.getUUID(), new Snapshot(player.saveWithoutId(new CompoundTag()), player.serverLevel(),
                player.gameMode.getGameModeForPlayer(), player.position(), player.getYRot(), player.getXRot()));
    }

    private static void preparePlayer(ServerPlayer player, int water, int earth, boolean learnEarth) {
        EarthWallDismantling.cancel(player);
        SpellCasting.cancel(player);
        player.setGameMode(GameType.SURVIVAL);
        MagicKnowledge knowledge = new MagicKnowledge();
        knowledge.learn(WaterMagic.SPELL);
        for (int count = 0; count < water; count++) knowledge.practice(WaterMagic.SPELL);
        if (learnEarth) { knowledge.learn(EarthWallSpell.SPELL); for (int count = 0; count < earth; count++) knowledge.practice(EarthWallSpell.SPELL); }
        player.setData(ModAttachments.KNOWLEDGE, knowledge);
        player.setData(ModAttachments.MANA, new ManaData(learnEarth ? 100 : 83, 100, 6000, 0));
        CastingData casting = new CastingData();
        casting.select(learnEarth ? EarthWallSpell.SPELL : WaterMagic.SPELL);
        player.setData(ModAttachments.CASTING, casting);
        player.getInventory().setItem(0, new ItemStack(ModContent.TRAINING_STAFF.get()));
        player.getInventory().selected = 0;
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        player.setShiftKeyDown(false); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
    }

    private static void aim(ServerPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        player.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(), yaw, pitch);
        player.setYHeadRot(yaw);
    }

    private static void freezeMana(ServerPlayer player) {
        ManaData mana = player.getData(ModAttachments.MANA);
        player.setData(ModAttachments.MANA, new ManaData(mana.mana(), mana.maximum(), 6000, mana.cooldown()));
        player.syncData(ModAttachments.MANA);
    }

    private static void phase(Session current, int phase, String message) {
        current.phase = phase; current.phaseTicks = 0; current.acks.clear();
        for (UUID id : List.of(current.host, current.observer)) {
            ServerPlayer player = current.server.getPlayerList().getPlayer(id);
            UUID peer = id.equals(current.host) ? current.observer : current.host;
            PacketDistributor.sendToPlayer(player, new Check(phase, peer, (phase == 14 ? (id.equals(current.host) ? SpellCatalog.AIR_BURST : SpellCatalog.WATER_BOLT).id() : player.getData(ModAttachments.CASTING).selectedSpell()).toString(),
                    WaterMagic.practiceCount(player), EarthWallSpell.practiceCount(player), EarthWallSpell.isLearned(player),
                    player.getData(ModAttachments.MANA).mana(), current.center, phase >= 3 && phase <= 5,
                    (phase == 2 || phase == 8 || phase == 11 || phase == 17) && id.equals(current.host),
                    AirBurstSpell.practiceCount(player), AirBurstSpell.isLearned(player), List.copyOf(current.airEntities.values()),
                    current.airPeerPosition == null ? Vec3.ZERO : current.airPeerPosition, current.airPeerHealth,
                    HealingSpell.practiceCount(player), HealingSpell.isLearned(player), player.getHealth(),
                    current.server.getPlayerList().getPlayer(peer).getHealth(), FireMagic.practiceCount(player), FireMagic.isLearned(player), message));
        }
        LOGGER.info("{} PHASE {}: {}", PREFIX, phase, message);
    }

    private static void requireWall(Session current) {
        require(current.wallIdentity != null, "Server wall identity must have been captured");
        for (BlockPos pos : current.cells) require(current.server.overworld().getBlockState(pos).is(ModContent.EARTH_WALL.get())
                && current.server.overworld().getBlockEntity(pos) instanceof EarthWallBlockEntity cell
                && cell.identity().filter(current.wallIdentity::equals).isPresent(), "All six cells must retain the actual owner's original cast provenance");
    }

    private static boolean bothAcked(Session current) { return Boolean.TRUE.equals(current.acks.get(current.host)) && Boolean.TRUE.equals(current.acks.get(current.observer)); }
    private static void acknowledge(ServerPlayer player, Ack ack) {
        Session current = session;
        if (!enabled() || current == null || current.restored || current.server != player.getServer() || ack.phase != current.phase
                || (!player.getUUID().equals(current.host) && !player.getUUID().equals(current.observer))) return;
        LOGGER.info("{} CLIENT_RECEIPT player={} phase={} passed={} detail={}", PREFIX, player.getGameProfile().getName(), ack.phase, ack.passed, ack.detail);
        if (!ack.passed) { finish(current, false, "Client " + player.getGameProfile().getName() + ": " + ack.detail); return; }
        current.acks.put(player.getUUID(), true);
    }

    private static void sync(ServerPlayer player) {
        player.syncData(ModAttachments.KNOWLEDGE); player.syncData(ModAttachments.MANA); player.syncData(ModAttachments.CASTING);
        player.getInventory().setChanged(); player.inventoryMenu.broadcastFullState();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }

    private static void journal(Session current, boolean restored) throws Exception {
        CompoundTag root = new CompoundTag();
        root.putBoolean("Restored", restored); root.putString("Purpose", "Development-only two-client spell probe recovery journal");
        root.putLong("OriginalDayTime", current.dayTime); root.putString("OriginalDifficulty", current.difficulty.getKey());
        root.putBoolean("OriginalMobSpawning", current.mobSpawning);
        root.putBoolean("OriginalNaturalRegeneration", current.naturalRegeneration);
        root.putBoolean("OriginalPvp",current.pvp);
        ListTag airEntities = new ListTag();
        for (UUID id : current.airEntities.keySet()) { CompoundTag tag = new CompoundTag(); tag.putUUID("UUID", id); airEntities.add(tag); }
        root.put("TemporaryEntityUUIDs", airEntities);
        ListTag temporaryBlocks = new ListTag();
        for (var entry : current.temporaryBlocks.entrySet()) {
            CompoundTag tag = new CompoundTag(); tag.putInt("X", entry.getKey().getX()); tag.putInt("Y", entry.getKey().getY()); tag.putInt("Z", entry.getKey().getZ());
            tag.put("OriginalState", NbtUtils.writeBlockState(entry.getValue())); temporaryBlocks.add(tag);
        }
        root.put("TemporaryBlocks", temporaryBlocks);
        ListTag players = new ListTag();
        for (var entry : current.snapshots.entrySet()) {
            CompoundTag tag = new CompoundTag(); tag.putUUID("UUID", entry.getKey()); tag.put("Player", entry.getValue().player.copy());
            tag.putString("Dimension", entry.getValue().level.dimension().location().toString()); players.add(tag);
        }
        root.put("Players", players);
        ListTag cells = new ListTag();
        for (BlockPos pos : current.cells) { CompoundTag tag = new CompoundTag(); tag.putInt("X", pos.getX()); tag.putInt("Y", pos.getY()); tag.putInt("Z", pos.getZ()); cells.add(tag); }
        root.put("OriginallyAirCells", cells);
        root.putBoolean("OriginallyAirWithoutBlockEntities", true);
        Path path = current.server.getWorldPath(LevelResource.ROOT).resolve("jr-multiplayer-probe-recovery.nbt");
        NbtIo.writeCompressed(root, path);
    }

    private static void finish(Session current, boolean passed, String message) {
        if (current == null || current.restored) return;
        current.restored = true;
        boolean restored = true;
        for (UUID id : current.airEntities.keySet()) {
            var entity = current.server.overworld().getEntity(id);
            if (entity != null) entity.discard(); // Exact fixture UUIDs only; no drops or broad entity deletion.
        }
        for (var entry : current.temporaryBlocks.entrySet()) {
            current.server.overworld().setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
            if (!current.server.overworld().getBlockState(entry.getKey()).equals(entry.getValue())) restored = false;
        }
        for (BlockPos pos : current.wallCreated ? current.cells : List.<BlockPos>of()) {
            if (current.server.overworld().getBlockState(pos).is(ModContent.EARTH_WALL.get())) current.server.overworld().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (var entry : current.snapshots.entrySet()) {
            ServerPlayer player = current.server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { restored = false; LOGGER.error("{} MISSING_PLAYER_RESTORE {}: recovery journal retained", PREFIX, entry.getKey()); continue; }
            try {
                Snapshot snapshot = entry.getValue();
                EarthWallDismantling.cancel(player);
                SpellCasting.cancel(player);
                player.removeData(ModAttachments.KNOWLEDGE); player.removeData(ModAttachments.MANA); player.removeData(ModAttachments.CASTING);
                player.load(snapshot.player.copy());
                player.setGameMode(snapshot.mode);
                player.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                player.getData(ModAttachments.CASTING).clearActive();
                player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
                if (player.getData(ModAttachments.MANA).cooldown() > 0) player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), player.getData(ModAttachments.MANA).cooldown());
                sync(player);
                LOGGER.info("{} PLAYER_RESTORED {}: original serialized player state/inventory/attachments/game mode/position", PREFIX, player.getGameProfile().getName());
            } catch (Exception failure) { restored = false; LOGGER.error("{} RESTORE_FAILED: recovery journal retained", PREFIX, failure); }
        }
        current.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(current.mobSpawning, current.server);
        current.server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(current.naturalRegeneration, current.server);
        current.server.setDifficulty(current.difficulty, true); current.server.overworld().setDayTime(current.dayTime);
        current.server.setUsesAuthentication(current.authentication);
        current.server.setPvpAllowed(current.pvp);
        if(current.fireTeam!=null) {
            var board=current.server.getScoreboard();board.removePlayerTeam(current.fireTeam);
            for(UUID id:List.of(current.host,current.observer)) {
                String previous=id.equals(current.host)?current.hostOriginalTeam:current.peerOriginalTeam;
                ServerPlayer p=current.server.getPlayerList().getPlayer(id);
                if(previous!=null&&p!=null&&board.getPlayerTeam(previous)!=null)board.addPlayerToTeam(p.getScoreboardName(),board.getPlayerTeam(previous));
            }
        }
        current.server.overworld().getEntities(ModContent.FIRE_BALL.get(), b->b.getOwner()!=null&&(b.getOwner().getUUID().equals(current.host)||b.getOwner().getUUID().equals(current.observer))).forEach(FireBallProjectile::discard);
        try { if (restored) journal(current, true); } catch (Exception failure) { restored = false; LOGGER.error("{} JOURNAL_FAILED", PREFIX, failure); }
        String status = passed && restored ? "PASS" : "FAIL";
        LOGGER.info("{} {}: {}; cleanup={}, original terrain retained, loopback listener ends when integrated world exits", PREFIX, status, message, restored);
        for (ServerPlayer player : new ArrayList<>(current.server.getPlayerList().getPlayers())) {
            if (player.getUUID().equals(current.host) || player.getUUID().equals(current.observer)) {
                PacketDistributor.sendToPlayer(player, new Check(99, player.getUUID(), "", 0, 0, false, 0,
                        current.center, false, false, 0, false, List.of(), Vec3.ZERO, 0, 0, false, 0, 0, 0, false,
                        status + ": " + message + "; restored=" + restored));
            }
        }
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && !session.restored && event.getEntity() instanceof ServerPlayer player
                && (player.getUUID().equals(session.host) || player.getUUID().equals(session.observer))) finish(session, false, "Peer logged out before completion");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && session.server == event.getServer() && !session.restored) finish(session, false, "Server stopping before completion");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private MultiplayerSpellProbe() {}
}
