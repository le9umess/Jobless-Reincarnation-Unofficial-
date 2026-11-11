package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.training.SchoolForm;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab.TabVisibility;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.world.item.SkillBookItem;

/** Only the nineteen retired Epic Fight 21.17.3.1 book variants have a replacement. */
public final class SchoolBookSources {
    private static @Nullable SchoolForm replacement(ItemStack stack) {
        if (!stack.is(EpicFightItems.SKILLBOOK.get())) return null;
        ResourceLocation id = SkillBookItem.getContainSkill(stack).flatMap(holder -> holder.unwrapKey())
                .map(key -> key.location()).orElse(null);
        if (id == null) return null;
        if (id.getNamespace().equals(dev.joblessreincarnation.JoblessReincarnation.MOD_ID)) {
            // An EF-generated book never awards the advanced study sheet.
            for (SchoolForm form : SchoolForm.values()) if (form.id.equals(id.getPath())) {
                return form == SchoolForm.LIGHT_BLADE ? SchoolForm.ARM_STRIKE : form;
            }
            return null;
        }
        if (!id.getNamespace().equals("epicfight")) return null;
        return switch (id.getPath()) {
            case "adrenaline_fiend", "berserker", "bonebreaker", "hypervitality", "swordmaster", "vengeance" -> SchoolForm.ARM_STRIKE;
            case "adaptive_skin", "catharsis", "impact_guard", "technician", "revelation", "forbidden_strength" -> SchoolForm.FLOW_COUNTER;
            case "death_harvest", "emergency_escape", "stamina_pillager", "step", "phantom_ascent", "demolition_leap", "meteor_slam" -> SchoolForm.TEAR_POUCH;
            default -> null;
        };
    }

    public static ItemStack generatedBook(ItemStack generated) {
        SchoolForm form = replacement(generated);
        return form == null ? generated : new ItemStack(form.note(), generated.getCount());
    }

    public static void creativeContents(BuildCreativeModeTabContentsEvent event) {
        for (var s : event.getParentEntries().stream().filter(stack -> replacement(stack) != null).toList()) {
            event.remove(s, TabVisibility.PARENT_AND_SEARCH_TABS);
        }
        for (var s : event.getSearchEntries().stream().filter(stack -> replacement(stack) != null).toList()) {
            event.remove(s, TabVisibility.SEARCH_TAB_ONLY);
        }
    }

    private SchoolBookSources() {}
}
