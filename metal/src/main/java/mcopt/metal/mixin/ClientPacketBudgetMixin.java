package mcopt.metal.mixin;

import mcopt.metal.cpu.PacketWorkBudget;
import net.minecraft.network.PacketProcessor;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketProcessor.class)
abstract class ClientPacketBudgetMixin {
    @ModifyExpressionValue(method="processQueuedPackets",at=@At(value="INVOKE",target="Ljava/util/Queue;isEmpty()Z"))
    private boolean mcopt$packetBudget(boolean empty){return PacketWorkBudget.exhausted(empty);}
    @Inject(method="processQueuedPackets",at=@At(value="INVOKE",target="Lnet/minecraft/network/PacketProcessor$ListenerAndPacket;handle()V",shift=At.Shift.AFTER))
    private void mcopt$packetHandled(CallbackInfo ci){PacketWorkBudget.handled();}
}
