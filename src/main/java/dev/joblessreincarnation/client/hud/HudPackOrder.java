package dev.joblessreincarnation.client.hud;

import java.util.ArrayList;
import java.util.List;

/** Pure ordering policy: add the new default above vanilla without rearranging existing packs. */
public final class HudPackOrder {
    public static final String ID = "mod/jobless_reincarnation:resourcepacks/brass_hud";

    public static List<String> firstSelection(List<String> selected) {
        if (selected.contains(ID)) return List.copyOf(selected);
        var result = new ArrayList<>(selected);
        result.add(Math.max(0, result.indexOf("vanilla") + 1), ID);
        return List.copyOf(result);
    }

    private HudPackOrder() {}
}
