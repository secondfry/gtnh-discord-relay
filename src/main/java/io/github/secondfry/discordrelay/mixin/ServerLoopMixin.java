package io.github.secondfry.discordrelay.mixin;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.secondfry.discordrelay.DiscordRelay;

@Mixin(MinecraftServer.class)
public abstract class ServerLoopMixin {

    // ServerUtilities can cancel tick() at HEAD while the server loop continues to run.
    @Inject(
        method = "run",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tick()V"),
        require = 2)
    private void discordRelay$beforeTick(CallbackInfo callback) {
        DiscordRelay relay = DiscordRelay.instance;
        if (relay == null) return;
        relay.serverLoopHeartbeat();
    }
}
