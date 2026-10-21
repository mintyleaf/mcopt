package mcopt.metal.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import mcopt.metal.compat.LitematicaOverlayBatch;
import net.minecraft.client.Camera;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.render.schematic.WorldRendererSchematic", remap = false, priority = 900)
abstract class LitematicaOverlayMixin {
	@WrapMethod(method = "renderBlockOverlays")
	private void mcopt$overlayBatch(Camera camera, float width, ProfilerFiller profiler, Operation<Void> original) {
		boolean active = LitematicaOverlayBatch.begin();
		try { original.call(camera,width,profiler); }
		finally { if (active) LitematicaOverlayBatch.end(); }
	}

	@Inject(method = "drawOverlayInternal", at = @At("HEAD"), cancellable = true, require = 1)
	private void mcopt$overlayDraw(@Coerce Object type, RenderPipeline pipeline, @Coerce Object buffers,
		int color, float[] offset, boolean useColor, boolean useOffset, CallbackInfo ci) throws Throwable {
		if (LitematicaOverlayBatch.capture(type,pipeline,buffers,color,offset,useColor,useOffset)) ci.cancel();
	}
}
