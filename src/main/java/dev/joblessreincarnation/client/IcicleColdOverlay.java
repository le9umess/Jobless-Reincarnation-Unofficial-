package dev.joblessreincarnation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.joblessreincarnation.magic.IcicleFields;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Visual only. Real powder snow keeps its own counter, overlay and gameplay; no invented client status packets. */
public final class IcicleColdOverlay {
    private static final ResourceLocation RIM=ResourceLocation.withDefaultNamespace("textures/misc/powder_snow_outline.png");
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        var mc=Minecraft.getInstance();var player=mc.player;
        if(player==null||mc.level==null||player.isSpectator()||mc.options.hideGui||player.getTicksFrozen()>0)return;
        var speed=player.getAttribute(Attributes.MOVEMENT_SPEED);
        if(speed==null||!speed.hasModifier(IcicleFields.SLOW_ID))return;
        // The same drawing path/texture as vanilla's camera frost, without setting its damage/movement counter.
        RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.enableBlend();
        graphics.setColor(1F,1F,1F,.55F);
        graphics.blit(RIM,0,0,-90,0F,0F,graphics.guiWidth(),graphics.guiHeight(),graphics.guiWidth(),graphics.guiHeight());
        RenderSystem.disableBlend();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();graphics.setColor(1F,1F,1F,1F);
    }
    private IcicleColdOverlay(){}
}
