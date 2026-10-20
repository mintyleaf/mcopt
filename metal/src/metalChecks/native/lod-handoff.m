// clang -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/lod-handoff.m -o /tmp/mcopt-lod-handoff
// /tmp/mcopt-lod-handoff metal/src/main/resources/mcopt/lod/columns.metal
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static void check(BOOL ok, NSString *message) { if (!ok) { fprintf(stderr,"%s\n",message.UTF8String); exit(1); } }
static id<MTLBuffer> buffer(id<MTLDevice> d, NSUInteger bytes) {
	id<MTLBuffer> b=[d newBufferWithLength:bytes options:MTLResourceStorageModeShared]; memset(b.contents,0,bytes); return b;
}

int main(int argc, const char **argv) { @autoreleasepool {
	check(argc==2,@"Expected columns.metal");
	id<MTLDevice> d=MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> queue=[d newCommandQueue]; NSError *error=nil;
	NSString *base=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:&error]; check(base!=nil,error.description);
	NSString *source=[@"#define SEAM_HANDOFF 1\n#define SEAM_DEPTH_PUSH 0.9995\n" stringByAppendingString:base];
	source=[source stringByAppendingString:@"\n"
		"vertex MeshVOut handoff_vs(uint v [[vertex_id]]) { MeshVOut o; o.position=float4(v==0?float2(-1,-1):v==1?float2(3,-1):float2(-1,3),0.5,1); o.info=FACE_TOP; return o; }\n"];
	MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_1;options.mathMode=MTLMathModeSafe;
	id<MTLLibrary> lib=[d newLibraryWithSource:source options:options error:&error]; check(lib!=nil,error.description);
	check([lib newFunctionWithName:@"lod_mesh_vs2"].functionConstantsDictionary[@"lodHandoffPass"]!=nil,@"Native handoff specialization detection failed");
	MTLFunctionConstantValues *fadeValues=[MTLFunctionConstantValues new], *opaqueValues=[MTLFunctionConstantValues new];
	bool yes=true,no=false;
	[fadeValues setConstantValue:&yes type:MTLDataTypeBool atIndex:0];
	[opaqueValues setConstantValue:&no type:MTLDataTypeBool atIndex:0];
	MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
	// Compile every production handoff fragment path, including cutout plants and native G-buffer output.
	NSArray *fragments=@[@"lod_mesh_vanilla",@"lod_mesh_plant_vanilla",@"lod_mesh_gbuffer",@"lod_mesh_plant_gbuffer"];
	for (int i=0;i<4;i++) {
		pd.vertexFunction=(i&1)?[lib newFunctionWithName:@"lod_mesh_plant_vs"]:[lib newFunctionWithName:@"lod_mesh_vs2" constantValues:fadeValues error:&error];
		pd.fragmentFunction=(i&1)?[lib newFunctionWithName:fragments[i]]:[lib newFunctionWithName:fragments[i] constantValues:fadeValues error:&error];
		for (int a=0;a<5;a++) pd.colorAttachments[a].pixelFormat=MTLPixelFormatInvalid;
		if (i<2) pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
		else {
			for (int a=1;a<=3;a++) pd.colorAttachments[a].pixelFormat=MTLPixelFormatRGBA16Float;
			pd.colorAttachments[4].pixelFormat=MTLPixelFormatR32Float;
		}
		check([d newRenderPipelineStateWithDescriptor:pd error:&error]!=nil,error.description);
		if (!(i&1)) {
			pd.vertexFunction=[lib newFunctionWithName:@"lod_mesh_vs2" constantValues:opaqueValues error:&error];
			pd.fragmentFunction=[lib newFunctionWithName:fragments[i] constantValues:opaqueValues error:&error];
			check([d newRenderPipelineStateWithDescriptor:pd error:&error]!=nil,error.description);
		}
	}
	for (int a=0;a<5;a++) pd.colorAttachments[a].pixelFormat=MTLPixelFormatInvalid;
	pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
	pd.vertexFunction=[lib newFunctionWithName:@"handoff_vs"]; pd.fragmentFunction=[lib newFunctionWithName:@"lod_mesh_vanilla" constantValues:fadeValues error:&error];
	id<MTLRenderPipelineState> pipe=[d newRenderPipelineStateWithDescriptor:pd error:&error]; check(pipe!=nil,error.description);
	id<MTLBuffer> frame=buffer(d,4096), mask=buffer(d,2048+128*128*4), geom=buffer(d,4096*4), color=buffer(d,4096*4);
	id<MTLBuffer> crowns=buffer(d,4096*4), tw=buffer(d,4096*16), palette=buffer(d,4096);
	float *f=frame.contents; int *fi=frame.contents;
	f[16]=8; f[22]=8; f[25]=-8; // rays cover one 16 x 16 chunk at y0
	f[28]=f[29]=64; f[37]=f[39]=10000;
	f[40]=f[41]=f[42]=f[44]=f[45]=f[46]=f[47]=1;
	fi[55]=6; f[56]=f[57]=f[58]=8;
	fi[580]=fi[581]=-1; fi[582]=3; f[584]=144; f[585]=176; // Java's 2320-byte lightmap frame + handoff rectangle/band
	for (int i=0;i<4096;i++) { ((uint32_t *)geom.contents)[i]=512|0x2000; ((uint32_t *)color.contents)[i]=0x001F001F; }
	MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:64 mipmapped:NO];
	td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageRenderTarget;
	id<MTLTexture> target=[d newTextureWithDescriptor:td];
	td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
	id<MTLTexture> atlas=[d newTextureWithDescriptor:td];
	id<MTLSamplerState> sampler=[d newSamplerStateWithDescriptor:[MTLSamplerDescriptor new]];
	unsigned char pixels[64*64*4], previous[64*64*4];
	float *arrivals=(float *)((char *)mask.contents+2048);
	for (int scenario=0;scenario<9;scenario++) for (int repeat=0;repeat<2;repeat++) {
		float arrival=scenario<5?scenario*0.25f:scenario==5?0:1;
		for (int i=0;i<9;i++) arrivals[i]=arrival;
		f[16]=scenario==6?24:8; f[584]=scenario==7?4:144; f[585]=scenario==7?12:176;
		fi[582]=scenario==8?0:3; f[59]=repeat*12345; // unrelated install clock must never reseed the dissolve
		if (scenario==6) { arrivals[3]=arrivals[5]=0; } // one coarse face spans ready and missing chunks
		MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture=target; pass.colorAttachments[0].loadAction=MTLLoadActionClear;
		pass.colorAttachments[0].storeAction=MTLStoreActionStore;
		// Red represents complete near geometry; magenta represents absent near terrain (must get a full fallback).
		pass.colorAttachments[0].clearColor=scenario==5?MTLClearColorMake(1,0,1,1):MTLClearColorMake(1,0,0,1);
		id<MTLCommandBuffer> c=[queue commandBuffer]; id<MTLRenderCommandEncoder> e=[c renderCommandEncoderWithDescriptor:pass];
		[e setRenderPipelineState:pipe]; [e setCullMode:MTLCullModeNone];
		NSArray *buffers=@[frame,mask,geom,color,crowns,tw,palette]; int slots[]={22,23,24,25,26,27,28};
		for (int i=0;i<7;i++) [e setFragmentBuffer:buffers[i] offset:i==1?2048:0 atIndex:slots[i]];
		[e setFragmentTexture:atlas atIndex:20]; [e setFragmentSamplerState:sampler atIndex:14];
		[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [e endEncoding];
		[c commit]; [c waitUntilCompleted]; check(c.status==MTLCommandBufferStatusCompleted,c.error.description);
		[target getBytes:pixels bytesPerRow:64*4 fromRegion:MTLRegionMake2D(0,0,64,64) mipmapLevel:0];
		int blue=0;
		for (int y=0;y<64;y++) for (int x=0;x<64;x++) {
			unsigned char *p=pixels+4*(y*64+x);
			check((p[0]>240 && p[2]<5)||(p[2]>240 && p[0]<5),@"Handoff left a sky hole or changed surface colors");
			blue+=p[2]>240;
			if (scenario==6) check((p[2]>240)==(x<21||x>=43),@"Coarse face leaks across covered chunk boundary");
		}
		if (scenario<5) check(blue==4096-(scenario*1024),@"Arrival dissolve has wrong pixel coverage");
		if (scenario==5||scenario==8) check(blue==4096,@"Missing chunk or disabled handoff lost fallback");
		if (scenario==7) check(blue>0&&blue<4096,@"Outgoing distance band is not smooth");
		if (!repeat) memcpy(previous,pixels,sizeof pixels);
		else check(!memcmp(previous,pixels,sizeof pixels),@"Stationary handoff flickers across frames");
	}
	// Draw the actual survivors vertex stage through both specializations. Compare the combined draws with the exact
	// per-pixel reference, and ensure every whole quad belongs to only one draw (including one crossing the band).
	id<MTLRenderPipelineState> split[2];
	for(int phase=0;phase<2;phase++) {
		MTLFunctionConstantValues *values=phase?fadeValues:opaqueValues;
		pd.vertexFunction=[lib newFunctionWithName:@"lod_mesh_vs2" constantValues:values error:&error];
		pd.fragmentFunction=[lib newFunctionWithName:@"lod_mesh_vanilla" constantValues:values error:&error];
		split[phase]=[d newRenderPipelineStateWithDescriptor:pd error:&error];check(split[phase]!=nil,error.description);
	}
	id<MTLBuffer> args=buffer(d,512),survivors=buffer(d,16),dummy=buffer(d,1024);
	((uint32_t*)args.contents)[14]=1;
	uint16_t indices[]={0,1,2,2,3,0};id<MTLBuffer> index=[d newBufferWithBytes:indices length:sizeof indices options:MTLResourceStorageModeShared];
	for(int scenario=0;scenario<4;scenario++) {
		memset(f,0,16*sizeof(float));
		int width=scenario==2?64:16,tx=scenario==1?4:0;
		f[0]=2.0f/width;f[9]=1.0f/8;f[12]=-(tx*64+width/2.0f-8)*2/width;f[14]=0.5;f[15]=1;
		f[16]=width/2.0f;f[22]=8;f[24]=tx*64+width/2.0f-8;f[25]=-8;
		fi[582]=scenario==3?0:3;f[584]=scenario==2?4:144;f[585]=scenario==2?36:176;
		for(int i=0;i<9;i++)arrivals[i]=scenario==0?0.5f:1;
		uint32_t *sr=survivors.contents;sr[0]=((width-1)<<14)|(15<<20);sr[1]=512;sr[2]=tx;sr[3]=0;
		unsigned char reference[sizeof pixels],combined[sizeof pixels];int counts[2]={0,0};
		for(int mode=0;mode<4;mode++) {
			MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
			pass.colorAttachments[0].texture=target;pass.colorAttachments[0].loadAction=MTLLoadActionClear;pass.colorAttachments[0].storeAction=MTLStoreActionStore;
			pass.colorAttachments[0].clearColor=MTLClearColorMake(1,0,0,1);
			id<MTLCommandBuffer> c=[queue commandBuffer];id<MTLRenderCommandEncoder> e=[c renderCommandEncoderWithDescriptor:pass];
			[e setCullMode:MTLCullModeNone];
			NSArray *buffers=@[frame,mask,geom,color,crowns,tw,palette];int slots[]={22,23,24,25,26,27,28};
			for(int i=0;i<7;i++)[e setFragmentBuffer:buffers[i] offset:i==1?2048:0 atIndex:slots[i]];
			[e setFragmentTexture:atlas atIndex:20];[e setFragmentSamplerState:sampler atIndex:14];
			if(mode==0) {
				[e setRenderPipelineState:pipe];[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			} else {
				[e setVertexBuffer:frame offset:0 atIndex:22];[e setVertexBuffer:dummy offset:0 atIndex:23];[e setVertexBuffer:dummy offset:0 atIndex:24];
				[e setVertexBuffer:args offset:0 atIndex:25];[e setVertexBuffer:survivors offset:0 atIndex:26];[e setVertexBuffer:dummy offset:0 atIndex:27];
				[e setVertexBuffer:geom offset:0 atIndex:28];[e setVertexBuffer:crowns offset:0 atIndex:29];[e setVertexBuffer:dummy offset:0 atIndex:30];
				for(int phase=0;phase<2;phase++)if(mode==3||phase==mode-1) {
					[e setRenderPipelineState:split[phase]];
					[e drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:6 indexType:MTLIndexTypeUInt16 indexBuffer:index indexBufferOffset:0];
				}
			}
			[e endEncoding];[c commit];[c waitUntilCompleted];check(c.status==MTLCommandBufferStatusCompleted,c.error.description);
			[target getBytes:pixels bytesPerRow:64*4 fromRegion:MTLRegionMake2D(0,0,64,64) mipmapLevel:0];
			if(mode==0)memcpy(reference,pixels,sizeof pixels);
			else if(mode<3)for(int i=0;i<64*64;i++)counts[mode-1]+=pixels[4*i+2]>240;
			else memcpy(combined,pixels,sizeof pixels);
		}
		check(!memcmp(reference,combined,sizeof pixels),[NSString stringWithFormat:@"Split handoff changed coverage in scenario %d",scenario]);
		BOOL near=scenario==0||scenario==2;
		check(counts[near?0:1]==0 && counts[near?1:0]>0,@"Quad appeared in both draws or was assigned to the wrong draw");
	}
	puts("PASS: six production Metal pipeline variants; exact fade coverage, stable frames, missing fallback; opaque/fade partition matches reference for near, far, straddling and disabled handoffs");
} }
