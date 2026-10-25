package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.probe.GameplayProbe;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TextureManager.class)
abstract class TextureProbeMixin {
    @WrapMethod(method = "registerAndLoad")
    private void mcoptProbe$texture(Identifier location, ReloadableTexture texture, Operation<Void> original) {
        var event = GameplayProbe.begin("textureLoad");
        try { original.call(location, texture); }
        finally {
            if (GameplayProbe.end(event)) {
                event.subject = String.valueOf(location);
                event.implementation = texture == null ? "null" : texture.getClass().getName();
                event.commit();
            }
        }
    }
}
