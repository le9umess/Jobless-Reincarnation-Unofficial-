package dev.joblessreincarnation.magic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Spell choices and slot IDs follow the character. The active staff and its identity are transient. */
public final class CastingData {
    public static final int SLOT_COUNT = 9;
    private static final String DEFAULT_ID = "jobless_reincarnation:water_bolt";
    public static final Codec<CastingData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("selectedSpell", DEFAULT_ID).forGetter(d -> d.selectedSpell.toString()),
            ResourceLocation.CODEC.optionalFieldOf("previousSpell").forGetter(d -> Optional.ofNullable(d.previousSpell)),
            Codec.STRING.listOf().optionalFieldOf("recallSlots", List.of()).forGetter(CastingData::savedSlots)
    ).apply(i, CastingData::new));

    public static final StreamCodec<ByteBuf, CastingData> STREAM_CODEC = new StreamCodec<>() {
        public CastingData decode(ByteBuf buf) {
            String selected = ByteBufCodecs.STRING_UTF8.decode(buf);
            Optional<ResourceLocation> previous = ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf);
            List<String> slots = new ArrayList<>(SLOT_COUNT);
            for (int slot = 0; slot < SLOT_COUNT; slot++) slots.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            CastingData data = new CastingData(selected, previous, slots);
            data.revision = ByteBufCodecs.VAR_INT.decode(buf);
            data.activeSpell = ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).decode(buf).orElse(null);
            data.overcasting = buf.readBoolean();
            data.activeFormStage = Math.clamp(ByteBufCodecs.VAR_INT.decode(buf), 0, 2);
            return data;
        }
        public void encode(ByteBuf buf, CastingData data) {
            ByteBufCodecs.STRING_UTF8.encode(buf, data.selectedSpell.toString());
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, Optional.ofNullable(data.previousSpell));
            for (String id : data.savedSlots()) ByteBufCodecs.STRING_UTF8.encode(buf, id);
            ByteBufCodecs.VAR_INT.encode(buf, data.revision);
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).encode(buf, Optional.ofNullable(data.activeSpell));
            buf.writeBoolean(data.overcasting);
            ByteBufCodecs.VAR_INT.encode(buf, data.activeFormStage);
        }
    };

    private ResourceLocation selectedSpell;
    @Nullable private ResourceLocation previousSpell;
    private final SpellCatalog[] slots = new SpellCatalog[SLOT_COUNT];
    private int revision;
    @Nullable private ResourceLocation activeSpell;
    private boolean overcasting;
    private int activeFormStage;
    private ItemStack activeStack = ItemStack.EMPTY;
    private int activeSlot = -1;
    private boolean fireReadyCueSent;
    @Nullable private ResourceLocation activeDimension;

    public CastingData() { this(DEFAULT_ID, Optional.empty(), List.of()); }

    private CastingData(String selected, Optional<ResourceLocation> previous, List<String> storedSlots) {
        ResourceLocation parsed = ResourceLocation.tryParse(selected);
        selectedSpell = parsed == null ? ResourceLocation.parse(DEFAULT_ID) : parsed;
        previousSpell = previous.orElse(null);
        for (int slot = 0; slot < Math.min(storedSlots.size(), SLOT_COUNT); slot++) {
            SpellCatalog spell = SpellCatalog.byId(ResourceLocation.tryParse(storedSlots.get(slot)));
            if (spell != null && indexOf(spell) < 0) slots[slot] = spell;
        }
    }
    private List<String> savedSlots() {
        return Arrays.stream(slots).map(s -> s == null ? "" : s.id().toString()).toList();
    }
    public ResourceLocation selectedSpell() { return selectedSpell; }
    @Nullable public ResourceLocation previousSpell() { return previousSpell; }
    @Nullable public ResourceLocation activeSpell() { return activeSpell; }
    public boolean overcasting() { return overcasting; }
    public int activeFormStage() { return activeFormStage; }
    public int revision() { return revision; }
    @Nullable public SpellCatalog spellAt(Player player, int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return null;
        SpellCatalog spell = slots[slot];
        return spell != null && spell.knows(player) ? spell : null;
    }
    private int indexOf(@Nullable SpellCatalog spell) {
        for (int i = 0; i < slots.length; i++) if (slots[i] == spell) return i;
        return -1;
    }
    /** Fill missing learned spells without moving any surviving assignment. */
    boolean normalize(Player player) {
        boolean changed = false;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && !slots[i].knows(player)) {
                slots[i] = null;
                changed = true;
            }
        }
        for (SpellCatalog spell : SpellCatalog.values()) {
            if (!spell.knows(player) || indexOf(spell) >= 0) continue;
            int preferred = spell.ordinal();
            int slot = preferred < slots.length && slots[preferred] == null ? preferred : indexOf(null);
            if (slot >= 0) {
                slots[slot] = spell;
                changed = true;
            }
        }
        SpellCatalog current = SpellCatalog.selected(player);
        if (!current.id().equals(selectedSpell)) {
            selectedSpell = current.id();
            changed = true;
        }
        SpellCatalog prior = SpellCatalog.byId(previousSpell);
        if (previousSpell != null && (prior == null || prior == current || !prior.knows(player))) {
            previousSpell = null;
            changed = true;
        }
        if (changed) revision++;
        return changed;
    }
    boolean moveSlot(SpellCatalog spell, int to) {
        int from = indexOf(spell);
        if (from < 0 || to < 0 || to >= SLOT_COUNT || from == to) return false;
        slots[from] = slots[to];
        slots[to] = spell;
        revision++;
        return true;
    }
    @Nullable public SpellCatalog previousOrNext(Player player) {
        SpellCatalog current = SpellCatalog.selected(player);
        SpellCatalog prior = SpellCatalog.byId(previousSpell);
        if (prior != null && prior != current && prior.knows(player)) return prior;
        SpellCatalog[] spells = SpellCatalog.values();
        for (int offset = 1; offset < spells.length; offset++) {
            SpellCatalog next = spells[(current.ordinal() + offset) % spells.length];
            if (next.knows(player)) return next;
        }
        return null;
    }
    public boolean select(ResourceLocation spell) {
        if (selectedSpell.equals(spell)) return false;
        previousSpell = selectedSpell;
        selectedSpell = spell;
        revision++;
        return true;
    }
    void begin(SpellCatalog spell, Player player, ItemStack stack) {
        overcasting = ManaDepletion.canOvercast(player, spell);
        activeFormStage = SpellTraining.stage(spell.practiceCount(player)).ordinal();
        activeSpell = spell.id();
        fireReadyCueSent = false;
        activeStack = stack;
        activeSlot = player.getInventory().selected;
        activeDimension = player.level().dimension().location();
    }
    boolean matches(ServerPlayer player, ItemStack stack) {
        return activeSpell != null && activeStack == stack && player.getMainHandItem() == activeStack
                && activeSlot == player.getInventory().selected
                && player.level().dimension().location().equals(activeDimension);
    }
    boolean claimReadyCue(SpellCatalog spell) {
        if (fireReadyCueSent || !spell.id().equals(activeSpell)) return false;
        fireReadyCueSent = true;
        return true;
    }
    boolean claimFireReadyCue() {
        if (fireReadyCueSent || !SpellCatalog.FIRE_BALL.id().equals(activeSpell)) return false;
        fireReadyCueSent = true;
        return true;
    }
    public boolean clearActive() {
        boolean changed = activeSpell != null;
        activeSpell = null;
        overcasting = false;
        activeFormStage = 0;
        activeStack = ItemStack.EMPTY;
        fireReadyCueSent = false;
        activeSlot = -1;
        activeDimension = null;
        return changed;
    }
}
