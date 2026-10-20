// clang -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/lod-surfaces.m -o /tmp/mcopt-lod-surfaces
// /tmp/mcopt-lod-surfaces metal/src/main/resources/mcopt/lod/columns.metal metal/src/main/resources/mcopt/lod/seam.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>

static void require(BOOL ok, NSString *message) {
	if (!ok) { fprintf(stderr,"%s\n",message.UTF8String); exit(1); }
}
static id<MTLLibrary> library(id<MTLDevice> d, NSString *source) {
	NSError *e=nil;
	id<MTLLibrary> l=[d newLibraryWithSource:source options:nil error:&e];
	require(l != nil,e.description);
	return l;
}
static id<MTLComputePipelineState> pipeline(id<MTLDevice> d,id<MTLLibrary> l,NSString *name) {
	NSError *e=nil;
	id<MTLComputePipelineState> p=[d newComputePipelineStateWithFunction:[l newFunctionWithName:name] error:&e];
	require(p != nil,e.description);
	return p;
}
static id<MTLBuffer> buffer(id<MTLDevice> d,NSUInteger size) {
	id<MTLBuffer> b=[d newBufferWithLength:size options:MTLResourceStorageModeShared];
	memset(b.contents,0,size);
	return b;
}
static void finish(id<MTLCommandBuffer> c,id<MTLComputeCommandEncoder> e) {
	[e endEncoding]; [c commit]; [c waitUntilCompleted];
	require(c.status == MTLCommandBufferStatusCompleted,c.error.description);
}

static void lighting(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void surface_light_test(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], "
		"device const uint *t [[buffer(2)]], device float4 *out [[buffer(3)]], device const PaletteEntry *pal [[buffer(4)]], "
		"texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { "
		"CompSurface s; s.rel=float3(0.25); s.face=i==1u||i==4u||i==7u?FACE_XN:i==2u?FACE_BOTTOM:i==6u?FACE_PLANT:FACE_TOP; "
		"if(i==7u) s.rel.y=-20.0; uint flags=i<3u?REC_CROWN:i<5u?REC_UNDER_CROWN:i==5u?REC_WET:0u; "
		"constexpr sampler smp(coord::normalized); compShade(f,uint2(1u,flags|65535u),g,g,g,t,pal,atlas,smp,s); "
		"out[2u*i]=float4(float3(s.light),1); out[2u*i+1u]=float4(float2(s.levels),0,1); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"surface_light_test");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,4096),buffer(d,4*256*4),buffer(d,16*16),buffer(d,4096)];
	float *f=b[0].contents;
	f[40]=0.2f; f[41]=0.3f; f[42]=0.4f; f[50]=0.7f;
	((int *)f)[55]=4;
	f[64]=f[65]=1;
	__fp16 *lut=(__fp16 *)((char *)b[0].contents+272);
	for (int pair=0;pair<256;pair++) {
		lut[pair*4]=pair/255.0f; lut[pair*4+1]=(pair&15)/15.0f; lut[pair*4+2]=(pair>>4)/15.0f; lut[pair*4+3]=1;
	}
	((uint32_t *)b[1].contents)[0]=(1+512)|0x4000u|(1u<<15);
	uint32_t *t=b[2].contents;
	t[3*256]=0x0302E7F0u;
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	desc.usage=MTLTextureUsageShaderRead;
	id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	for (int mode=0;mode<3;mode++) {
		t[0]=mode==1?0:0xC0000000u;
		f[64]=mode==2?0:1;
		id<MTLCommandBuffer> c=[queue commandBuffer];
		id<MTLComputeCommandEncoder> e=[c computeCommandEncoder];
		[e setComputePipelineState:p];
		for (int j=0;j<5;j++) [e setBuffer:b[j] offset:0 atIndex:j];
		[e setTexture:atlas atIndex:0];
		[e dispatchThreads:MTLSizeMake(8,1,1) threadsPerThreadgroup:MTLSizeMake(8,1,1)];
		finish(c,e);
		float *out=b[3].contents;
		int pairs[]={0xF0,0xE7,0x02,0x03,0x03,0xF0,0xF0,0xE0};
		for (int i=0;i<8;i++) {
			float shade=mode==1&&(i==2||i==3||i==4)?0.7f:1;
			float expected[]={mode==0?pairs[i]/255.0f:0.2f*shade,mode==0?(pairs[i]&15)/15.0f:0.3f*shade,mode==0?(pairs[i]>>4)/15.0f:0.4f*shade};
			if(mode==1) {int sky=shade<1?13:15;expected[0]=sky*16/255.f;expected[1]=0;expected[2]=sky/15.f;}
			for (int j=0;j<3;j++) require(fabsf(out[i*8+j]-expected[j])<0.002,
				[NSString stringWithFormat:@"Lighting mode %d face %d channel %d: %f != %f",mode,i,j,out[i*8+j],expected[j]]);
			if (mode==0) require(fabsf(out[i*8+4]-(pairs[i]&15)/15.0f)<0.002 && fabsf(out[i*8+5]-(pairs[i]>>4)/15.0f)<0.002,@"G-buffer light levels lost");
		}
	}
	puts("PASS: production GPU shading selects roof, underside, side, ground, water and plant light; unknown/disabled samples retain fallback");
}

