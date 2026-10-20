// clang -O2 -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/shoreline-gpu.m metal/src/main/native/lodmesh.c -o /tmp/mcopt-shoreline-gpu
// /tmp/mcopt-shoreline-gpu metal/src/main/resources/mcopt/lod/columns.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include "../../main/native/lodmesh.h"
#include <math.h>

static void check(BOOL ok,NSString *message) { if(!ok) { fprintf(stderr,"%s\n",message.UTF8String); exit(1); } }
static id<MTLBuffer> buffer(id<MTLDevice> d,NSUInteger bytes) {
	id<MTLBuffer> b=[d newBufferWithLength:bytes options:MTLResourceStorageModeShared]; memset(b.contents,0,bytes); return b;
}
int main(int argc,const char **argv) { @autoreleasepool {
	check(argc==2,@"Expected columns.metal");
	id<MTLDevice> d=MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> queue=[d newCommandQueue]; NSError *error=nil;
	NSString *source=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error]; check(source!=nil,error.description);
	source=[source stringByAppendingString:@"\n"
		"vertex MeshVOut bank_test_vs(uint vid [[vertex_id]], constant CompFrame &f [[buffer(0)]], device const uint *arena [[buffer(1)]]) { "
		"const uint corners[6]={0,1,2,2,3,0}; uint q=vid/6u; return meshQuadCorner(f,arena[2u*q],arena[2u*q+1u],0,0,0,false,int4(0),corners[vid%6u],q); } "
		"fragment half4 bank_test_fs(MeshVOut v [[stage_in]]) { return half4(0.2,0.4,0.1,1); } "
		"kernel void bank_bounds(constant CompFrame &f [[buffer(0)]], device const uint *arena [[buffer(1)]], constant MeshFrame &mf [[buffer(2)]], "
		"device const uint4 *table [[buffer(3)]], device float4 *out [[buffer(4)]]) { MeshInstance mi; mi.a=uint4(0,1,0,0); mi.b=int4(0); "
		"float4 P[4]; float3 lo,hi,plo,phi; meshQuadClip(f,mi,arena,0,P,lo,hi,mf,table,arena,arena); "
		"pkWordsBox(mf,arena[0],arena[1],0,0,0,plo,phi); out[0]=float4(lo.y,hi.y,plo.y,phi.y); }\n"];
	id<MTLLibrary> lib=[d newLibraryWithSource:source options:nil error:&error]; check(lib!=nil,error.description);
	MTLRenderPipelineDescriptor *desc=[MTLRenderPipelineDescriptor new]; desc.vertexFunction=[lib newFunctionWithName:@"bank_test_vs"];
	desc.fragmentFunction=[lib newFunctionWithName:@"bank_test_fs"]; desc.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
	id<MTLRenderPipelineState> pipe=[d newRenderPipelineStateWithDescriptor:desc error:&error]; check(pipe!=nil,error.description);
	id<MTLComputePipelineState> bounds=[d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"bank_bounds"] error:&error]; check(bounds!=nil,error.description);
	id<MTLBuffer> frame=buffer(d,4096), arena=buffer(d,65536*8), mf=buffer(d,4096), table=buffer(d,16), output=buffer(d,16);
	const int width=256,height=128;
	MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
	td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageRenderTarget;
	id<MTLTexture> target=[d newTextureWithDescriptor:td];
	uint32_t *g=calloc(4096,4),*cr=calloc(4096,4),*h=calloc(64*LM_HDR,4),*q=calloc(65536*2,4); uint8_t *pixels=malloc(width*height*4);
	for(int base=-7;base<=64;base+=71) for(int rise=0;rise<=2;rise+=2) for(int roof=0;roof<2;roof++) {
		for(int z=0;z<64;z++) for(int x=0;x<64;x++) {
			int wet=x<32,i=z*64+x,top=base+(wet?0:rise);
			g[i]=(top+512)|0x2000|(wet?0x1000:0); cr[i]=0;
			if(roof) { g[i]=(base+20+512)|0x6000|(1u<<15); cr[i]=(top+512)|(wet?1u<<28:0); }
		}
		LmIn in={.geom=g,.crown=cr,.logN=6,.block=8,.neighbors=15}; check(lm_mesh(&in,h,q,65536)>0,@"Mesher failed");
		uint32_t *a=arena.contents; int count=0;
		for(int b=0;b<64;b++) for(int group=LM_G_XP;group<=LM_G_BZN;group++) {
			uint32_t r=h[b*LM_HDR+group]; int at=r&0xFFFFF,n=r>>20;
			for(int j=at;j<at+n;j++) if(((q[2*j]>>26)&7)==LM_F_XN && (q[2*j]&127)==32 && ((q[2*j]>>29)&3)!=LM_K_CROWN) {
				a[2*count]=q[2*j]; a[2*count+1]=q[2*j+1]; count++;
			}
		}
		check(count>0,@"Missing bank strip");
		memset(frame.contents,0,4096); float *f=frame.contents;
		f[5]=4; f[8]=1.0f/32; f[12]=-1; f[14]=0.5; f[15]=1;
		((int *)f)[53]=base; ((int *)f)[55]=6; ((int *)mf.contents)[21]=base;
		MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture=target; pass.colorAttachments[0].loadAction=MTLLoadActionClear;
		pass.colorAttachments[0].storeAction=MTLStoreActionStore; pass.colorAttachments[0].clearColor=MTLClearColorMake(1,1,1,1);
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLRenderCommandEncoder> enc=[c renderCommandEncoderWithDescriptor:pass];
		[enc setRenderPipelineState:pipe]; [enc setCullMode:MTLCullModeNone]; [enc setVertexBuffer:frame offset:0 atIndex:0]; [enc setVertexBuffer:arena offset:0 atIndex:1];
		[enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:count*6]; [enc endEncoding];
		id<MTLComputeCommandEncoder> ce=[c computeCommandEncoder]; [ce setComputePipelineState:bounds];
		NSArray *buffers=@[frame,arena,mf,table,output];
		for(int j=0;j<5;j++) [ce setBuffer:buffers[j] offset:0 atIndex:j];
		[ce dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)]; [ce endEncoding];
		[c commit]; [c waitUntilCompleted]; check(c.status==MTLCommandBufferStatusCompleted,c.error.description);
		[target getBytes:pixels bytesPerRow:width*4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
		for(int y=40;y<=60;y++) for(int x=16;x<width-16;x++) check(pixels[(y*width+x)*4]<100,@"Bright background leaks through the shoreline wall");
		float *out=output.contents; float expected[]={-1.0f/9-0.001f,rise,-1.0f/9-0.001f,rise};
		for(int j=0;j<4;j++) check(fabsf(out[j]-expected[j])<0.0001,@"Vertex/cull/position-pass bank heights disagree");
	}
	free(g);free(cr);free(h);free(q);free(pixels);
	puts("PASS: actual mesher + production GPU vertices rasterize closed shorelines; standard and position-pass bounds agree at positive/negative heights and beneath roofs");
} }
