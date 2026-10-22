package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also runs in menus: disabling LODs must retire buffers even when no world is being drawn. */
@Mixin(GameRenderer.class)
abstract class LodRuntimeMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$liveControls(CallbackInfo ci) { Lod.clientFrame(); }
}
