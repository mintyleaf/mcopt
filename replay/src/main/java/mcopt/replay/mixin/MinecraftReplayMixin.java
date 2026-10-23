package mcopt.replay.mixin;

import mcopt.replay.Replay;
import mcopt.replay.PacketTrace;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftReplayMixin {
    @WrapMethod(method="runTick")
    private void mcoptReplay$frameWork(boolean advance,Operation<Void> original) {
        var scope=PacketTrace.beginWork("frame");
        try{original.call(advance);}finally{PacketTrace.end(scope);}
    }
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mcoptReplay$frame(boolean advanceGameTime, CallbackInfo ci) { Replay.frame(); }
}
