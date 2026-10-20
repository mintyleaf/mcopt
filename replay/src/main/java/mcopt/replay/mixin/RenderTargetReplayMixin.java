package mcopt.replay.mixin;

import mcopt.replay.Replay;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class RenderTargetReplayMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/util/profiling/Profiler;get()Lnet/minecraft/util/profiling/ProfilerFiller;"))
    private void mcoptReplay$worldTarget(CallbackInfo ci) { Replay.worldTarget(); }
}
