package dev.joblessreincarnation.client.aura;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TearPouchItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.config.ClientConfig;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * Epic Fight's corner icon shows the weapon's own innate skill. While a form is prepared the same key casts that form,
 * so the corner shows the form instead: lit when it would go off now, dim when not. Flow only goes off in the instant
 * after a clean deflection, so that lights up with the counter's window.
 */
public final class FormHud {
    private static final ResourceLocation EF_INNATE = ResourceLocation.fromNamespaceAndPath("epicfight", "weapon_innate");
    private static final ResourceLocation PLATE = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "hud/plate");
    private static final int PLATE_SIZE = 28, ICON_SIZE = 24, EF_ICON_SIZE = 32;
    private static final int COUNTER_TICKS = 12;
    private static final int WATER = 0x6FB6E8;
    private static long counterUntil;

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "prepared_form"), FormHud::render);
    }

    public static void hideInnateIcon(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(EF_INNATE) && prepared(Minecraft.getInstance()) != null) event.setCanceled(true);
    }

    /** The server opened this player's own counter (its public burst names them as the caster). */
    public static void counterOpened(LocalPlayer player) {
        counterUntil = player.level().getGameTime() + COUNTER_TICKS;
        player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 1.6F);
    }

    private static Skill prepared(Minecraft minecraft) {
        LocalPlayerPatch patch = minecraft.player == null ? null : EpicFightCapabilities.getEntityPatch(minecraft.player, LocalPlayerPatch.class);
        if (patch == null || SchoolSkills.inputSlot(patch, SkillSlots.WEAPON_INNATE) == SkillSlots.WEAPON_INNATE) return null;
        return patch.getSkill(SchoolSkills.inputSlot(patch, SkillSlots.WEAPON_INNATE)).getSkill();
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Skill skill = prepared(minecraft);
        if (skill == null || minecraft.options.hideGui || player.isSpectator() || !player.isAlive()) return;
        LocalPlayerPatch patch = EpicFightCapabilities.getEntityPatch(player, LocalPlayerPatch.class);
        var corner = ClientConfig.getWeaponInnatePosition();
        int x = corner.x + (EF_ICON_SIZE - PLATE_SIZE) / 2, y = corner.y + (EF_ICON_SIZE - PLATE_SIZE) / 2;
        long time = player.level().getGameTime();
        boolean ready = !patch.getEntityState().inaction();
        String form = skill.getRegistryName().getPath();
        int bags = 0;
        float window = 0;
        if (form.equals("flow_counter")) {
            window = Math.max(0, counterUntil - time - delta.getGameTimeDeltaPartialTick(false)) / COUNTER_TICKS;
            ready = window > 0;
        } else if (form.equals("tear_pouch")) {
            for (var stack : player.getInventory().items) if (stack.is(ModContent.TEAR_POUCH.get())) bags += stack.getCount();
            if (player.getOffhandItem().is(ModContent.TEAR_POUCH.get())) bags += player.getOffhandItem().getCount();
            ready &= TearPouchItem.available(player);
        }
        float light = ready ? 1.0F : 0.42F;
        RenderSystem.enableBlend();
        graphics.setColor(1, 1, 1, ready ? 1.0F : 0.7F);
        graphics.blitSprite(PLATE, x, y, PLATE_SIZE, PLATE_SIZE);
        graphics.setColor(light, light, light, ready ? 1.0F : 0.75F);
        int inset = (PLATE_SIZE - ICON_SIZE) / 2;
        graphics.blit(skill.getSkillTexture(), x + inset, y + inset, ICON_SIZE, ICON_SIZE, 0, 0, EF_ICON_SIZE, EF_ICON_SIZE, EF_ICON_SIZE, EF_ICON_SIZE);
        graphics.setColor(1, 1, 1, 1);
        if (window > 0) graphics.fill(x + inset, y + inset, x + inset + ICON_SIZE, y + inset + ICON_SIZE, Math.round(window * 110) << 24 | WATER);
        if (form.equals("tear_pouch")) {
            String count = String.valueOf(bags);
            graphics.drawString(minecraft.font, count, x + PLATE_SIZE - 3 - minecraft.font.width(count), y + PLATE_SIZE - 10, bags > 0 ? 0xFFFFFFFF : 0xFFE06A5A, true);
        }
    }

    private FormHud() {}
}
