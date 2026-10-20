package mcopt.metal.mixin.lod;

import java.util.function.BooleanSupplier;
import mcopt.metal.lod.LodRefinement;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class LodRefinementMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void mcopt$refine(BooleanSupplier hasTime, CallbackInfo ci) {
		LodRefinement.tick((ServerLevel)(Object)this);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mcopt$stopRefinement(CallbackInfo ci) {
		LodRefinement.stop((ServerLevel)(Object)this);
	}
}
