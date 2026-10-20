// Exercise production compaction and vertex decoding on the GPU, with the frozen prior vertex path as reference.
kernel void test_setup(device MeshFrame* mf [[buffer(0)]], device uint4* table [[buffer(1)]], device uint* args [[buffer(4)]],
                       device uint4* input [[buffer(17)]], device uint* legacyArgs [[buffer(18)]], device atomic_uint* errors [[buffer(19)]],
                       device MeshInstance* inst [[buffer(23)]], device uint* arena [[buffer(24)]], device CompFrame* comp [[buffer(22)]],
                       device uint* geom [[buffer(12)]], device uint* crowns [[buffer(13)]], constant uint2& params [[buffer(9)]],
                       uint gid [[thread_position_in_grid]]) {
    if (gid == 0u) {
        MeshFrame m = {}; CompFrame f = {};
        m.origin = int4(-13, 90, 7, 5); m.counts = int4(4,16,1024,1024); m.caps = int4(params.y,1024,0,0);
        for (int l=0;l<5;l++) m.win[l] = int4(-2,-2,0,0);
        f.origin = int4(-13,90,7,8); f.camFrac = float4(0.25,0.125,0.75,0);
        f.viewProj = float4x4(float4(0.001,0,0,0),float4(0,0.001,0,0),float4(0,0,0.001,0),float4(0,0,0,1));
        f.handoff = int4(0,0,1,0); f.handoffBand = float4(100,150,0,0);
        mf[0]=m;comp[0]=f; atomic_store_explicit(errors,0u,memory_order_relaxed);
    }
    if (gid < 128u) {args[gid]=0u;legacyArgs[gid]=gid==ARGS_SURV?params.x:0u;}
    if (gid < 5u*16u) {
        uint l=gid/16u,slot=gid%16u;
        table[gid]=uint4(1u,uint(int(slot%4u)-2),uint(int(slot/4u)-2),0u);
    }
    if (gid < 5u*256u*256u) {geom[gid]=uint(64+512)|0x2000u;crowns[gid]=0u;}
    if (gid >= params.x) return;
    uint L=gid%5u,face=gid%6u,x=(gid*13u)%64u,z=(gid*29u)%64u;
    int tx=int((gid/5u)%4u)-2,tz=int((gid/20u)%4u)-2;
    uint w0=x|(z<<7)|((gid%32u)<<14)|(((gid*7u)%32u)<<20)|(face<<26);
    if ((gid%7u)==0u && face>=2u) w0|=1u<<31; // dynamic skirt
    uint w1=uint(64+512)|(uint(70+512)<<12)|((gid%3u)==0u?1u<<24:0u);
    uint qi=256u+gid;arena[qi*2u]=w0;arena[qi*2u+1u]=w1;
    bool clipped=(gid%3u)==0u,relative=(gid%2u)==0u;
    MeshInstance mi;mi.a=uint4(relative?gid:qi,1u|(L<<8)|(clipped?1u<<12:0u)|(relative?MESH_REL:0u),uint(tx),uint(tz));
    int px=((tx*64+int(x))<<L)+13,pz=((tz*64+int(z))<<L)-7;
    mi.b=int4(px,px+(int(1u+(gid%16u))<<L),pz,pz+(int(1u+(gid%16u))<<L));inst[gid]=mi;
    input[gid]=clipped?uint4(0u,0u,gid,(1u<<24)|(L<<20))
        :uint4(w0,w1,uint(tx),(uint(tz)&0xFFFFFu)|(L<<20)|((gid%4u)<<25));
}

kernel void test_append(constant MeshFrame& mf [[buffer(0)]], device const uint4* table [[buffer(1)]], device atomic_uint* args [[buffer(4)]],
                        device uint4* survivors [[buffer(7)]], constant uint2& params [[buffer(9)]], device const uint4* input [[buffer(17)]],
                        constant CompFrame& f [[buffer(22)]], device const MeshInstance* inst [[buffer(23)]], device const uint* arena [[buffer(24)]],
                        uint gid [[thread_position_in_grid]],uint lane [[thread_index_in_simdgroup]]) {
    bool keep=gid<params.x;uint4 r=keep?input[gid]:uint4(0);
    bool handoff=keep && meshRecordHandoff(f,mf,table,inst,arena,r);
    meshAppendSurvivor(keep,handoff,r,mf,args,survivors,lane);
}

kernel void test_validate(constant MeshFrame& mf [[buffer(0)]], device const uint4* table [[buffer(1)]], device const uint* args [[buffer(4)]],
                          device const uint4* survivors [[buffer(7)]], constant uint2& params [[buffer(9)]], device const uint* geom [[buffer(12)]],
                          device const uint* crowns [[buffer(13)]], device const uint4* input [[buffer(17)]], device const uint* legacyArgs [[buffer(18)]],
                          device atomic_uint* errors [[buffer(19)]], constant CompFrame& f [[buffer(22)]], device const MeshInstance* inst [[buffer(23)]],
                          device const uint* arena [[buffer(24)]], uint gid [[thread_position_in_grid]]) {
    bool partitioned=args[ARGS_PARTITIONED]!=0u;
    uint q=gid/4u,corner=gid%4u,count=partitioned?args[lodHandoffPass?ARGS_HANDOFF:ARGS_OPAQUE]:args[ARGS_SURV];
    MeshVOut actual=meshSurvivorCorner((q%MESH_DRAW_Q)*4u+corner,q/MESH_DRAW_Q,f,inst,arena,args,survivors,table,geom,crowns,mf);
    if(q>=count) {
        if(actual.info!=0u || any(actual.position!=float4(0,0,-2,1)))atomic_fetch_add_explicit(errors,1u,memory_order_relaxed);
        return;
    }
    uint4 r=survivors[partitioned && lodHandoffPass?uint(mf.caps.x)-1u-q:q];uint id=params.x;
    for(uint i=0;i<params.x;i++)if(all(input[i]==r)){id=i;break;}
    if(id==params.x) {atomic_fetch_add_explicit(errors,1u,memory_order_relaxed);return;}
    MeshVOut expected=reference_mesh_vs2((id%MESH_DRAW_Q)*4u+corner,id/MESH_DRAW_Q,f,inst,arena,legacyArgs,input,table,geom,crowns,mf);
    if(actual.info!=expected.info || any(abs(actual.position-expected.position)>float4(1e-6)))atomic_fetch_add_explicit(errors,1u,memory_order_relaxed);
}
