package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Light propagation can finish after the chunk arrives; refresh its stored LOD samples once updates settle. */
@Mixin(ClientChunkCache.class)
abstract class LodLightUpdateMixin {
	@Shadow @Final private ClientLevel level;

	@Inject(method = "onLightUpdate", at = @At("TAIL"))
	private void mcopt$lodLightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
		Lod.lightChanged(this.level, pos);
	}
}
