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
        if (!AfkRollbackClient.hasCheckpoint()) return;

        Minecraft client = Minecraft.getInstance();
        int buttonWidth = 180;
        int buttonHeight = 20;
        int x = client.getWindow().getGuiScaledWidth() / 2 - buttonWidth / 2;
        int y = client.getWindow().getGuiScaledHeight() / 4 + 120;

        ((ScreenInvoker) (Object) this).afkRollback$callAddRenderableWidget(
                Button.builder(
                        Component.literal("Last checkpoint"),
                        button -> AfkRollbackClient.requestCheckpointRestore()
                ).bounds(x, y, buttonWidth, buttonHeight).build()
        );
    }
}
