package mcopt.metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import mcopt.metal.PipelinePrecompile;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ShaderManager.class)
abstract class PipelinePrecompileMixin {
	// Supplement only the reload's compile batch; leave the public location-keyed registry unchanged.
	@ModifyExpressionValue(method = "lambda$reload$1", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/RenderPipelines;requiredPipelines()Ljava/util/List;"))
	private static List<RenderPipeline> mcopt$completePipelines(List<RenderPipeline> registered) {
		return PipelinePrecompile.complete(registered);
	}
}
