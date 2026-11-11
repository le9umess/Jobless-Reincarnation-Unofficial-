package dev.joblessreincarnation.client.epic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.NpcGestureVisual;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Eyes that blink, for NPCs drawn from one skin. Each eye shape is the whole skin again with only the eye pixels changed,
 * and this picks which of them the renderer binds this frame. Looks only, on the client: the rhythm is worked out from the
 * entity's id and the world clock, so nothing is stored per entity and nothing is sent. Each NPC keeps its own rhythm; two of them blink at once only by chance.
 * Characters with a full set of shapes list them all; blink-only characters list just {@link Shape#HALF} and {@link Shape#CLOSED}.
 * A shape a character lacks, or whose skin is missing from the loaded resources, falls back to the open eyes.
 */
public final class NpcEyes {
    public enum Shape { OPEN, HALF, CLOSED, HAPPY, SQUEEZE }

    // The main characters (user 11-05: only the popular ones get expressions) smile through a greeting; Sylphiette alone now
    // and then squeezes her eyes shut in it, the cute "><", which is kept rare. Everyone else only blinks.
    public static final NpcEyes ROXY = new NpcEyes("roxy_epic_v01", false, Shape.HALF, Shape.CLOSED, Shape.HAPPY, Shape.SQUEEZE);
    public static final NpcEyes SYLPHIE = new NpcEyes("sylphiette_epic", true, Shape.HALF, Shape.CLOSED, Shape.HAPPY, Shape.SQUEEZE);
    public static final NpcEyes PAUL = new NpcEyes("paul_epic", false, Shape.HALF, Shape.CLOSED);
    public static final NpcEyes ZENITH = new NpcEyes("zenith_epic", false, Shape.HALF, Shape.CLOSED);
    public static final NpcEyes LILIA = new NpcEyes("lilia_epic", false, Shape.HALF, Shape.CLOSED);
    private static final NpcEyes[] ALL = {ROXY, SYLPHIE, PAUL, ZENITH, LILIA};

    /** The eye set drawn over this skin, or null for a skin that keeps its painted eyes. */
    @org.jetbrains.annotations.Nullable public static NpcEyes forSkin(String skin) {
        for (NpcEyes eyes : ALL) if (eyes.name.equals(skin)) return eyes;
        return null;
    }

    /** One blink falls somewhere in every slot of this many ticks: about every four and a half seconds, never on a beat. */
    private static final int SLOT = 90;
    /** Earliest and latest start of a blink inside its slot; a second blink, now and then, follows the first by this many ticks. */
    private static final int FIRST = 8, LAST = 73, AGAIN = 7;
    /** The greeting's smile starts once the nod is under way and closes before she settles back. */
    private static final int SMILE_FROM = 8, SMILE_ENDS_BEFORE = 10;
    /** In one greeting out of this many the cute squeeze comes first, for this many ticks of the smile, then the smile. */
    private static final int SQUEEZE_ONE_IN = 3, SQUEEZE_TICKS = 18;

    private final String name;
    private final boolean squeezes;
    private final ResourceLocation open;
    private final ResourceLocation[] skins = new ResourceLocation[Shape.values().length];
    /** Whether each shape's skin is in the loaded resources; null until first asked after a resource reload. */
    private final Boolean[] present = new Boolean[Shape.values().length];

    private NpcEyes(String skin, boolean squeezes, Shape... shapes) {
        name = skin;
        this.squeezes = squeezes;
        open = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "textures/entity/" + skin + ".png");
        for (Shape shape : shapes) skins[shape.ordinal()] = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID,
                "textures/entity/eyes/" + skin + "/" + shape.name().toLowerCase(Locale.ROOT) + ".png");
    }

    /** The skin to draw {@code entity} with this frame: a smile through the middle of a public greeting, otherwise its blinks. */
    public ResourceLocation skin(Entity entity) {
        Shape greeting = skins[Shape.HAPPY.ordinal()] != null ? greetingFace(entity) : null;
        if (greeting != null) return skin(greeting);
        return skin(blink(entity.getId(), entity.level().getGameTime()));
    }

    /**
     * Through the middle of a greeting everyone nearby has been told of, from the same synced start, so each viewer sees the
     * face at the same moment of the nod and wave. Nothing else changes the face; a lecture never does. For Sylphiette, one
     * greeting in three, chosen by its public sequence number so every viewer agrees, starts with the squeeze.
     */
    @org.jetbrains.annotations.Nullable private Shape greetingFace(Entity entity) {
        if (!entity.hasData(ModAttachments.NPC_GESTURE_VISUAL)) return null;
        NpcGestureVisual gesture = entity.getData(ModAttachments.NPC_GESTURE_VISUAL);
        if (gesture.kind() != NpcGestureVisual.Kind.GREETING || !gesture.live()) return null;
        double age = gesture.age(0);
        if (age < SMILE_FROM || age >= gesture.duration() - SMILE_ENDS_BEFORE) return null;
        boolean cute = squeezes && skins[Shape.SQUEEZE.ordinal()] != null && Math.floorMod(gesture.sequence(), SQUEEZE_ONE_IN) == 0;
        return cute && age < SMILE_FROM + SQUEEZE_TICKS ? Shape.SQUEEZE : Shape.HAPPY;
    }

    /** The skin for one shape, or the open eyes when this character has no such shape or its file is missing. */
    public ResourceLocation skin(Shape shape) {
        int i = shape.ordinal();
        ResourceLocation wanted = skins[i];
        if (wanted == null) return open;
        if (present[i] == null) present[i] = Minecraft.getInstance().getResourceManager().getResource(wanted).isPresent();
        return present[i] ? wanted : open;
    }

    /** Open, but for a short blink once in every slot, and now and then a second one just after it. Same answer on every client. */
    public static Shape blink(int id, long time) {
        long shifted = time + Math.floorMod(mix(id, -1L), SLOT);
        long slot = Math.floorDiv(shifted, SLOT);
        int at = (int) (shifted - slot * SLOT);
        int seed = mix(id, slot);
        int start = FIRST + Math.floorMod(seed, LAST - FIRST + 1);
        Shape shape = frame(at - start);
        if (shape == Shape.OPEN && Math.floorMod(seed >>> 8, 5) == 0) shape = frame(at - start - AGAIN);
        return shape;
    }

    /** Half shut, shut for two ticks, half shut: a fifth of a second. */
    private static Shape frame(int tick) {
        return switch (tick) {
            case 0, 3 -> Shape.HALF;
            case 1, 2 -> Shape.CLOSED;
            default -> Shape.OPEN;
        };
    }

    private static int mix(int id, long slot) {
        long h = id * 0x9E3779B97F4A7C15L + slot * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) h;
    }

    /** After resources reload, look for the eye skins again: a resource pack may have added or removed them. */
    public static void forget() {
        for (NpcEyes eyes : ALL) java.util.Arrays.fill(eyes.present, null);
    }
}
