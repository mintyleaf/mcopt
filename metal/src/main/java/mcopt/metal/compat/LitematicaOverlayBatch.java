package mcopt.metal.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Retains original transparent draw order; only consecutive compatible passes are combined. */
public final class LitematicaOverlayBatch {
	private static final ThreadLocal<Batch> LOCAL = ThreadLocal.withInitial(() -> new Batch(new GameDriver()));
	private static final Matrix4f IDENTITY = new Matrix4f();
	private static final Vector4f WHITE = new Vector4f(1);
	private static final Vector3f ZERO = new Vector3f();
	private static final boolean TRACE = Boolean.getBoolean("mcopt.metal.jfr");
	@jdk.jfr.Name("mcopt.LitematicaOverlayBatch") @jdk.jfr.Label("Litematica overlay batch")
	@jdk.jfr.Category("mcopt") @jdk.jfr.StackTrace(false)
	static final class Sample extends jdk.jfr.Event { int draws, passes, flushes; double cpuMs; boolean success; }
	private LitematicaOverlayBatch() { }

	public static boolean begin() {
		if (!LitematicaOverlayGuard.verified || Access.STATE == null || !RenderSystem.isOnRenderThread()) return false;
		LOCAL.get().begin(); return true;
	}
	public static void end() { LOCAL.get().end(); }

	public static boolean capture(Object type, RenderPipeline pipeline, Object buffers, int color, float[] offset,
		boolean useColor, boolean useOffset) throws Throwable {
		if (!LitematicaOverlayGuard.verified) return false;
		Batch batch = LOCAL.get();
		if (batch.depth != 1) return false;
		Access a = Access.STATE;
		if (a == null) return false;
		int count = (int)a.count.invokeExact(buffers);
		GpuBuffer vertex = (GpuBuffer)a.vertex.invokeExact(buffers);
		if (vertex == null || vertex.isClosed() || count <= 0) { batch.flush(); return false; }
		RenderTarget target = (RenderTarget)a.target.invokeExact();
		Vector4f tint = useColor ? new Vector4f(net.minecraft.util.ARGB.redFloat(color), net.minecraft.util.ARGB.greenFloat(color),
			net.minecraft.util.ARGB.blueFloat(color), net.minecraft.util.ARGB.alphaFloat(color)) : WHITE;
		Vector3f translation = useOffset ? new Vector3f(offset[0], offset[1], offset[2]) : ZERO;
		var transform = new DynamicGpuData.Transform(RenderSystem.getModelViewMatrixCopy(), tint, translation, IDENTITY);
		batch.add(new Draw(pipeline, target.getColorTextureView(), target.hasDepth() ? target.getDepthTextureView() : null,
			vertex, (GpuBuffer)a.index.invokeExact(buffers), (IndexType)a.indexType.invokeExact(buffers),
			(PrimitiveTopology)a.topology.invokeExact(type), count, transform));
		return true;
	}

