package mcopt.replay.mixin;

import mcopt.replay.Replay;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class CameraReplayMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void mcoptReplay$pose(CallbackInfo ci) { Replay.applyPose(); }
}