static void walls(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void wall_test(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], device const uint *cr [[buffer(2)]], "
		"device const uint *tw [[buffer(3)]], device float4 *out [[buffer(4)]], device const PaletteEntry *pal [[buffer(5)]], "
		"texture2d<half> atlas [[texture(0)]]) { CompSurface s; s.rel=float3(0, f.camFrac.w, 0.25); s.face=FACE_XN; "
		"constexpr sampler smp(coord::normalized); compShade(f,uint2(1u,REC_UNDER_CROWN|65535u),g,g,cr,tw,pal,atlas,smp,s); "
		"out[0]=float4(float2(s.levels),s.ao,1); out[1]=float4(compOccupied(f,g,cr,-1,0,63),compOccupied(f,g,cr,-1,0,75),compOccupied(f,g,cr,-1,0,89),1); "
		"CompSurface water; uint rec; meshSurface(f,float4(0,0,0.5,1),(64u<<9)|(3u<<3)|FACE_TOP,g,g,cr,water,rec); "
		"out[2]=float4(water.rel.y,float(rec&65535u),float((rec&REC_WET)!=0u),float((rec&REC_UNDER_CROWN)!=0u)); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"wall_test");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,256*4),buffer(d,256*4),buffer(d,4*256*4),buffer(d,3*16),buffer(d,4096)];
	float *f=b[0].contents;
	((int *)f)[55]=4;
	f[50]=0.7f; f[51]=1; f[64]=f[65]=1;
	f[28]=f[29]=1; // screen.xy
	// Only C.y is needed by meshRay for the horizontal water plane.
	f[24]=0; f[25]=1; f[26]=0;
	__fp16 *lut=(__fp16 *)((char *)f+272);
	for(int pair=0;pair<256;pair++) for(int j=0;j<4;j++) lut[pair*4+j]=1;
	uint32_t *g=b[1].contents,*cr=b[2].contents,*tw=b[3].contents;
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	desc.usage=MTLTextureUsageShaderRead;
	id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	for(int mode=0;mode<6;mode++) {
		memset(g,0,256*4); memset(cr,0,256*4); memset(tw,0,4*256*4);
		g[0]=(90+512)|0x6000u|(2u<<15); cr[0]=(80+512)|(0x3456u<<12)|(1u<<28);
		tw[0]=tw[15]=0xC0000000u;
		g[15]=(64+512)|0x2000u;
		tw[3*256+15]=0xF0;
		f[59]=75.25f;
		int sky=15,block=0;
		if(mode==1||mode==2) {
			g[15]=(90+512)|0x6000u|(2u<<15); cr[15]=64+512;
			tw[3*256+15]=mode==1?0xC50000F0u:0x050000F0u;
			sky=mode==1?12:0;
			if(mode==2) { f[59]=66.25f; block=3; }
		} else if(mode==3) { g[15]=(80+512)|0x2000u; sky=0; }
		else if(mode==4) { tw[15]=0; sky=0; }
		else if(mode==5) { tw[3*256+15]=15; f[59]=90.25f; sky=0; }
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLComputeCommandEncoder> e=[c computeCommandEncoder];
		[e setComputePipelineState:p];
		for(int j=0;j<6;j++) [e setBuffer:b[j] offset:0 atIndex:j];
		[e setTexture:atlas atIndex:0];
		[e dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)]; finish(c,e);
		float *out=b[4].contents;
		require(fabsf(out[0]-block/15.0f)<0.002 && fabsf(out[1]-sky/15.0f)<0.002,
			[NSString stringWithFormat:@"Outside-face lighting mode %d: got block=%f sky=%f",mode,out[0],out[1]]);
		if(mode==1) require(out[4]==1 && out[5]==0 && out[6]==1,@"Overhang's air gap became an AO wall");
		require(fabsf(out[8]-(64-1.0f/9-0.001f))<0.0001 && out[9]==0x3456 && out[10]==1 && out[11]==1,@"Water beneath cap lost height/color/material");
	}
	puts("PASS: walls read exposed-face daylight/torch light, roof gaps preserve shadows, sealed/unknown spaces stay dark, and water under caps stays water");
}

