// clang -O2 -fobjc-arc -framework Foundation -framework Metal metal/src/metalChecks/native/lod-gpu-work.m -o /tmp/mcopt-lod-gpu-work
// /tmp/mcopt-lod-gpu-work columns-before.metal metal/src/main/resources/mcopt/lod/columns.metal
// Compares production kernels with a saved pre-change shader, including untouched PK bins and far-minimum storage.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdint.h>

static void check(BOOL ok, NSString *message) {
    if (!ok) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}
static uint32_t randomWord(uint32_t *state) {
    *state ^= *state << 13; *state ^= *state >> 17; *state ^= *state << 5; return *state;
}
static id<MTLBuffer> buffer(id<MTLDevice> d, NSUInteger length) {
    id<MTLBuffer> b = [d newBufferWithLength:length options:MTLResourceStorageModeShared];
    memset(b.contents, 0, length); return b;
}
static id<MTLComputePipelineState> pipeline(id<MTLDevice> d, id<MTLLibrary> lib, NSString *name) {
    NSError *error = nil;
    id<MTLComputePipelineState> p = [d newComputePipelineStateWithFunction:[lib newFunctionWithName:name] error:&error];
    check(p != nil, error.description); return p;
}
static double run(id<MTLCommandQueue> q, id<MTLComputePipelineState> p, NSArray<id<MTLBuffer>> *buffers,
                  const int *slots, NSUInteger count, NSUInteger group, int repeats) {
    id<MTLCommandBuffer> cb = [q commandBuffer];
    id<MTLComputeCommandEncoder> e = [cb computeCommandEncoder];
    [e setComputePipelineState:p];
    for (NSUInteger i=0; i<buffers.count; i++) [e setBuffer:buffers[i] offset:0 atIndex:slots[i]];
    for (int i=0; i<repeats; i++) {
        [e dispatchThreads:MTLSizeMake(count,1,1) threadsPerThreadgroup:MTLSizeMake(group,1,1)];
        if (i+1<repeats) [e memoryBarrierWithScope:MTLBarrierScopeBuffers];
    }
    [e endEncoding]; [cb commit]; [cb waitUntilCompleted];
    check(cb.status==MTLCommandBufferStatusCompleted, cb.error.description);
    return (cb.GPUEndTime-cb.GPUStartTime)*1e6/repeats;
}
static int compareDouble(const void *a,const void *b) {
    double x=*(const double *)a,y=*(const double *)b; return (x>y)-(x<y);
}
static void horizons(id<MTLDevice> d, id<MTLCommandQueue> q, id<MTLLibrary> old, id<MTLLibrary> current) {
    id<MTLBuffer> input=buffer(d,4096*129*4), a=buffer(d,input.length), b=buffer(d,input.length), pk=buffer(d,128);
    uint32_t state=0x5819AB43;
    for (int mode=0; mode<2; mode++) {
        NSString *name=mode?@"lod_pk_hz_prefix":@"lod_mesh_hz_prefix";
        id<MTLComputePipelineState> before=pipeline(d,old,name), after=pipeline(d,current,name);
        NSUInteger width=after.threadExecutionWidth;
        for (int pattern=0; pattern<12; pattern++) {
            uint32_t *words=input.contents;
            for (int i=0; i<4096*129; i++) {
                uint32_t r=randomWord(&state);
                words[i]=pattern==0?0:pattern==1?UINT32_MAX:pattern==2?(i&127):pattern==3?127-(i&127):
                    pattern==4?((i&127)==31?UINT32_MAX:0):pattern==5?((i&127)==32?UINT32_MAX:0):
                    pattern==6?((i&127)==127?UINT32_MAX:0):pattern==7?((i&127)==0?UINT32_MAX:0):
                    pattern==8?((r&15)==0?r:0):r;
            }
            uint32_t *params=pk.contents;
            params[12]=pattern==0?0:pattern==1?UINT32_MAX:randomWord(&state);
            params[13]=pattern==0?0:pattern==1?UINT32_MAX:randomWord(&state);
            memcpy(a.contents,input.contents,input.length); memcpy(b.contents,input.contents,input.length);
            int slots[]={8,14};
            run(q,before,@[a,pk],slots,4096,64,1);
            run(q,after,@[b,pk],slots,4096*width,width*4,1);
            check(!memcmp(a.contents,b.contents,input.length),[NSString stringWithFormat:@"Horizon mismatch mode %d pattern %d",mode,pattern]);
            check(!memcmp((char *)b.contents+4096*128*4,(char *)input.contents+4096*128*4,4096*4),@"Prefix overwrote far-minimum storage");
        }
        double times[2][16]; int slots[]={8,14};
        ((uint32_t *)pk.contents)[12]=((uint32_t *)pk.contents)[13]=UINT32_MAX;
        for (int trial=0; trial<18; trial++) for (int phase=0; phase<2; phase++) {
            int variant=(trial+phase)&1;
            double us=run(q,variant?after:before,@[variant?b:a,pk],slots,variant?4096*width:4096,variant?width*4:64,32);
            if(trial>=2) times[variant][trial-2]=us;
        }
        for(int i=0;i<2;i++) qsort(times[i],16,sizeof(double),compareDouble);
        printf("PASS: %s exact full-buffer match in 12 patterns; SIMD width %lu; isolated repeated kernel median before %.3f us, after %.3f us\n",
            name.UTF8String,(unsigned long)width,(times[0][7]+times[0][8])/2,(times[1][7]+times[1][8])/2);
    }
}

