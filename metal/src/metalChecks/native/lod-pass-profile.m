// clang -fno-objc-arc -framework Foundation -framework Metal -I metal/src/main/native \
// metal/src/metalChecks/native/lod-pass-profile.m metal/build/natives/natives/libmcmetal.dylib \
// -Wl,-rpath,"$PWD/metal/build/natives/natives" -o /tmp/mcopt-lod-pass-profile
#import "mcmetal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern Ctx *mc_create(char *, int, char *, int);
extern Enc *mc_enc_new(Ctx *);
extern int mc_profile_begin(Enc *, int);
extern id<MTLCounterSampleBuffer> mc_profile_end(Enc *, int *);
extern void mc_profile_labels(Enc *, char *, int, int);
extern void mc_profile_read(Ctx *, id<MTLCounterSampleBuffer>, int, double *);
extern int mc_render_begin(Enc *, int, id<MTLTexture> const *, const float *, id<MTLTexture>, int, float, int, int);
extern id<MTLCommandBuffer> mc_enc_commit(Enc *);
extern void *mcl_cols_new(Ctx *, const char *, char *, int);
extern int mcl_mesh_draw(void *, Enc *, int, const void *, int, id<MTLBuffer>, id<MTLBuffer>, id<MTLBuffer>,
    id<MTLBuffer>, id<MTLBuffer>, id<MTLBuffer>, id<MTLBuffer>, id<MTLBuffer>, id<MTLBuffer>, id<MTLBuffer>, id<MTLTexture>,
    id<MTLBuffer>, const void *, int, int, id<MTLBuffer>, char *, int);

static void check(int ok, const char *message) { if (!ok) { fprintf(stderr,"%s\n", message); exit(1); } }

int main(int argc, const char **argv) { @autoreleasepool {
    check(argc == 2, "Expected columns.metal");
    char error[16384]={0}, name[256]={0};
    Ctx *ctx=mc_create(name,sizeof name,error,sizeof error); check(ctx!=NULL,error);
    NSString *base=[NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:NULL];
    check(base!=nil,"Cannot read shader");
    for(int profile=0;profile<2;profile++) {
        NSString *source=[(profile?@"#define SEAM_HANDOFF 1\n#define LOD_PROFILE_SPLIT 1\n":@"#define SEAM_HANDOFF 1\n") stringByAppendingString:base];
        void *lod=mcl_cols_new(ctx,source.UTF8String,error,sizeof error); check(lod!=NULL,error);
        Enc *enc=mc_enc_new(ctx);
        check(mc_profile_begin(enc,32),"Device lacks stage timestamp sampling");
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:64 height:64 mipmapped:NO];
        td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageRenderTarget;
        id<MTLTexture> color=[ctx->device newTextureWithDescriptor:td];
        td.pixelFormat=MTLPixelFormatDepth32Float;td.storageMode=MTLStorageModePrivate;
        id<MTLTexture> depth=[ctx->device newTextureWithDescriptor:td];
        float clear[]={1,0.25,0.5,0.75,1};
        mc_render_begin(enc,1,&color,clear,depth,1,0,64,64);
        id<MTLBuffer> data=[ctx->device newBufferWithLength:131072 options:MTLResourceStorageModeShared];
        memset(data.contents,0,data.length); // zero indirect draws: validates the production split/store/load and state setup
        td.pixelFormat=MTLPixelFormatRGBA8Unorm;td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;td.width=td.height=1;
        id<MTLTexture> atlas=[ctx->device newTextureWithDescriptor:td];
        float frame[640]={0}, meshFrame[640]={0};
        unsigned serial=enc->renderSerial;
        int split=mcl_mesh_draw(lod,enc,0,frame,sizeof frame,data,data,data,data,data,data,data,data,data,data,atlas,data,
            meshFrame,sizeof meshFrame,0,data,error,sizeof error);
        check(split==profile,error);
        check(enc->renderSerial==serial+(profile?2:0),"Profiling changed an unexpected number of render encoders");
        char labels[32][128]={0};mc_profile_labels(enc,(char *)labels,128,32);
        int count=0;id<MTLCounterSampleBuffer> samples=mc_profile_end(enc,&count);
        id<MTLCommandBuffer> cmd=mc_enc_commit(enc);[cmd waitUntilCompleted];
        check(cmd.status==MTLCommandBufferStatusCompleted,cmd.error.description.UTF8String);
        check(count==(profile?12:4),"Wrong render stage sample count");
        if(profile) check(strstr(labels[1],"lod mesh (profile split)")!=NULL,"LOD draw was not identified separately");
        double times[128]={0};mc_profile_read(ctx,samples,count,times);
        unsigned char pixels[64*64*4];[color getBytes:pixels bytesPerRow:256 fromRegion:MTLRegionMake2D(0,0,64,64) mipmapLevel:0];
        for(int i=0;i<64*64;i++) check(abs(pixels[i*4]-64)<=1 && abs(pixels[i*4+1]-128)<=1 && abs(pixels[i*4+2]-191)<=1 && pixels[i*4+3]==255,
            "Diagnostic LOD split lost color contents");
        [cmd release];[color release];[depth release];[atlas release];[data release];free(enc);
    }
    puts("PASS: production LOD profiling splits only when requested, preserves color contents, samples three render encoders and labels the isolated LOD draw");
    return 0;
} }
