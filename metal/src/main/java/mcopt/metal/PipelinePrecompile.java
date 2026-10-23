package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * 26.3's location-keyed registry overwrites these earlier, distinct item pipeline objects.
 * Include them in the normal resource-reload compile batch, preserving pipeline/cache identities.
 * No cache entries are aliased by name. mcopt.startup.completePipelines=false restores the vanilla batch.
 */
public final class PipelinePrecompile {
	private PipelinePrecompile() { }

	public static List<RenderPipeline> complete(List<RenderPipeline> registered) {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.startup.completePipelines", "true"))) return registered;
		List<RenderPipeline> result = null;
		for (RenderPipeline pipeline : List.of(RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_CUTOUT_GLINT,
			RenderPipelines.ITEM_TRANSLUCENT_GLINT)) {
			boolean found = false;
			for (RenderPipeline existing : registered) if (existing == pipeline) { found = true; break; }
			if (!found) {
				if (result == null) result = new ArrayList<>(registered);
				result.add(pipeline);
			}
		}
		return result == null ? registered : List.copyOf(result);
	}
}
