package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;

public final class PipelinePrecompileChecks {
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
	public static void main(String[] args) {
		var original = RenderPipelines.requiredPipelines();
		var completed = PipelinePrecompile.complete(original);
		require(completed.size() == original.size() + 3, "26.3 must retain all three overwritten item identities");
		for (RenderPipeline pipeline : List.of(RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_CUTOUT_GLINT,
			RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL, RenderPipelines.ITEM_TRANSLUCENT_GLINT,
			RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL)) {
			require(completed.stream().filter(p -> p == pipeline).count() == 1, "Each item variant must compile exactly once");
		}
		require(completed.subList(0, original.size()).equals(original), "Preserve registered order and objects");
		require(PipelinePrecompile.complete(completed) == completed, "Already complete list must be returned unchanged");
		System.setProperty("mcopt.startup.completePipelines", "false");
		require(PipelinePrecompile.complete(original) == original, "Opt-out preserves the original list");
		System.out.println("PASS: distinct item identities survive location collisions; completion is idempotent and opt-out works");
	}
}
