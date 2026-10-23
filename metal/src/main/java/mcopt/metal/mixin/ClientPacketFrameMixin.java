package mcopt.metal.mixin;

import mcopt.metal.config.McoptRuntime;
import mcopt.metal.cpu.PacketWorkBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketProcessor;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class ClientPacketFrameMixin {
    @WrapOperation(method="runTick",at=@At(value="INVOKE",target="Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void mcopt$packetFrame(PacketProcessor processor,Operation<Void> original) {
        Minecraft mc=(Minecraft)(Object)this;
        long nanos=mc.level==null || mc.player==null?0:(long)(McoptRuntime.current().number("mcopt.client.packetBudgetMs")*1e6);
        var scope=PacketWorkBudget.begin(nanos);
        try{original.call(processor);}finally{PacketWorkBudget.end(scope);}
    }
}