int main(int argc,const char **argv) { @autoreleasepool {
    check(argc==3,@"Expected pre-change and current columns.metal paths");
    id<MTLDevice> d=MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> q=[d newCommandQueue];
    id<MTLLibrary> libs[2]; NSError *error=nil;
    // Each wall stencil uses the actual production occupancy routines and the original eight query locations.
    NSString *wrapper=@"\nkernel void wall_stencil(constant CompFrame& f [[buffer(0)]], device const uint* geom [[buffer(1)]], "
        "device const uint* crowns [[buffer(2)]], device uint* out [[buffer(3)]], uint i [[thread_position_in_grid]]) { "
        "int x=int(i&255u)-256,z=int(i>>8)-256; uint idx=(i&~255u)|((i+1u)&255u); uint word=geom[idx],crown=crowns[idx]; int top=GEOM_Y(word),ground=int(crown&4095u)-512; "
        "for(uint k=0u;k<16u;k++) { int y=k==0u?top:k==1u?top-1:k==2u?top+1:k==3u?ground:k==4u?ground-1:k==5u?ground+1:"
        "k==6u?top-GEOM_THICK(word):k==7u?-512:k==8u?-100000:k==9u?-100001:int((i*719u+k*419u)%2048u)-768; "
        "int b=y,ox=x+1,oz=z,ux=0,uz=1; "
        "WALL_QUERIES "
        "out[i*16u+k]=uint(belowFront)|uint(aboveFront)<<1|uint(sideM)<<2|uint(sideP)<<3|uint(cMb)<<4|uint(cPb)<<5|uint(cMt)<<6|uint(cPt)<<7; }}\n";
    for (int variant=0;variant<2;variant++) {
        NSString *source=[NSString stringWithContentsOfFile:@(argv[variant+1]) encoding:NSUTF8StringEncoding error:&error];
        check(source!=nil,error.description);
        // Extract each version's production wall stencil. Keep the reference independent of the replacement helper.
        NSRange start=[source rangeOfString:@"#else\n    bool" options:NSBackwardsSearch range:NSMakeRange(0,[source rangeOfString:@"float a00 = compVertexAo(belowFront"].location)];
        check(start.location!=NSNotFound,@"Cannot find production wall stencil");
        NSUInteger begin=start.location+6;
        NSRange end=[source rangeOfString:@"#endif" options:0 range:NSMakeRange(begin,source.length-begin)];
        NSString *queries=[source substringWithRange:NSMakeRange(begin,end.location-begin)];
        source=[source stringByAppendingString:[wrapper stringByReplacingOccurrencesOfString:@"WALL_QUERIES" withString:queries]];
        MTLCompileOptions *options=[MTLCompileOptions new]; options.languageVersion=MTLLanguageVersion3_1;options.mathMode=MTLMathModeSafe;
        libs[variant]=[d newLibraryWithSource:source options:options error:&error];check(libs[variant]!=nil,error.description);
    }
    horizons(d,q,libs[0],libs[1]);
    id<MTLBuffer> frame=buffer(d,4096),g=buffer(d,65536*4),cr=buffer(d,65536*8),out[2]={buffer(d,65536*16*4),buffer(d,65536*16*4)};
    float *f=frame.contents; ((int *)f)[55]=8; f[43]=65536;
    uint32_t state=0xF16729A3;
    for(int i=0;i<65536;i++) {
        // Cover malformed/unknown data too: an optimization must not invent solids from partially updated columns.
        ((uint32_t *)g.contents)[i]=randomWord(&state);
        ((uint32_t *)cr.contents)[i]=randomWord(&state);
        ((uint32_t *)cr.contents)[65536+i]=randomWord(&state);
    }
    id<MTLComputePipelineState> p[2]={pipeline(d,libs[0],@"wall_stencil"),pipeline(d,libs[1],@"wall_stencil")};
    for(int mode=0;mode<3;mode++) {
        f[51]=mode==0?0:mode==1?1:-1;
        int slots[]={0,1,2,3};
        for(int v=0;v<2;v++) run(q,p[v],@[frame,g,cr,out[v]],slots,65536,128,1);
        check(!memcmp(out[0].contents,out[1].contents,out[0].length),[NSString stringWithFormat:@"Wall AO stencil mismatch vertical mode %d",mode]);
    }
    puts("PASS: production wall AO stencils match bit-for-bit for 3,145,728 cases (8 probes each), random columns, interval boundaries and enabled/disabled vertical data");
    f[51]=1;
    double times[2][16]; int slots[]={0,1,2,3};
    for(int trial=0;trial<18;trial++) for(int phase=0;phase<2;phase++) {
        int v=(trial+phase)&1;
        double us=run(q,p[v],@[frame,g,cr,out[v]],slots,65536,128,1);
        if(trial>=2)times[v][trial-2]=us;
    }
    for(int v=0;v<2;v++)qsort(times[v],16,sizeof(double),compareDouble);
    printf("Wall stencil isolated kernel median before %.3f us, after %.3f us (synthetic workload, not a frame-time forecast)\n",
        (times[0][7]+times[0][8])/2,(times[1][7]+times[1][8])/2);
    return 0;
} }
