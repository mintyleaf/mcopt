package mcopt.metal.own;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Our mesher's FRAPI path (OwnApi): what Fabric API's own SectionCompiler patch does, with our quads as the destination. Per
 * worker a block tesselator from the verified Indigo renderer (other implementations retain a new renderer
 * per compile): the renderer culls, lights (AO or flat, emissive), tints and offsets each quad; per worker one quad emitter
 * whose emit writes the finished quad as compact quads for solid and cutout (the bytes OwnTerrain.store makes of what
 * Fabric API's patch would have put in vanilla's BufferBuilder: same floats, ABGR colour, packed light) or, translucent,
 * with QuadView.buffer into the translucent BufferBuilder, as Fabric API's patch does. The layer is the quad's own (QuadView.chunkLayer: FRAPI's blend mode).
 * Loaded only when fabric-renderer-api-v1 is.
 */
final class OwnFrapi implements OwnApi.Mesher {
	private static final ThreadLocal<OwnFrapi> LOCAL = ThreadLocal.withInitial(OwnFrapi::new);

	private final Renderer renderer = Renderer.get();
	private final QuadEmitter emitter = this.renderer.quadEmitter(this::emitted);
	private final boolean reusable = this.renderer.getClass().getName().equals("net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer")
		&& FabricLoader.getInstance().getModContainer("fabric-renderer-indigo")
			.filter(mod -> mod.getMetadata().getVersion().getFriendlyString().equals("9.1.24+b9d63e335d")).isPresent();
	private @Nullable AltModelBlockRenderer blocks;
	private boolean ambientOcclusion;
	private @Nullable BlockColors colors;
	private @Nullable OwnQuads quads;
	private @Nullable Supplier<VertexConsumer> translucent;
	private @Nullable VertexConsumer translucentSink;
	private final float[] p = new float[12], uv = new float[8];
	private final int[] abgr = new int[4], light = new int[4];

	private OwnFrapi() {
	}

	static OwnApi.Mesher get(boolean ambientOcclusion, BlockColors blockColors, OwnQuads quads, Supplier<VertexConsumer> translucent) {
		OwnFrapi f = LOCAL.get();
		if (!f.reusable || f.blocks == null || f.ambientOcclusion != ambientOcclusion || f.colors != blockColors) {
			f.blocks = f.renderer.altModelBlockRenderer(ambientOcclusion, true, blockColors);
			f.ambientOcclusion = ambientOcclusion;
			f.colors = blockColors;
		}
		f.quads = quads;
		f.translucent = translucent;
		f.translucentSink = null;
		return f;
	}

	@Override public void release() {
		this.quads = null;
		this.translucent = null;
		this.translucentSink = null;
	}

	@Override
	public void block(float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state, BlockStateModel model, long seed) {
		this.blocks.tesselateBlock(this.emitter, x, y, z, level, pos, state, model, seed);
	}

	private void emitted(MutableQuadView quad) {
		ChunkSectionLayer layer = quad.chunkLayer();
		if (layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT) {
			// what quad.buffer + BufferBuilder (BLOCK format: position, ABGR colour, uv, packed light) would store, read straight
			// from the quad: no face normal (the BLOCK format has none), no per-vertex calls
			float[] p = this.p, uv = this.uv;
			int[] abgr = this.abgr, light = this.light;
			for (int v = 0; v < 4; v++) {
				p[v * 3] = quad.x(v);
				p[v * 3 + 1] = quad.y(v);
				p[v * 3 + 2] = quad.z(v);
				uv[v * 2] = quad.u(v);
				uv[v * 2 + 1] = quad.v(v);
				int c = quad.color(v);
				abgr[v] = c & 0xFF00FF00 | (c & 0xFF0000) >> 16 | (c & 0xFF) << 16;
				light[v] = quad.lightmap(v);
			}
			this.quads.layers[layer == ChunkSectionLayer.SOLID ? 0 : 1].quad(p, abgr, uv, light);
			return;
		}
		VertexConsumer sink = this.translucentSink;
		if (sink == null) sink = this.translucentSink = this.translucent.get();
		quad.buffer(OverlayTexture.NO_OVERLAY, sink);
	}
}
