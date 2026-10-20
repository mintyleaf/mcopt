// clang -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/lod-water-height.m -o /tmp/mcopt-lod-water-height
// /tmp/mcopt-lod-water-height metal/src/main/resources/mcopt/lod/columns.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>

static void require(BOOL ok, NSString *message) {
	if (!ok) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		require(argc == 2, @"Expected columns.metal path");
		NSError *error = nil;
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		NSString *source = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error];
		require(source != nil, error.description);
		source = [source stringByAppendingString:@"\n"
			"kernel void height_test(constant CompFrame &f [[buffer(0)]], constant MeshFrame &mf [[buffer(1)]], "
			"device const uint *arena [[buffer(2)]], device const uint *geom [[buffer(3)]], device const uint *color [[buffer(4)]], "
			"device const uint4 *table [[buffer(5)]], device float4 *out [[buffer(6)]]) { "
			"MeshVOut v = meshQuadCorner(f,arena[0],arena[1],0,0,0,false,int4(0),0,0); "
			"MeshInstance mi; mi.a=uint4(0,1,0,0); mi.b=int4(0); float4 P[4]; float3 lo,hi; "
			"meshQuadClip(f,mi,arena,0,P,lo,hi,mf,table,geom,geom); CompSurface s; uint record; "
			"meshSurface(f,float4(0.5,0.5,0,1),v.info,geom,color,geom,s,record); "
			"out[0]=float4(-v.position.y,-P[0].y,s.rel.y,lo.y); }\n"];
		id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&error];
		require(library != nil, error.description);
		id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithFunction:[library newFunctionWithName:@"height_test"] error:&error];
		require(pipeline != nil, error.description);
		id<MTLCommandQueue> queue = [device newCommandQueue];
		NSMutableArray<id<MTLBuffer>> *buffers = [NSMutableArray array];
		for (int i = 0; i < 7; i++) [buffers addObject:[device newBufferWithLength:4096 options:MTLResourceStorageModeShared]];
		for (int wet = 0; wet <= 1; wet++) {
			for (id<MTLBuffer> b in buffers) memset(b.contents, 0, b.length);
			float *f = buffers[0].contents;
			for (int i = 0; i < 4; i++) f[5 * i] = 1;
			f[25]=1; f[28]=f[29]=1;
			((int *)f)[55]=4;
			uint32_t *q = buffers[2].contents;
			q[0] = wet ? 3u << 29 : 0; q[1] = 575 | 575 << 12;
			((uint32_t *)buffers[3].contents)[0] = 575 | 0x2000 | (wet ? 0x1000 : 0);
			id<MTLCommandBuffer> command = [queue commandBuffer];
			id<MTLComputeCommandEncoder> encoder = [command computeCommandEncoder];
			[encoder setComputePipelineState:pipeline];
			for (int i = 0; i < 7; i++) [encoder setBuffer:buffers[i] offset:0 atIndex:i];
			[encoder dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
			[encoder endEncoding]; [command commit]; [command waitUntilCompleted];
			require(command.status == MTLCommandBufferStatusCompleted, command.error.description);
			float expected = 62.0f + (wet ? 8.0f / 9.0f - 0.001f : 1.0f);
			float *out = buffers[6].contents;
			for (int i = 0; i < 4; i++) require(fabsf(out[i]-expected) < 0.0001,
				[NSString stringWithFormat:@"Water height %d stage %d: got %f, expected %f",wet,i,out[i],expected]);
		}
		puts("PASS: GPU water vertices, cull bounds and fragment plane match vanilla's 8/9 height minus its 0.001 inset; dry terrain unchanged");
	}
}
