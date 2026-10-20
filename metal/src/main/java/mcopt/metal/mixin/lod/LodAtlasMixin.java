package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
abstract class LodAtlasMixin {
	@Inject(method = "upload", at = @At("RETURN"))
	private void mcopt$reloadPalette(CallbackInfo ci) {
		if (((TextureAtlas)(Object)this).location().equals(TextureAtlas.LOCATION_BLOCKS)) Lod.atlasReloaded();
	}
}