static void openWaterLighting(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void open_water_light(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], device const uint *cr [[buffer(2)]], "
		"device const uint *tw [[buffer(3)]], device float4 *out [[buffer(4)]], device const PaletteEntry *pal [[buffer(5)]], texture2d<half> atlas [[texture(0)]]) { "
		"CompSurface s; s.rel=float3(0,62.888889,0.25); s.face=f.tex.w>0?FACE_XN:FACE_TOP; uint flags=REC_WET|(f.tex.z>0?REC_UNDER_CROWN:0u); "
		"constexpr sampler smp(coord::normalized); compShade(f,uint2(1u,flags|65535u),g,g,cr,tw,pal,atlas,smp,s); "
		"out[0]=float4(float2(s.levels),float(s.light.r),float(s.light.g)); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"open_water_light");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,256*4),buffer(d,256*4),buffer(d,4*256*4),buffer(d,16),buffer(d,4096)];
	float *f=b[0].contents; ((int *)f)[55]=4; f[64]=f[65]=1;
	uint32_t *g=b[1].contents,*cr=b[2].contents,*tw=b[3].contents;
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	desc.usage=MTLTextureUsageShaderRead; id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	for(int mode=0;mode<8;mode++) {
		BOOL roof=mode==3||mode==4||mode==7, side=mode==6||mode==7;
		g[0]=roof?(81+512)|0x6000u|(1u<<15):(63+512)|0x3000u;
		cr[0]=(63+512)|(1u<<28); tw[0]=0x80000000u;
		tw[3*256]=mode==2?13:mode==4?0x04000000u:0;
		f[62]=roof?1:0; f[63]=side?1:0; f[66]=mode==5?0:1;
		float daylight=mode==0||mode==6?1:0.05f;
		__fp16 *lut=(__fp16 *)((char *)f+272);
		for(int pair=0;pair<256;pair++) {
			lut[4*pair]=daylight*(pair>>4)/15.0f+(pair&15)/15.0f;
			lut[4*pair+1]=daylight*(pair>>4)/15.0f;
		}
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLComputeCommandEncoder> e=[c computeCommandEncoder];
		[e setComputePipelineState:p]; for(int j=0;j<6;j++) [e setBuffer:b[j] offset:0 atIndex:j]; [e setTexture:atlas atIndex:0];
		[e dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)]; finish(c,e);
		int sky=roof||mode==5?0:side?14:15, block=mode==2?13:mode==4?4:0;
		float *out=b[4].contents, expected[]={block/15.0f,sky/15.0f,daylight*sky/15.0f+block/15.0f,daylight*sky/15.0f};
		for(int j=0;j<4;j++) require(fabsf(out[j]-expected[j])<0.002,
			[NSString stringWithFormat:@"Open-water lighting mode %d component %d: %f != %f",mode,j,out[j],expected[j]]);
	}
	puts("PASS: zero-light open-water strips recover daylight, stay dark at night and retain torches; covered water and dimensions without sky retain real shadows");
}

