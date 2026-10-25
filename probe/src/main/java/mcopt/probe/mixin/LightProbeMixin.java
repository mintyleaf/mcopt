package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.probe.GameplayProbe;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class LightProbeMixin {
    @WrapMethod(method = "pollLightUpdates")
    private void mcoptProbe$light(Operation<Void> original) {
        var event = GameplayProbe.begin("lightUpdates");
        try { original.call(); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
}
