package mcopt.replay.mixin;

import mcopt.replay.Replay;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional mod method; count only the path that actually requests the colored-star pipeline. */
@Mixin(value = SkyRenderer.class, priority = 800)
abstract class StarReplayMixin {
    @Inject(method = "ambientfog$renderColoredStars", remap = false, require = 0, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
    private void mcoptReplay$starPipeline(CallbackInfo ci) { Replay.starPipelineDraw(); }
}