static void highestSurfaceLighting(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void highest_light(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], device const uint *cr [[buffer(2)]], "
		"device const uint *tw [[buffer(3)]], device float4 *out [[buffer(4)]], device const PaletteEntry *pal [[buffer(5)]], "
		"texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { CompSurface s; s.rel=float3(0.25); s.face=i==3?FACE_XN:FACE_TOP; "
		"uint flags=i<2?(i==0?REC_CROWN:0u):REC_UNDER_CROWN; constexpr sampler smp(coord::normalized); "
		"compShade(f,uint2(1,flags|65535),g,g,cr,tw,pal,atlas,smp,s); out[i]=float4(float2(s.levels),float(s.light.r),float(s.light.g)); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"highest_light");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,256*4),buffer(d,256*4),buffer(d,4*256*4),buffer(d,64),buffer(d,4096)];
	float *f=b[0].contents; ((int *)f)[55]=4; f[64]=f[65]=1;
	((uint32_t *)b[1].contents)[0]=(81+512)|0x6000u|(1u<<15);
	((uint32_t *)b[2].contents)[0]=64+512;
	uint32_t *tw=b[3].contents; tw[0]=0x80000000u; tw[3*256]=0x0000F000; // Partial update: side ready, highest top still zero.
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	for (int sky=0;sky<2;sky++) for (int night=0;night<2;night++) {
		f[66]=sky; float day=night?0.05f:1;
		__fp16 *lut=(__fp16 *)((char *)f+272);
		for (int pair=0;pair<256;pair++) for (int j=0;j<4;j++) lut[4*pair+j]=day*(pair>>4)/15.0f;
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLComputeCommandEncoder> e=[c computeCommandEncoder];
		[e setComputePipelineState:p]; for (int j=0;j<6;j++) [e setBuffer:b[j] offset:0 atIndex:j]; [e setTexture:atlas atIndex:0];
		[e dispatchThreads:MTLSizeMake(4,1,1) threadsPerThreadgroup:MTLSizeMake(4,1,1)]; finish(c,e);
		float *out=b[4].contents;
		for (int i=0;i<4;i++) {
			float expected=sky&&i<2?1:0;
			require(fabsf(out[4*i+1]-expected)<0.002 && fabsf(out[4*i+2]-day*expected)<0.002,
				@"Partial top-light update left trees/ground black, brightened roof interiors, or bypassed night/no-sky lighting");
		}
	}
	puts("PASS: partially ready top lighting cannot blacken trees/ground; roof interiors, night and no-sky dimensions retain their lighting");
}

static void capMaterials(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void cap_material_test(constant CompFrame &f [[buffer(0)]], device const PaletteEntry *p [[buffer(1)]], "
		"device float4 *out [[buffer(2)]], texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { "
		"constexpr sampler smp(coord::normalized); float3 n=i==0?float3(1,0,0):i==2?float3(0,1,0):float3(0,-1,0); "
		"out[i]=float4(float3(compTexture(f,p,atlas,smp,i==3?2:1,i==2,float2(0.5),float3(10),n,half3(1,0,0))),1); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"cap_material_test");
	id<MTLBuffer> frame=buffer(d,4096), palette=buffer(d,3*80), output=buffer(d,4*16);
	float *f=frame.contents; f[60]=0.001;
	float *e=(float *)palette.contents+20;
	e[0]=e[4]=0; e[1]=e[5]=0; e[2]=e[6]=0.5; e[3]=e[7]=1;
	e[8]=e[12]=1; e[11]=1; e[15]=0x663319;
	e[16]=0.5; e[17]=0; e[18]=e[19]=1;
	memcpy(e+20,e,80); e[31]=0; // A normal block must retain the existing side texture on its underside.
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:2 height:1 mipmapped:NO];
	desc.usage=MTLTextureUsageShaderRead;
	id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	uint8_t pixels[]={255,0,0,255,102,51,25,255};
	[atlas replaceRegion:MTLRegionMake2D(0,0,2,1) mipmapLevel:0 withBytes:pixels bytesPerRow:8];
	id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLComputeCommandEncoder> enc=[c computeCommandEncoder];
	[enc setComputePipelineState:p]; [enc setBuffer:frame offset:0 atIndex:0]; [enc setBuffer:palette offset:0 atIndex:1]; [enc setBuffer:output offset:0 atIndex:2];
	[enc setTexture:atlas atIndex:0]; [enc dispatchThreads:MTLSizeMake(4,1,1) threadsPerThreadgroup:MTLSizeMake(4,1,1)]; finish(c,enc);
	float *out=output.contents;
	for(int i=0;i<4;i++) {
		float expected[]={i==1?102/255.0f:1,i==1?51/255.0f:0,i==1?25/255.0f:0};
		for(int j=0;j<3;j++) require(fabsf(out[i*4+j]-expected[j])<0.002,@"Mushroom skin/underside UV or color mixed, or other materials changed");
	}
	puts("PASS: production texture shader keeps red cap skin and brown underside separate in the existing 80-byte palette layout");
}

