package mcopt.replay.mixin;

import mcopt.replay.PacketTrace;
import net.minecraft.client.multiplayer.ClientLevel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class LightReplayMixin {
    @WrapMethod(method="pollLightUpdates")
    private void mcoptReplay$light(Operation<Void> original) {
        var scope=PacketTrace.beginLight();
        try{original.call();}finally{PacketTrace.end(scope);}
    }
}
