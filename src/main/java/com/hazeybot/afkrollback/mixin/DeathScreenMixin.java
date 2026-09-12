package com.hazeybot.afkrollback.mixin;

import com.hazeybot.afkrollback.AfkRollbackClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void afkRollback$addRollbackButton(CallbackInfo ci) {
        if (!AfkRollbackClient.hasSnapshot()) return;

        Minecraft client = Minecraft.getInstance();
        int buttonWidth = 180;
        int buttonHeight = 20;
        int x = client.getWindow().getGuiScaledWidth() / 2 - buttonWidth / 2;
        int y = client.getWindow().getGuiScaledHeight() / 4 + 120;

        ((ScreenInvoker) (Object) this).afkRollback$callAddRenderableWidget(
                Button.builder(
                        Component.literal("Rollback to AFK Snapshot"),
                        button -> AfkRollbackClient.requestRollback()
                ).bounds(x, y, buttonWidth, buttonHeight).build()
        );
    }
}
