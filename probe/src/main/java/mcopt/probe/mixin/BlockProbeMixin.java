package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import mcopt.probe.GameplayProbe;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelRenderer.class)
abstract class BlockProbeMixin {
    @WrapMethod(method = "submitBlockDestroyAnimation")
    private void mcoptProbe$breaking(PoseStack pose, SubmitNodeCollector collector,
                                      LevelRenderState state, Operation<Void> original) {
        var event = GameplayProbe.begin("blockBreakingSubmit");
        try { original.call(pose, collector, state); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
}
