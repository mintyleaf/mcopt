package mcopt.replay.mixin;

import mcopt.replay.PacketTrace;
import net.minecraft.network.PacketProcessor;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketProcessor.class)
abstract class PacketReplayMixin {
    @WrapMethod(method="processQueuedPackets")
    private void mcoptReplay$drain(Operation<Void> original) {
        var scope=PacketTrace.beginDrain();
        try{original.call();}finally{PacketTrace.end(scope);}
    }
    @ModifyExpressionValue(method="processQueuedPackets",at=@At(value="INVOKE",target="Ljava/util/Queue;isEmpty()Z"))
    private boolean mcoptReplay$budget(boolean empty){return PacketTrace.emptyOrBudget(empty);}
    @Inject(method="processQueuedPackets",at=@At(value="INVOKE",target="Lnet/minecraft/network/PacketProcessor$ListenerAndPacket;handle()V",shift=At.Shift.AFTER))
    private void mcoptReplay$handled(CallbackInfo ci){PacketTrace.handled();}
}