static void publishing(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"seam_publish");
	const int n=128, words=n*n;
	NSArray<id<MTLBuffer>> *b=@[buffer(d,16),buffer(d,32),buffer(d,8*4096*4),buffer(d,words*4),buffer(d,words*4),buffer(d,2*words*4),buffer(d,4*words*4)];
	uint32_t *f=b[0].contents; f[0]=7; f[1]=words; f[2]=words; f[3]=1;
	int *tile=b[1].contents; tile[0]=0; tile[1]=-1; tile[2]=1;
	for (int mode=0;mode<3;mode++) {
		int flags=mode==0?15:mode==1?10:2;
		tile[3]=flags;
		int channels[8]={0,1}, count=2;
		if (flags&1) { channels[count++]=2; channels[count++]=3; }
		channels[count++]=4;
		if (flags&4) { channels[count++]=5; channels[count++]=6; }
		if (flags&8) channels[count++]=7;
		uint32_t *stage=b[2].contents;
		for (int j=0;j<count;j++) for (int i=0;i<4096;i++) stage[j*4096+i]=0x10000000u|channels[j]<<20|i|(channels[j]==4?0xC0000000u:0);
		id<MTLCommandBuffer> c=[queue commandBuffer];
		id<MTLComputeCommandEncoder> e=[c computeCommandEncoder];
		[e setComputePipelineState:p];
		for (int j=0;j<7;j++) [e setBuffer:b[j] offset:0 atIndex:j];
		[e dispatchThreads:MTLSizeMake(4096,1,1) threadsPerThreadgroup:MTLSizeMake(256,1,1)];
		finish(c,e);
		for (int j=0;j<count;j++) for (int i=0;i<4096;i++) {
			int channel=channels[j], index=(64+(i>>6))*128+64+(i&63);
			int target=channel==0?3:channel==1?4:channel<=3?5:6;
			int plane=channel==3?1:channel>=4?channel-4:0;
			uint32_t actual=((uint32_t *)b[target].contents)[plane*words+index];
			require(actual==stage[j*4096+i],[NSString stringWithFormat:@"GPU publish mode %d channel %d cell %d lost metadata",mode,channel,i]);
		}
	}
	puts("PASS: production GPU publisher keeps texture flags and light words aligned for full, plant-free and unlit tiles at negative origins");
}

static void islands(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void islands_test(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], "
		"device const uint *cr [[buffer(2)]], device uint *out [[buffer(3)]], uint i [[thread_position_in_grid]]) { "
		"out[i]=uint(compOccupied(f,g,cr,0,0,int(i))); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"islands_test");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,256*4),buffer(d,512*4),buffer(d,256*4)];
	float *f=b[0].contents;f[43]=256;f[51]=1;((int *)f)[55]=4;
	uint32_t *g=b[1].contents,*cr=b[2].contents;
	for(int mode=0;mode<8;mode++) {
		g[0]=mode==0?0x2000u:mode==1?(80+512)|0x2000u:(210+512)|0x6000u|(63u<<15);
		cr[0]=(mode==2?80:40)+512|(1u<<29);cr[256]=(40+512)|((120+512)<<12);
		if(mode>=3) {
			g[0]=(121+512)|0x6000u|(41u<<15)|(8u<<25);
			cr[0]=(64+512)|(1u<<30)|(mode==4?1u<<29:0);
			cr[256]=(3u<<30)|(20u<<10)|(40u<<20);
		}
		if(mode>=5) {
			g[0]=(140+512)|0x6000u|(60u<<15)|(8u<<25);
			cr[0]=(64+512)|(1u<<30)|(mode==6?1u<<29:0);
			cr[256]=1023u|(1u<<16)|(1u<<22)|(3u<<28);
		}
		if(mode==7) {
			g[0]=(200+512)|0x6000u|(40u<<15)|(1u<<21)|(8u<<25);
			cr[0]=(32+512)|(1u<<30)|(1u<<29);cr[256]=7u|(1u<<10)|(1u<<20)|(1u<<26);
		}
		id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> enc=[cb computeCommandEncoder];
		[enc setComputePipelineState:p];for(int j=0;j<4;j++) [enc setBuffer:b[j] offset:0 atIndex:j];
		[enc dispatchThreads:MTLSizeMake(256,1,1) threadsPerThreadgroup:MTLSizeMake(32,1,1)];finish(cb,enc);
		uint32_t *out=b[3].contents;
		for(int y=0;y<256;y++) {
			BOOL expected=mode>0 && ((y>=40 && y<80) || (mode==2 && y>=120 && y<210));
			if(mode>=3) expected=(y<64 && (mode==3 || y>=56)) || y==80 || y==100 || y==120;
			if(mode>=5) expected=(y<64 && (mode==5 || y>=56)) || (y>=80 && y<100) || y==112 || y==113 || y==124 || y==125 || (y>=136 && y<140);
			if(mode==7) expected=(y>=24&&y<32)||(y>=40&&y<58)||(y>=100&&y<106)||(y>=160&&y<166)||(y>=196&&y<200);
			require(out[y]==expected,[NSString stringWithFormat:@"End AO filled void or island/roof gap: mode %d y %d",mode,y]);
		}
	}
	puts("PASS: actual Metal occupancy keeps the End void, island undersides and three-floor structure gaps open");
}

