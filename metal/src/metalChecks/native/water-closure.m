// clang -O2 -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/water-closure.m metal/src/main/native/lodmesh.c -o /tmp/mcopt-water-closure
// /tmp/mcopt-water-closure metal/src/main/resources/mcopt/lod/columns.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include "../../main/native/lodmesh.h"
#include <math.h>

static void check(BOOL ok, NSString *message) { if (!ok) { fprintf(stderr,"%s\n",message.UTF8String); exit(1); } }
static id<MTLBuffer> buffer(id<MTLDevice> d, NSUInteger n) {
	id<MTLBuffer> b=[d newBufferWithLength:n options:MTLResourceStorageModeShared]; memset(b.contents,0,n); return b;
}
int main(int argc, const char **argv) { @autoreleasepool {
	check(argc==2,@"Expected columns.metal");
	id<MTLDevice> d=MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> queue=[d newCommandQueue]; NSError *error=nil;
	NSString *source=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error];
	check(source!=nil,error.description);
	source=[source stringByAppendingString:@"\n"
		"vertex MeshVOut closure_vs(uint vid [[vertex_id]], constant CompFrame &f [[buffer(22)]], device const uint *a [[buffer(0)]]) { "
		"const uint corners[6]={0,1,2,2,3,0}; uint q=vid/6; return meshQuadCorner(f,a[2*q],a[2*q+1],0,0,0,false,int4(0),corners[vid%6],q); } "
		"vertex MeshVOut seabed_vs(uint vid [[vertex_id]]) { const float2 p[6]={float2(-1,-1),float2(1,-1),float2(1,0),float2(1,0),float2(-1,0),float2(-1,-1)}; "
		"MeshVOut o; o.position=float4(p[vid],0.75,1); o.info=0; return o; } "
		"fragment half4 seabed_fs(MeshVOut in [[stage_in]]) { return half4(0.1,0.8,0.2,1); } "
		"kernel void closure_directions(constant CompFrame &f [[buffer(0)]], device float4 *out [[buffer(1)]], uint i [[thread_position_in_grid]]) { "
		"CompSurface s; s.rel=float3(16,-8,0); s.waterPath=32; s.albedo=half3(0.2,0.4,0.8); s.light=half3(1); s.face=i+1; "
		"out[i]=float4(float3(compSurfaceFog(f,s,half3(0.8))),1); } "
		"kernel void water_side_test(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], device const uint *cr [[buffer(2)]], "
		"device float4 *out [[buffer(3)]], device const uint *tw [[buffer(4)]], device const PaletteEntry *pal [[buffer(5)]], texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { "
		"CompSurface s; s.face=i+1; s.rel=float3(i==0?33.0:i==1?32.0:32.5,f.camFrac.w-100.0,i==2?25.0:i==3?24.0:24.5); "
		"constexpr sampler smp(coord::normalized); compShade(f,uint2(0,REC_WET|0x3B1Fu),g,g,cr,tw,pal,atlas,smp,s); "
		"out[i]=float4(s.waterPath,float3(compSurfaceFog(f,s,s.albedo*s.light))); }\n"];
	id<MTLLibrary> lib=[d newLibraryWithSource:source options:nil error:&error]; check(lib!=nil,error.description);
	MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
	pd.vertexFunction=[lib newFunctionWithName:@"closure_vs"]; pd.fragmentFunction=[lib newFunctionWithName:@"lod_mesh_vanilla"];
	pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA32Float; pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
	id<MTLRenderPipelineState> pipe=[d newRenderPipelineStateWithDescriptor:pd error:&error]; check(pipe!=nil,error.description);
	pd.vertexFunction=[lib newFunctionWithName:@"seabed_vs"]; pd.fragmentFunction=[lib newFunctionWithName:@"seabed_fs"];
	id<MTLRenderPipelineState> bed=[d newRenderPipelineStateWithDescriptor:pd error:&error]; check(bed!=nil,error.description);
	id<MTLComputePipelineState> directions=[d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"closure_directions"] error:&error];
	check(directions!=nil,error.description);
	id<MTLComputePipelineState> sides=[d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"water_side_test"] error:&error];
	check(sides!=nil,error.description);
	MTLDepthStencilDescriptor *ds=[MTLDepthStencilDescriptor new]; ds.depthCompareFunction=MTLCompareFunctionGreater; ds.depthWriteEnabled=YES;
	id<MTLDepthStencilState> depthState=[d newDepthStencilStateWithDescriptor:ds];
	uint32_t *g=calloc(4096,4), *h=calloc(64*LM_HDR,4), *q=calloc(65536*2,4);
	for (int i=0;i<4096;i++) g[i]=575|0x3000;
	LmIn input={.geom=g,.logN=6,.block=8,.neighbors=15}; check(lm_mesh(&input,h,q,65536)>0,@"Water mesher failed");
	for (int b=0;b<64;b++) for (int group=LM_G_XP;group<=LM_G_BZN;group++)
		check((h[b*LM_HDR+group]>>20)==0,@"Equal water surfaces gained interior walls");
	id<MTLBuffer> arena=buffer(d,65536*8), frame=buffer(d,4096), geom=buffer(d,4096*4), color=buffer(d,4096*4);
	id<MTLBuffer> crowns=buffer(d,4096*4), tw=buffer(d,4096*16), palette=buffer(d,4096), output=buffer(d,64);
	memcpy(geom.contents,g,4096*4);
	int count=0;
	uint32_t range=h[(3*8+4)*LM_HDR+LM_G_SXN], at=range&0xFFFFF, n=range>>20;
	check(n==8,@"Equal-water handoff has no submerged closure");
	for (int j=at;j<at+n;j++) {
		check(((q[2*j]>>29)&3)==LM_K_WATER && (q[2*j+1]&4095)==0,@"Water closure stops above the world floor");
		((uint32_t *)arena.contents)[2*count]=q[2*j]; ((uint32_t *)arena.contents)[2*count+1]=q[2*j+1]; count++;
	}
	for (int i=0;i<4096;i++) {
		((uint32_t *)color.contents)[i]=0x12343B1F; // Deliberately different side/top colors.
		((uint32_t *)tw.contents)[i]=0x80000000;
		((uint32_t *)tw.contents)[3*4096+i]=0x0000E0F0;
	}
	enum { width=64, height=64 };
	MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:width height:height mipmapped:NO];
	td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageRenderTarget;
	id<MTLTexture> target=[d newTextureWithDescriptor:td];
	td.pixelFormat=MTLPixelFormatDepth32Float; td.storageMode=MTLStorageModePrivate;
	id<MTLTexture> depth=[d newTextureWithDescriptor:td];
	td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	id<MTLTexture> atlas=[d newTextureWithDescriptor:td];
	id<MTLSamplerState> sampler=[d newSamplerStateWithDescriptor:[MTLSamplerDescriptor new]];
	float pixels[width*height*4], baseline[width*height*4];
	for (int submerged=0;submerged<2;submerged++) for (int night=0;night<2;night++) for (int haze=0;haze<2;haze++) {
		memset(frame.contents,0,4096); float *f=frame.contents, sky=night?0.1:1, camY=submerged?-4:2;
		f[8]=0.25; f[5]=0.125; f[13]=(camY+8)/8; f[14]=0.5; f[15]=1;
		f[18]=4; f[21]=-8; f[24]=8; f[25]=-8-camY; f[28]=width; f[29]=height;
		f[32]=haze?1:0.8; f[33]=haze?1:0.1; f[34]=haze?1:0.1; f[35]=1;
		if (submerged) { f[32]=5.0/255; f[33]=32.0/255; f[34]=77.0/255; }
		f[37]=f[39]=1; f[40]=f[41]=f[42]=sky;
		f[44]=1; f[45]=0.5; f[46]=0.8; f[47]=0.6;
		((int *)f)[52]=32; ((int *)f)[53]=63; ((int *)f)[54]=24; ((int *)f)[55]=6;
		f[56]=-8; f[57]=camY; f[58]=4; f[64]=f[65]=f[66]=1;
		f[67]=(0x05204D+1)*(submerged?-1.0f:1.0f);
		__fp16 *lut=(__fp16 *)((char *)f+272);
		for (int i=0;i<256*4;i++) lut[i]=sky;
		MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture=target; pass.colorAttachments[0].loadAction=MTLLoadActionClear;
		pass.colorAttachments[0].storeAction=MTLStoreActionStore; pass.colorAttachments[0].clearColor=MTLClearColorMake(1,0,1,1);
		pass.depthAttachment.texture=depth; pass.depthAttachment.loadAction=MTLLoadActionClear; pass.depthAttachment.clearDepth=0;
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLRenderCommandEncoder> e=[c renderCommandEncoderWithDescriptor:pass];
		[e setCullMode:MTLCullModeNone]; [e setDepthStencilState:depthState];
		[e setRenderPipelineState:bed]; [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
		[e setCullMode:MTLCullModeBack]; [e setFrontFacingWinding:MTLWindingClockwise];
		[e setRenderPipelineState:pipe]; [e setVertexBuffer:arena offset:0 atIndex:0]; [e setVertexBuffer:frame offset:0 atIndex:22];
		NSArray *buffers=@[frame,geom,color,crowns,tw,palette]; int slots[]={22,24,25,26,27,28};
		for (int i=0;i<6;i++) [e setFragmentBuffer:buffers[i] offset:0 atIndex:slots[i]];
		[e setFragmentTexture:atlas atIndex:20]; [e setFragmentSamplerState:sampler atIndex:14];
		[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:count*6]; [e endEncoding];
		id<MTLComputeCommandEncoder> ce=[c computeCommandEncoder]; [ce setComputePipelineState:directions];
		[ce setBuffer:frame offset:0 atIndex:0]; [ce setBuffer:output offset:0 atIndex:1];
		[ce dispatchThreads:MTLSizeMake(4,1,1) threadsPerThreadgroup:MTLSizeMake(4,1,1)]; [ce endEncoding];
		[c commit]; [c waitUntilCompleted]; check(c.status==MTLCommandBufferStatusCompleted,c.error.description);
		[target getBytes:pixels bytesPerRow:width*16 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
		for (int y=4;y<28;y++) for (int x=2;x<width-2;x++) {
			float *p=pixels+4*(y*width+x);
			check(p[2]>p[0] && p[0]<0.4,@"Sky/haze/background leaks through submerged LOD boundary");
		}
		float *bedPixel=pixels+4*(48*width+32);
		check(fabsf(bedPixel[1]-0.8f)<0.001,[NSString stringWithFormat:@"Water closure overwrites loaded seabed: %.3f %.3f %.3f",bedPixel[0],bedPixel[1],bedPixel[2]]);
		if (!haze) memcpy(baseline,pixels,sizeof pixels);
		else for (int i=0;i<width*height*4;i++) check(fabsf(pixels[i]-baseline[i])<0.001,@"Atmospheric haze changes underwater closure");
		float *out=output.contents;
		for (int i=1;i<4;i++) for (int j=0;j<3;j++) check(fabsf(out[4*i+j]-out[j])<0.001,@"Water closure has directional dark stripes");
		if (night&&!submerged) check(pixels[4*(16*width+32)+2]<0.1,@"Water closure glows at night");
	}
	// Exposed waterfall faces are not deep underwater skirts. Test every cardinal face, lower water under a crown,
	// and unknown neighbors through the production compShade/compSurfaceFog functions.
	memset(frame.contents,0,4096); float *sf=frame.contents;
	sf[37]=sf[39]=10000; sf[40]=sf[41]=sf[42]=1; sf[44]=sf[45]=sf[46]=sf[47]=1;
	((int *)sf)[53]=100; ((int *)sf)[55]=6; sf[67]=0x05204D+1;
	float bright=0;
	for (int mode=0;mode<4;mode++) {
		for (int i=0;i<4096;i++) {
			((uint32_t *)geom.contents)[i]=mode==2?0:575|0x3000;
			((uint32_t *)crowns.contents)[i]=575|(0x3B1Fu<<12)|(1u<<28);
			if (mode==3) ((uint32_t *)geom.contents)[i]=602|0x6000|(2u<<15);
		}
		((uint32_t *)geom.contents)[24*64+32]=592|0x3000; // A falling water column whose top is y80.
		sf[59]=mode==1?60:70;
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLComputeCommandEncoder> e=[c computeCommandEncoder];
		[e setComputePipelineState:sides]; NSArray *bs=@[frame,geom,crowns,output,tw,palette];
		for (int i=0;i<6;i++) [e setBuffer:bs[i] offset:0 atIndex:i]; [e setTexture:atlas atIndex:0];
		[e dispatchThreads:MTLSizeMake(4,1,1) threadsPerThreadgroup:MTLSizeMake(4,1,1)]; [e endEncoding];
		[c commit]; [c waitUntilCompleted]; check(c.status==MTLCommandBufferStatusCompleted,c.error.description);
		float *p=output.contents;
		for (int i=0;i<4;i++) check((mode==0||mode==3)?p[4*i]<0:p[4*i]>0,@"Waterfall mistaken for deep closure, or submerged/unknown handoff lost absorption");
		if (mode==0) bright=p[3];
		if (mode==1) check(bright>p[3]*1.5,@"Exposed waterfall still renders as a dark navy wall");
	}
	free(g); free(h); free(q);
	puts("PASS: actual water mesh + production GPU shading preserve submerged handoffs, seabed, haze/night lighting and exposed waterfalls on every cardinal face");
} }
