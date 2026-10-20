// clang -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/lod-overlap.m -o /tmp/mcopt-lod-overlap
// /tmp/mcopt-lod-overlap metal/src/main/resources/mcopt/lod/columns.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static void require(BOOL ok, NSString *message) {
	if (!ok) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		require(argc == 2, @"Expected columns.metal path");
		NSError *error = nil;
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		NSString *base = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error];
		require(base != nil, error.description);
		NSString *testSource = @"\n"
			"vertex float4 overlap_vs(uint vid [[vertex_id]], constant CompFrame &f [[buffer(0)]], constant uint &lod [[buffer(1)]], "
			"constant float &jitter [[buffer(2)]]) { float2 p = vid == 0 ? float2(-1,-1) : vid == 1 ? float2(3,-1) : float2(-1,3); "
			"float3 rel=float3(p,0.5+jitter); return lod != 0 ? meshClip(f,rel) : f.viewProj*float4(rel,1); }\n"
			"fragment float4 overlap_fs(constant float4 &color [[buffer(0)]]) { return color; }\n";
		id<MTLCommandQueue> queue = [device newCommandQueue];
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget; td.storageMode = MTLStorageModeShared;
		id<MTLTexture> color = [device newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatDepth32Float; td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> depth = [device newTextureWithDescriptor:td];
		MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
		dd.depthCompareFunction = MTLCompareFunctionGreaterEqual; dd.depthWriteEnabled = YES;
		id<MTLDepthStencilState> ds = [device newDepthStencilStateWithDescriptor:dd];
		for (int push = 0; push <= 1; push++) {
			NSString *prefix = push ? @"#define SEAM_DEPTH_PUSH 0.9995\n" : @"";
			id<MTLLibrary> library = [device newLibraryWithSource:[[prefix stringByAppendingString:base] stringByAppendingString:testSource] options:nil error:&error];
			require(library != nil, error.description);
			MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
			pd.vertexFunction = [library newFunctionWithName:@"overlap_vs"];
			pd.fragmentFunction = [library newFunctionWithName:@"overlap_fs"];
			pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
			pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
			id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:pd error:&error];
			require(pipeline != nil, error.description);
			for (int step = -4; step <= 4; step++) {
				MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
				pass.colorAttachments[0].texture=color; pass.colorAttachments[0].loadAction=MTLLoadActionClear; pass.colorAttachments[0].storeAction=MTLStoreActionStore;
				pass.depthAttachment.texture=depth; pass.depthAttachment.loadAction=MTLLoadActionClear; pass.depthAttachment.storeAction=MTLStoreActionDontCare; pass.depthAttachment.clearDepth=0;
				id<MTLCommandBuffer> command = [queue commandBuffer];
				id<MTLRenderCommandEncoder> enc = [command renderCommandEncoderWithDescriptor:pass];
				[enc setRenderPipelineState:pipeline]; [enc setDepthStencilState:ds]; [enc setCullMode:MTLCullModeNone];
				float frame[64]={0}; for(int i=0;i<4;i++) frame[i*5]=1;
				[enc setVertexBytes:frame length:sizeof frame atIndex:0];
				for (uint32_t lod = 0; lod <= 1; lod++) {
					float jitter = lod ? step*0.000001f : 0;
					float rgba[4]={lod ? 0 : 1,0,lod ? 1 : 0,1};
					[enc setVertexBytes:&lod length:4 atIndex:1]; [enc setVertexBytes:&jitter length:4 atIndex:2];
					[enc setFragmentBytes:rgba length:sizeof rgba atIndex:0];
					[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
				}
				[enc endEncoding]; [command commit]; [command waitUntilCompleted];
				require(command.status == MTLCommandBufferStatusCompleted, command.error.description);
				uint8_t rgba[4]; [color getBytes:rgba bytesPerRow:4 fromRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0];
				BOOL nearWins = push || step < 0;
				require(rgba[nearWins ? 0 : 2] == 255 && rgba[nearWins ? 2 : 0] == 0,
					[NSString stringWithFormat:@"Overlap push=%d jitter=%d: rgba %u %u %u",push,step,rgba[0],rgba[1],rgba[2]]);
			}
		}
		puts("PASS: GPU reproduces unstable coplanar ownership without bias; near terrain wins all nine jittered overlaps with the LOD depth offset");
	}
}
