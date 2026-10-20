// From the repository root:
// clang -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/water-transition.m -o /tmp/mcopt-water-transition
// /tmp/mcopt-water-transition metal/src/main/resources/mcopt/own/terrain.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>

static void require(BOOL ok, NSString *message) {
	if (!ok) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		require(argc == 2, @"Expected terrain.metal path");
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		NSError *error = nil;
		NSString *source = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error];
		require(source != nil, error.description);
		source = [@"#define OWN_WATER_TRANSITION 1\n" stringByAppendingString:source];
		source = [source stringByAppendingString:@"\n"
			"kernel void water_test(device float4 *out [[buffer(0)]], constant Globals &g [[buffer(1)]], constant WaterFrame &f [[buffer(2)]], "
			"device const uint *geom [[buffer(3)]], device const uint *color [[buffer(4)]], device const float *edges [[buffer(5)]], "
			"constant float2 &uv [[buffer(6)]]) { out[0] = ownWaterTransition(float4(0.2,0.4,0.6,0.5),uv,float2(0),g,f,geom,color,edges); }\n"];
		id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&error];
		require(library != nil, error.description);
		// The production vertex/fragment pairs must agree for both rich and lean, full/half-color translucent draws.
		for (int half = 0; half <= 1; half++) for (int lean = 0; lean <= 1; lean++) {
			MTLFunctionConstantValues *cv = [MTLFunctionConstantValues new];
			bool no = false, yes = true, h = half, l = lean;
			int zero = 0; float limit = 0.1;
			for (int i = 1; i <= 3; i++) [cv setConstantValue:&no type:MTLDataTypeBool atIndex:i];
			[cv setConstantValue:&yes type:MTLDataTypeBool atIndex:0];
			[cv setConstantValue:&yes type:MTLDataTypeBool atIndex:4];
			[cv setConstantValue:&limit type:MTLDataTypeFloat atIndex:5];
			[cv setConstantValue:&zero type:MTLDataTypeInt atIndex:7];
			[cv setConstantValue:&no type:MTLDataTypeBool atIndex:8];
			[cv setConstantValue:&h type:MTLDataTypeBool atIndex:9];
			[cv setConstantValue:&l type:MTLDataTypeBool atIndex:10];
			[cv setConstantValue:&yes type:MTLDataTypeBool atIndex:26];
			MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
			pd.vertexFunction = [library newFunctionWithName:@"own_vs" constantValues:cv error:&error];
			require(pd.vertexFunction != nil, error.description);
			pd.fragmentFunction = [library newFunctionWithName:@"own_fs" constantValues:cv error:&error];
			require(pd.fragmentFunction != nil, error.description);
			pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
			require([device newRenderPipelineStateWithDescriptor:pd error:&error] != nil, error.description);
		}
		id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithFunction:[library newFunctionWithName:@"water_test"] error:&error];
		require(pipeline != nil, error.description);
		id<MTLCommandQueue> queue = [device newCommandQueue];
		NSMutableArray<id<MTLBuffer>> *buffers = [NSMutableArray array];
		for (int i = 0; i < 7; i++) [buffers addObject:[device newBufferWithLength:4096 options:MTLResourceStorageModeShared]];
		for (int test = 0; test < 7; test++) {
			for (id<MTLBuffer> b in buffers) memset(b.contents, 0, b.length);
			float *f = buffers[2].contents; int *fi = buffers[2].contents;
			f[0]=f[1]=0.1; f[2]=f[3]=0.2; f[4]=f[5]=0.4; f[6]=f[7]=0.5;
			fi[8]=-1; fi[9]=-1; fi[10]=3;
			fi[12]=4; fi[13]=-8; fi[14]=-8; fi[15]=test == 4 ? 0 : 16;
			f[16]=f[17]=f[18]=1; f[19]=32;
			for (int i = 0; i < 256; i++) {
				((uint32_t *)buffers[3].contents)[i] = 576 | (test == 5 ? 0x2000 : 0x3000);
				((uint32_t *)buffers[4].contents)[i] = 0x001f; // blue, exactly representable in RGB565
			}
			float distance = test == 0 ? 32 : test == 1 ? 16 : 0;
			for (int i = 0; i < 9; i++) ((float *)buffers[5].contents)[i] = distance;
			float *uv = buffers[6].contents; uv[0]=uv[1]=test == 3 ? 0.8 : test == 6 ? 0.45 : 0.15;
			id<MTLCommandBuffer> command = [queue commandBuffer];
			id<MTLComputeCommandEncoder> encoder = [command computeCommandEncoder];
			[encoder setComputePipelineState:pipeline];
			for (int i = 0; i < 7; i++) [encoder setBuffer:buffers[i] offset:0 atIndex:i];
			[encoder dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
			[encoder endEncoding]; [command commit]; [command waitUntilCompleted];
			require(command.status == MTLCommandBufferStatusCompleted, command.error.description);
			float blend = test == 1 ? 0.5 : test == 2 || test == 6 ? 1 : 0;
			float original[4]={0.2,0.4,0.6,0.5}, target[4]={0,0,1,1};
			float *out = buffers[0].contents;
			for (int i = 0; i < 4; i++) require(fabsf(out[i] - (original[i]*(1-blend)+target[i]*blend)) < 0.001,
				[NSString stringWithFormat:@"Water GPU case %d channel %d: got %f", test, i, out[i]]);
		}
		puts("PASS: 4 translucent pipeline variants; GPU water color/opacity transition at 0/16/32 blocks, flowing water, glass/dry exclusions and disabled LODs");
	}
}
