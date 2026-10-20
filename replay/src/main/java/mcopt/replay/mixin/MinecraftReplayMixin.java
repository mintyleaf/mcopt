package mcopt.replay.mixin;

import mcopt.replay.Replay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftReplayMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mcoptReplay$frame(boolean advanceGameTime, CallbackInfo ci) { Replay.frame(); }
}
