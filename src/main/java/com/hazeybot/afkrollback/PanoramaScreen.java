package com.hazeybot.afkrollback;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A temporary screen that renders only Minecraft's normal menu panorama.
 * No title logo, buttons, or fade are rendered.
 */
public final class PanoramaScreen extends Screen {
    public PanoramaScreen() {
        super(Component.empty());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float deltaTicks) {
        renderPanorama(graphics, deltaTicks);
        // graphics.fill(0, 0, this.width, this.height, 0x99000000);
    }
}