static void coarseLighting(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void coarse_light(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], device const uint *cr [[buffer(2)]], "
		"device float4 *out [[buffer(3)]], device const PaletteEntry *pal [[buffer(4)]], texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { "
		"CompSurface s; s.rel=float3(0.25); s.face=i<2u?FACE_TOP:FACE_XP; uint level=1u+(i&1u); "
		"constexpr sampler smp(coord::normalized); compShade(f,uint2(1u,level<<20|65535u),g,g,cr,g,pal,atlas,smp,s); "
		"uint idx=level*256u+(uint(-2>>int(level))&15u)*16u+(uint((i<2u?-2:-3)>>int(level))&15u); "
		"out[i]=float4(float2(s.levels),float(s.light.r),float(crownColor565(cr[idx]))); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"coarse_light");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,4*256*4),buffer(d,4*256*4),buffer(d,4*16),buffer(d,4096)];
	float *f=b[0].contents;int *fi=b[0].contents;fi[52]=fi[54]=-2;fi[55]=4;
	f[40]=0.2;f[41]=0.3;f[42]=0.4;f[43]=3*256;f[64]=1;
	__fp16 *lut=(__fp16 *)((char *)f+272);
	for(int pair=0;pair<256;pair++) {lut[pair*4]=(pair&15)/15.f;lut[pair*4+1]=(pair>>4)/15.f;lut[pair*4+3]=1;}
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	desc.usage=MTLTextureUsageShaderRead;id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	uint32_t *cr=b[2].contents;
	for(int mode=0;mode<4;mode++) {
		memset(cr,0,b[2].length);f[64]=mode==1?0:1;f[43]=(mode==3?1:3)*256;
		for(int i=0;i<4;i++) {
			int level=1+(i&1),idx=level*256+((-2>>level)&15)*16+(((i<2?-2:-3)>>level)&15);
			cr[idx]=mode==2?0:(1u<<31)|(13u<<24)|(0xABCu<<12)|(64+512);
		}
		id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> enc=[cb computeCommandEncoder];
		[enc setComputePipelineState:p];for(int j=0;j<5;j++) [enc setBuffer:b[j] offset:0 atIndex:j];[enc setTexture:atlas atIndex:0];
		[enc dispatchThreads:MTLSizeMake(4,1,1) threadsPerThreadgroup:MTLSizeMake(4,1,1)];finish(cb,enc);
		float *out=b[3].contents;
		for(int i=0;i<4;i++) {
			require(fabsf(out[i*4+2]-(mode==0?13/15.f:mode==1?0.2f:0))<0.002,@"Coarse light missing, misindexed at negative origins/faces, or bypasses disabled/unknown/buffer bounds");
			if(mode==0) require(fabsf(out[i*4]-13/15.f)<0.002 && fabsf(out[i*4+1]-1)<0.002,@"Coarse G-buffer lost block light");
			if(mode!=2) require(out[i*4+3]==((10*31/15)<<11|((11*63/15)<<5)|12*31/15),@"Coarse light corrupted lower-surface color");
		}
	}
	puts("PASS: actual Metal coarse lighting preserves small sources on levels 1/2 at negative origins and side boundaries; unknown/disabled/out-of-range data keeps fallback and lower colors");
}

