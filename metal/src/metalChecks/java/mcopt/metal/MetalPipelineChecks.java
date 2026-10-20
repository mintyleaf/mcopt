package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Driver-backed regressions for point-size translation and native shader cleanup after a failed compile. */
public final class MetalPipelineChecks {
	private static final String VERTEX = """
		#version 450
		void main() { gl_Position = vec4(0, 0, 0, 1); gl_PointSize = 1.0; }
		""";
	private static final String FRAGMENT = """
		#version 450
		layout(location = 0) out vec4 color;
		void main() { color = vec4(1); }
		""";
	private static final String INTEGER_FRAGMENT = FRAGMENT.replace("vec4", "ivec4");

	private static final class Module extends SPIRVModule {
		int closes;
		boolean failRead, failClose;

		Module(String source, ShaderType type) throws com.mojang.renderpearl.util.ShaderCompileException {
			super(compileSpv(source, type), type);
			reflect(); // Allocate the native reflection context that was leaking on backend failures.
		}

		@Override
		public ByteBuffer spv() {
			if (this.failRead) throw new IllegalStateException("injected translation failure");
			return super.spv();
		}

		@Override
		public void close() {
			if (++this.closes != 1) throw new AssertionError("Shader module closed more than once");
			super.close();
			if (this.failClose) throw new IllegalStateException("injected cleanup failure");
		}
	}

	private static ByteBuffer compileSpv(String source, ShaderType type) {
		long compiler = shaderc_compiler_initialize();
		long result = 0;
		try {
			result = shaderc_compile_into_spv(compiler, source,
				type == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader, "memory-check", "main", 0);
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
				throw new AssertionError(shaderc_result_get_error_message(result));
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			return MemoryUtil.memAlloc(bytes.remaining()).put(bytes).flip();
		} finally {
			if (result != 0) shaderc_result_release(result);
			shaderc_compiler_release(compiler);
		}
	}

	private static BackendRenderPipeline.CreateInfo info(Module vertex, Module fragment, PrimitiveTopology topology) {
		return new BackendRenderPipeline.CreateInfo("mcopt:memory-check", List.of(
			new BackendRenderPipeline.CreateInfo.Shader("vertex", VERTEX, vertex),
			new BackendRenderPipeline.CreateInfo.Shader("fragment", FRAGMENT, fragment)),
			List.of(), List.of(), List.of(), 0, null, PolygonMode.FILL, false,
			List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15)), topology);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && args[0].equals("verify-cache")) {
			System.setProperty("mcopt.startup.mslCache", "verify");
			System.setProperty("mcopt.startup.mslCacheDir", java.nio.file.Files.createTempDirectory("mcopt-msl-check-").toString());
		}
		MetalDevice device = new MetalDevice();
		for (PrimitiveTopology topology : List.of(PrimitiveTopology.DEBUG_LINES, PrimitiveTopology.DEBUG_LINE_STRIP,
			PrimitiveTopology.TRIANGLES, PrimitiveTopology.POINTS)) {
			Module vertex = new Module(VERTEX, ShaderType.VERTEX), fragment = new Module(FRAGMENT, ShaderType.FRAGMENT);
			BackendRenderPipeline pipeline = device.compilePipeline(info(vertex, fragment, topology)).finishCompile();
			require(pipeline != null, "Pipeline compilation failed: " + topology);
			require(vertex.closes == 0 && fragment.closes == 0, "Successful compile must leave frontend-owned modules open");
			vertex.close();
			fragment.close();
			pipeline.close();
			device.createCommandEncoder().submit();
			System.out.println("PASS: gl_PointSize shader with " + topology);
		}
		for (int i = 0; i < 32; i++) {
			Module vertex = new Module(VERTEX, ShaderType.VERTEX), fragment = new Module(INTEGER_FRAGMENT, ShaderType.FRAGMENT);
			try {
				device.compilePipeline(info(vertex, fragment, PrimitiveTopology.DEBUG_LINES));
				throw new AssertionError("Expected native pipeline failure for integer output into an UNORM target");
			} catch (IllegalStateException expected) {
				require(vertex.closes == 1 && fragment.closes == 1, "Failed compile leaked native shader modules");
			}
		}
		System.out.println("PASS: 32 native pipeline failures each close both reflected shader modules");
		Module vertex = new Module(VERTEX, ShaderType.VERTEX), fragment = new Module(FRAGMENT, ShaderType.FRAGMENT);
		vertex.failRead = vertex.failClose = true;
		try {
			device.compilePipeline(info(vertex, fragment, PrimitiveTopology.DEBUG_LINES));
			throw new AssertionError("Expected translation failure");
		} catch (IllegalStateException expected) {
			require(expected.getMessage().equals("injected translation failure"), "Cleanup replaced the original failure");
			require(expected.getSuppressed().length == 1, "Cleanup failure should be suppressed");
			require(vertex.closes == 1 && fragment.closes == 1, "Cleanup stopped before closing every module");
		}
		System.out.println("PASS: translation failure closes both modules and preserves cleanup errors");
		if (args.length > 0 && args[0].equals("verify-cache")) {
			require(MetalPipeline.mslHits > 0 && MetalPipeline.mslMismatches == 0, "Cached translation differs from fresh translation");
			System.out.println("PASS: cached translation stays correct across point and non-point pipelines");
		}
		device.close();
	}
}