	static boolean accessAvailable() { return Access.STATE != null; }
	/** Version-guarded buffer accessors; the optional mod's classes are never compile-time dependencies. */
	private record Access(MethodHandle vertex, MethodHandle index, MethodHandle indexType, MethodHandle count,
		MethodHandle topology, MethodHandle target) {
		static final Access STATE = resolve();
		private static Access resolve() {
			try {
				ClassLoader loader = LitematicaOverlayBatch.class.getClassLoader();
				Class<?> buffers = Class.forName("fi.dy.masa.litematica.render.schematic.ChunkRenderGpuBuffers", false, loader);
				Class<?> type = Class.forName("fi.dy.masa.litematica.render.schematic.OverlayRenderType", false, loader);
				Class<?> utils = Class.forName("fi.dy.masa.malilib.render.RenderUtils", false, loader);
				var lookup = MethodHandles.lookup();
				return new Access(getter(lookup,buffers,"getVertexBuffer",GpuBuffer.class), getter(lookup,buffers,"getIndexBuffer",GpuBuffer.class),
					getter(lookup,buffers,"getIndexType",IndexType.class), getter(lookup,buffers,"getIndexCount",int.class),
					getter(lookup,type,"topology",PrimitiveTopology.class), lookup.findStatic(utils,"mainTarget",MethodType.methodType(RenderTarget.class)));
			} catch (ReflectiveOperationException | LinkageError e) {
				System.out.println("[mcopt] Litematica overlay batch unavailable; retaining original draws: " + e); return null;
			}
		}
		private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?> result) throws ReflectiveOperationException {
			return MethodHandles.privateLookupIn(owner,lookup).findVirtual(owner,name,MethodType.methodType(result))
				.asType(MethodType.methodType(result,Object.class));
		}
	}

	record Draw(RenderPipeline pipeline, GpuTextureView color, GpuTextureView depth, GpuBuffer vertex, GpuBuffer index,
		IndexType indexType, PrimitiveTopology topology, int count, DynamicGpuData.Transform transform) {
		boolean compatible(Draw next) {
			return pipeline == next.pipeline && color == next.color && depth == next.depth && topology == next.topology;
		}
	}

	/** Renderer seam permits ordered-state and GPU fixtures without starting Minecraft or loading Litematica. */
	interface Driver {
		GpuBufferSlice[] transforms(DynamicGpuData.Transform[] transforms);
		void group(Draw[] draws, int from, int to, GpuBufferSlice[] transforms);
	}

	static final class Batch {
		static final int CAP = 256;
		final Draw[] pending = new Draw[CAP];
		final Driver driver;
		int depth, size, draws, passes, flushes;
		long started;
		Batch(Driver driver) { this.driver = driver; }
		void begin() {
			// Nested callbacks retain the original immediate path, after all older draws have been submitted.
			if (this.depth > 0) flush();
			else { this.draws = this.passes = this.flushes = 0; if(TRACE) this.started=System.nanoTime(); }
			this.depth++;
		}
		void end() {
			if (this.depth <= 0) throw new IllegalStateException("Unbalanced Litematica overlay batch");
			boolean success = false;
			try { if (this.depth == 1) flush(); success = true; }
			finally {
				this.depth--;
				if (TRACE && this.depth == 0 && this.draws > 0) {
					Sample s = new Sample();
					if (s.isEnabled()) { s.draws=this.draws;s.passes=this.passes;s.flushes=this.flushes;
						s.cpuMs=(System.nanoTime()-this.started)/1e6;s.success=success;s.commit(); }
				}
			}
		}
		void add(Draw draw) {
			if (this.depth != 1) throw new IllegalStateException("Overlay captured outside outer batch");
			if (this.size == CAP) flush();
			this.pending[this.size++] = draw;
		}
		void flush() {
			if (this.size == 0) return;
			try {
				var transforms = new DynamicGpuData.Transform[this.size];
				for (int i = 0; i < this.size; i++) transforms[i] = this.pending[i].transform;
				// Write every uniform before any pass opens: transient buffer uploads may interrupt an encoder.
				GpuBufferSlice[] slices = this.driver.transforms(transforms);
				for (int from = 0; from < this.size;) {
					int to = from + 1;
					while (to < this.size && this.pending[from].compatible(this.pending[to])) to++;
					this.driver.group(this.pending, from, to, slices);
					this.passes++; from = to;
				}
				this.draws += this.size; this.flushes++;
			} finally { Arrays.fill(this.pending, 0, this.size, null); this.size = 0; }
		}
	}

	private static final class GameDriver implements Driver {
		public GpuBufferSlice[] transforms(DynamicGpuData.Transform[] values) { return RenderSystem.getDynamicUniforms().writeTransforms(values); }
		public void group(Draw[] draws, int from, int to, GpuBufferSlice[] transforms) {
			Draw first = draws[from]; int maxSequential = 0;
			for (int i = from; i < to; i++) if (draws[i].index == null) maxSequential = Math.max(maxSequential,draws[i].count);
			GpuBuffer sequential = null; IndexType sequentialType = null;
			if (maxSequential > 0) {
				// Resolve once at the group's largest size; an intervening growth must not close a queued draw's buffer.
				var shape = RenderSystem.getSequentialBuffer(first.topology);
				sequential = shape.getBuffer(maxSequential); sequentialType = shape.type();
			}
			var pipeline = RenderSystem.getCompiledPipeline(first.pipeline);
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "litematica:mcopt_overlay_batch", first.color, Optional.empty(), first.depth, OptionalDouble.empty())) {
				pass.setPipeline(pipeline); RenderSystem.bindDefaultUniforms(pass);
				for (int i = from; i < to; i++) {
					Draw d = draws[i];
					pass.setUniform("DynamicTransforms", transforms[i]);
					pass.setVertexBuffer(0,d.vertex.slice());
					pass.setIndexBuffer(d.index != null ? d.index : sequential,d.index != null ? d.indexType : sequentialType);
					pass.drawIndexed(d.count,1,0,0,0);
				}
			}
		}
	}
}
