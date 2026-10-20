// Run from the repository root:
// clang -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/terrain-visibility.m -o /tmp/mcopt-terrain-visibility
// /tmp/mcopt-terrain-visibility metal/src/main/resources/mcopt/own/terrain.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <limits.h>

static void require(BOOL ok, NSString *message) {
	if (!ok) {
		fprintf(stderr, "%s\n", message.UTF8String);
		exit(1);
	}
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		require(argc == 2, @"Expected terrain.metal path");
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		require(device != nil, @"No Metal device");
		NSError *error = nil;
		NSString *source = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error];
		require(source != nil, error.description);
		id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&error];
		require(library != nil, error.description);
		id<MTLCommandQueue> queue = [device newCommandQueue];
		NSMutableArray<id<MTLBuffer>> *buffers = [NSMutableArray array];
		for (int i = 0; i < 14; i++) [buffers addObject:[device newBufferWithLength:4096 options:MTLResourceStorageModeShared]];
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:1 height:1 mipmapped:NO];
		id<MTLTexture> texture = [device newTextureWithDescriptor:td];
		for (NSString *name in @[@"own_cull", @"own_cull_r", @"frag_cull", @"frag_cull_s", @"frag_cull_s16", @"frag_cull_s8", @"frag_cull_s4"]) {
			id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithFunction:[library newFunctionWithName:name] error:&error];
			require(pipeline != nil, error.description);
			if (![name hasPrefix:@"own_cull"]) continue;
			for (int test = 0; test < 5; test++) {
				for (id<MTLBuffer> buffer in buffers) memset(buffer.contents, 0, buffer.length);
				int *frame = buffers[0].contents, *section = buffers[1].contents;
				uint32_t *rec = buffers[2].contents;
				frame[17] = 224; // Camera block Y: lower sections have passed the vanilla vertical cutoff.
				frame[25] = test == 2 ? -32 : test == 3 ? 100 : 14;
				frame[27] = test == 0 ? 12 : INT_MAX; // Vertical range at offset 108.
				frame[28] = 12; // Horizontal distance stays 12 chunks.
				frame[29] = 1; frame[30] = 16; frame[32] = 1;
				section[0] = test == 4 ? 4096 : 0;
				section[1] = 16; section[5] = 1; // One compiled section at Y=16.
				rec[2] = 6 << 6; // One solid quad in the unrestricted facing bucket.
				*(uint32_t *) buffers[13].contents = 1; // own_cull_r record capacity.
				id<MTLCommandBuffer> command = [queue commandBuffer];
				id<MTLComputeCommandEncoder> encoder = [command computeCommandEncoder];
				[encoder setComputePipelineState:pipeline];
				for (int i = 0; i < 14; i++) [encoder setBuffer:buffers[i] offset:0 atIndex:i];
				[encoder setTexture:texture atIndex:1];
				[encoder dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(32, 1, 1)];
				[encoder endEncoding];
				[command commit]; [command waitUntilCompleted];
				require(command.status == MTLCommandBufferStatusCompleted, command.error.description);
				uint32_t count = ((uint32_t *) buffers[3].contents)[1];
				uint32_t expected = test > 0 && test < 4 ? 1 : 0;
				require(count == expected, [NSString stringWithFormat:@"%@ case %d: expected %u visible units, got %u", name, test, expected, count]);
			}
		}
		puts("PASS: 7 terrain cull pipelines compile; GPU preserves low terrain above/below world bounds with LODs and retains horizontal/vanilla cutoffs");
	}
	return 0;
}
