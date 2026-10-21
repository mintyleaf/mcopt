package mcopt.metal.compat;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;

/** Real installed-body guard, ordered transforms, bounded queues, nesting and exception cleanup. */
public final class LitematicaOverlayChecks {
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
	static byte[] bytes(DynamicGpuData.Transform t) { var b=ByteBuffer.allocate(256);t.write(b);return Arrays.copyOf(b.array(),b.position()); }
	static final class Driver implements LitematicaOverlayBatch.Driver {
		final List<Integer> order = new ArrayList<>(); final List<byte[]> uniforms = new ArrayList<>();
		final List<Integer> groups = new ArrayList<>(); boolean fail;
		public GpuBufferSlice[] transforms(DynamicGpuData.Transform[] values) {
			for(var t:values) uniforms.add(bytes(t)); return new GpuBufferSlice[values.length];
		}
		public void group(LitematicaOverlayBatch.Draw[] values,int from,int to,GpuBufferSlice[] slices) {
			if(fail)throw new IllegalStateException("injected draw failure");
			groups.add(to-from); for(int i=from;i<to;i++) order.add(values[i].count());
		}
	}
	static LitematicaOverlayBatch.Draw draw(int id, RenderPipeline pipeline, GpuTextureView color, PrimitiveTopology topology) {
		var t=new DynamicGpuData.Transform(new Matrix4f().translate(id*.25f,id%7,-id),new Vector4f(.1f,.2f,.3f,.4f),new Vector3f(id%3),new Matrix4f());
		return new LitematicaOverlayBatch.Draw(pipeline,color,null,null,null,null,topology,id,t);
	}
	public static void main(String[] args) throws Exception {
		var lines=RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.parse("mcopt:overlay-lines")).withVertexShader("check").withFragmentShader("check").withPrimitiveTopology(PrimitiveTopology.DEBUG_LINES).build();
		var faces=RenderPipeline.builder().withLocation(net.minecraft.resources.Identifier.parse("mcopt:overlay-faces")).withVertexShader("check").withFragmentShader("check").withPrimitiveTopology(PrimitiveTopology.QUADS).build();
		GpuTextureView color=(GpuTextureView)Proxy.newProxyInstance(LitematicaOverlayChecks.class.getClassLoader(),new Class[]{GpuTextureView.class},(o,m,a)->null);
		Driver driver=new Driver();var batch=new LitematicaOverlayBatch.Batch(driver);var expected=new ArrayList<byte[]>();
		batch.begin();
		for(int id=1;id<=1025;id++) {
			var d=draw(id,id<=512?lines:faces,id==800?color:null,id<=512?PrimitiveTopology.DEBUG_LINES:PrimitiveTopology.QUADS);
			expected.add(bytes(d.transform()));batch.add(d);check(batch.size<=256,"Unbounded overlay queue");
		}
		batch.end();check(driver.order.size()==1025,"Draws dropped");
		for(int i=0;i<1025;i++) {
			check(driver.order.get(i)==i+1,"Transparent draw order changed");
			check(Arrays.equals(driver.uniforms.get(i),expected.get(i)),"Chunk transform/color bytes changed");
		}
		check(batch.passes==7 && batch.flushes==5,"Compatible groups/attachment boundary did not batch");
		check(batch.depth==0 && batch.size==0 && Arrays.stream(batch.pending).allMatch(v->v==null),"Scope retained buffers/transforms");
		batch.begin();batch.add(draw(2000,lines,null,PrimitiveTopology.DEBUG_LINES));batch.begin();
		check(driver.order.getLast()==2000 && batch.depth==2,"Nested immediate work can overtake queued outer draws");
		batch.end();batch.add(draw(2001,lines,null,PrimitiveTopology.DEBUG_LINES));batch.end();
		check(driver.order.getLast()==2001 && batch.depth==0,"Nested callback did not restore outer scope");
		driver.fail=true;batch.begin();batch.add(draw(3000,lines,null,PrimitiveTopology.DEBUG_LINES));
		try { batch.end();throw new AssertionError("Draw failure swallowed"); } catch(IllegalStateException expectedFailure) { }
		check(batch.depth==0 && batch.size==0 && Arrays.stream(batch.pending).allMatch(v->v==null),"Exception leaked scope or resources");
		driver.fail=false;batch.begin();batch.add(draw(3001,faces,null,PrimitiveTopology.QUADS));batch.end();check(driver.order.getLast()==3001,"Next frame could not recover");
		try(var jar=new JarFile(args[0])) {
			ClassNode node=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry("fi/dy/masa/litematica/render/schematic/WorldRendererSchematic.class"))).accept(node,0);
			for(var m:node.methods)if(List.of("renderBlockOverlays","renderBlockOverlay","drawOverlayInternal").contains(m.name))System.out.println(m.name+" "+LitematicaOverlayGuard.fingerprint(m));
			check(LitematicaOverlayGuard.verify(node),"Installed overlay body not recognized");
			node.methods.stream().filter(m->m.name.equals("drawOverlayInternal")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
			check(!LitematicaOverlayGuard.verify(node),"Modified overlay body was redirected");
		}
		check(LitematicaOverlayBatch.accessAvailable(),"Installed buffer/target accessors could not be linked");
		System.out.println("PASS: 1025 ordered draws/identical uniform bytes, 7 passes, bounded storage, target/pipeline boundaries, nested callbacks, exceptions and installed/unknown guards");
	}
}
