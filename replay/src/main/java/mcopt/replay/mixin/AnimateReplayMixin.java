package mcopt.replay.mixin;

import mcopt.replay.PacketTrace;
import net.minecraft.client.multiplayer.ClientLevel;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class AnimateReplayMixin {
    @WrapMethod(method="animateTick")
    private void mcoptReplay$animate(int x,int y,int z,Operation<Void> original) {
        var scope=PacketTrace.beginWork("animateTick");
        try{original.call(x,y,z);}finally{PacketTrace.end(scope);}
    }
}