static void changingAmbient(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void changing_ambient(constant CompFrame &f [[buffer(0)]], device const uint *g [[buffer(1)]], device const uint *tw [[buffer(2)]], "
		"device float4 *out [[buffer(3)]], device const PaletteEntry *pal [[buffer(4)]], texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { "
		"CompSurface s; s.rel=float3(float(i)+0.25,0.25,0.25); s.face=FACE_TOP; constexpr sampler smp(coord::normalized); "
		"compShade(f,uint2(1,REC_UNDER_CROWN|65535u),g,g,g,tw,pal,atlas,smp,s); out[i]=float4(float3(s.light),float(s.levels.y)); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"changing_ambient");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,4096),buffer(d,4*256*4),buffer(d,32),buffer(d,4096)];
	float *f=b[0].contents;((int *)f)[55]=4;f[50]=0.7;f[64]=f[65]=1;
	uint32_t *tw=b[2].contents;tw[1]=0x80000000u;tw[3*256+1]=0xD0000000u;
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	for(int flash=0;flash<2;flash++) {
		__fp16 *lut=(__fp16 *)((char *)f+272);
		for(int sky=0;sky<16;sky++) for(int block=0;block<16;block++) {
			float v=sky/15.f, k=v/(4-3*v);int pair=sky*16+block;
			float rgb[]={.15f+flash*.5f*k,.2f,.12f+flash*.3f*k};
			for(int c=0;c<3;c++) lut[pair*4+c]=rgb[c];
			if(sky==15&&block==0) for(int c=0;c<3;c++) f[40+c]=rgb[c];
		}
		id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder];
		[e setComputePipelineState:p];for(int j=0;j<5;j++) [e setBuffer:b[j] offset:0 atIndex:j];[e setTexture:atlas atIndex:0];
		[e dispatchThreads:MTLSizeMake(2,1,1) threadsPerThreadgroup:MTLSizeMake(2,1,1)];finish(cb,e);
		float *out=b[3].contents;
		for(int c=0;c<4;c++) require(fabsf(out[c]-out[4+c])<.002,@"Estimated/captured shadow surfaces disagree during an End-like sky colour change");
		require(fabsf(out[1]-.2f)<.002,@"Estimated shadow incorrectly darkens ambient light");
	}
	puts("PASS: generated and captured shadowed surfaces share changing ambient/sky colours without scaling ambient light");
}

static void mipBorders(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void mip_borders(constant CompFrame &f [[buffer(0)]], device const PaletteEntry *pal [[buffer(1)]], "
		"device float4 *out [[buffer(2)]], texture2d<half> atlas [[texture(0)]], uint i [[thread_position_in_grid]]) { "
		"constexpr sampler smp(mag_filter::linear,min_filter::linear,mip_filter::linear,address::clamp_to_edge); "
		"out[i]=float4(float3(compTexture(f,pal,atlas,smp,1u,true,float2(i==0?0.99999:0.00001,0.5),float3(0,1,0),float3(0,1,0),half3(1))),1); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"mip_borders");
	NSArray<id<MTLBuffer>> *b=@[buffer(d,4096),buffer(d,160),buffer(d,32)];
	float *f=b[0].contents;f[60]=exp2f(1.25f)/16;f[61]=2;f[63]=2;
	float *pal=b[1].contents;pal[20+2]=.5;pal[20+3]=1;pal[20+8]=pal[20+9]=pal[20+10]=1;
	MTLTextureDescriptor *desc=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:8 mipmapped:YES];
	desc.usage=MTLTextureUsageShaderRead;id<MTLTexture> atlas=[d newTextureWithDescriptor:desc];
	for(int level=0;level<4;level++) {
		int w=16>>level,h=8>>level;uint32_t pixels[w*h];
		for(int y=0;y<h;y++) for(int x=0;x<w;x++) pixels[y*w+x]=x<w/2?0xFF0000FF:0xFF00FF00;
		[atlas replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:level withBytes:pixels bytesPerRow:w*4];
	}
	id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder];
	[e setComputePipelineState:p];for(int j=0;j<3;j++) [e setBuffer:b[j] offset:0 atIndex:j];[e setTexture:atlas atIndex:0];
	[e dispatchThreads:MTLSizeMake(2,1,1) threadsPerThreadgroup:MTLSizeMake(2,1,1)];finish(cb,e);
	float *out=b[2].contents;for(int i=0;i<2;i++) require(out[i*4]>.99f&&out[i*4+1]<.001f,@"Trilinear LOD sprite sampling leaks a neighbouring block at a mip border");
	puts("PASS: fractional mip levels and both sprite edges exclude neighbouring atlas textures");
}

