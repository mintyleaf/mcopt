package mcopt.metal.mixin;

import java.util.Map;
import mcopt.metal.CaveTexturePreload;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
abstract class CaveTexturePreloadMixin {
	@Shadow @Final private Map<Identifier, AbstractTexture> byPath;
	@Shadow public abstract void registerForNextReload(Identifier id);
	@Inject(method="<init>", at=@At("TAIL"))
	private void mcopt$caveTextures(CallbackInfo ci) {
		CaveTexturePreload.register(this.byPath, this::registerForNextReload);
	}
}
