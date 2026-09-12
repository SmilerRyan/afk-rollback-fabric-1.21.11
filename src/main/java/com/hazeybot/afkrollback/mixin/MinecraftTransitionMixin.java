package com.hazeybot.afkrollback.mixin;

import com.hazeybot.afkrollback.AfkRollbackClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.hazeybot.afkrollback.PanoramaScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftTransitionMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (AfkRollbackClient.isSeamlessRollback()
                && screen != null
                && !(screen instanceof PanoramaScreen)) {
            ci.cancel();
        }
    }

    @Inject(method = "setOverlay", at = @At("HEAD"), cancellable = true)
    private void onSetOverlay(CallbackInfo ci) {
        if (AfkRollbackClient.isSeamlessRollback()) {
            ci.cancel();
        }
    }
}
