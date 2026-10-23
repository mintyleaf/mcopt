package mcopt.replay.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.replay.AssetTrace;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TextureManager.class)
abstract class TextureReplayMixin {
    @WrapMethod(method="registerAndLoad")
    private void mcoptReplay$load(Identifier location,ReloadableTexture texture,Operation<Void> original) {
        var event=AssetTrace.begin("texture",location.toString());
        if(event!=null)event.implementation=texture.getClass().getName();
        try{original.call(location,texture);}finally{AssetTrace.end(event);}
    }
}