static void islandVisibility(id<MTLDevice> d,id<MTLCommandQueue> queue,NSString *source) {
	source=[source stringByAppendingString:@"\n"
		"kernel void island_visibility(constant MeshFrame &f [[buffer(0)]], device const uint4 *table [[buffer(1)]], "
		"device const uint *arena [[buffer(2)]], device const uint *mask [[buffer(3)]], device atomic_uint *args [[buffer(4)]], "
		"device atomic_uint *hz [[buffer(8)]], device uint4 *blocks [[buffer(9)]]) { "
		"meshCullBlock<0>(f,table,arena,mask,args,hz,blocks,nullptr,24u); }\n"];
	id<MTLComputePipelineState> p=pipeline(d,library(d,source),@"island_visibility");
	id<MTLBuffer> frame=buffer(d,1024),table=buffer(d,16),arena=buffer(d,64*32*4),mask=buffer(d,4),args=buffer(d,128);
	id<MTLBuffer> hz=buffer(d,4096*129*4),blocks=buffer(d,16);
	float *f=frame.contents;int *fi=frame.contents;uint32_t *h=arena.contents,*t=table.contents;
	f[19]=4096;fi[23]=1;fi[32]=1;fi[33]=8;fi[34]=fi[35]=1;fi[36]=4;
	f[100]=48;f[101]=128/logf(8192/48.f);fi[104]=fi[105]=1;
	t[0]=1;t[1]=4;
	for(int cameraY=0;cameraY<=60;cameraY+=20) for(int mode=0;mode<4;mode++) {
		memset(h,0,64*32*4);memset(hz.contents,0,hz.length);memset(args.contents,0,args.length);
		fi[21]=cameraY;fi[98]=mode>=2?17:1;
		uint32_t *part=h+24*32;part[1]=1u<<20;part[15]=(40+512)|((80+512)<<16);
		for(int k=0;k<8;k++) {
			part[16+k]=(80+512)|((80+512)<<12);
			if(mode>0) { part[16+k]|=5u<<24|5u<<28;part[24+k]|=3u<<24|3u<<28; } // bottom 40 -> code 53
		}
		// Real-occlusion prepass: low far terrain leaves air underneath; high far terrain can be hidden by the island.
		if(mode>=2) for(int k=0;k<4096;k++) ((uint32_t *)hz.contents)[4096*128+k]=mode==2?0x407fffffu:0xbf800000u; // hzKey(-1), hzKey(1)
		id<MTLCommandBuffer> cb=[queue commandBuffer];id<MTLComputeCommandEncoder> enc=[cb computeCommandEncoder];
		[enc setComputePipelineState:p];
		NSArray *buffers=@[frame,table,arena,mask,args,hz,blocks];int slots[]={0,1,2,3,4,8,9};
		for(int k=0;k<7;k++) [enc setBuffer:buffers[k] offset:0 atIndex:slots[k]];
		[enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];finish(cb,enc);
		BOOL raised=NO;for(int k=0;k<4096*128;k++) raised|=((uint32_t *)hz.contents)[k]!=0;
		require(raised==(mode==0 || mode==3),[NSString stringWithFormat:@"Island visibility hid open space underneath, or lost its solid occluder: camera %d mode %d raised %d",cameraY,mode,raised]);
	}
	puts("PASS: production mesh cull preserves distant terrain below floating islands at four camera heights, with/without real occlusion; solid terrain remains an occluder");
}

int main(int argc,const char **argv) {
	@autoreleasepool {
		require(argc==3,@"Expected columns.metal and seam.metal paths");
		id<MTLDevice> d=MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q=[d newCommandQueue];
		NSError *e=nil;
		NSString *columns=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&e];
		require(columns!=nil,e.description);
		NSString *seam=[NSString stringWithContentsOfFile:@(argv[2]) encoding:NSUTF8StringEncoding error:&e];
		require(seam!=nil,e.description);
		lighting(d,q,columns);
		changingAmbient(d,q,columns);
		mipBorders(d,q,columns);
		walls(d,q,columns);
		openWaterLighting(d,q,columns);
		highestSurfaceLighting(d,q,columns);
		capMaterials(d,q,columns);
		publishing(d,q,seam);
		islands(d,q,columns);
		coarseLighting(d,q,columns);
		islandVisibility(d,q,columns);
	}
}
