// clang -O2 metal/src/metalChecks/native/shoreline-mesh.c metal/src/main/native/lodmesh.c -o /tmp/mcopt-shoreline-mesh
// Optional argument: an older libmcmetal.dylib, to verify the regression against the actual previous release.
#include "../../main/native/lodmesh.h"
#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static int (*mesh)(const LmIn *, uint32_t *, uint32_t *, int) = lm_mesh;
static void check(int ok, const char *message) { if (!ok) { fprintf(stderr,"FAIL: %s\n",message); exit(1); } }

int main(int argc, char **argv) {
	if (argc > 1) {
		void *old = dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
		check(old != NULL,"Could not load previous native library");
		mesh = dlsym(old,"lm_mesh"); check(mesh != NULL,"Missing previous mesher");
	}
	uint32_t *g=calloc(4096,4), *cr=calloc(4096,4), *h=calloc(64*LM_HDR,4), *q=calloc(65536*2,4);
	int cases=0;
	for(int block=8;block<=16;block*=2) for(int axis=0;axis<2;axis++) for(int flip=0;flip<2;flip++)
	for(int edge=27;edge<=32;edge+=5) for(int base=-7;base<=64;base+=71) for(int rise=-1;rise<=2;rise++) for(int roof=0;roof<2;roof++) {
		for(int z=0;z<64;z++) for(int x=0;x<64;x++) {
			int wet=((axis?z:x)<edge)^flip, i=z*64+x;
			g[i]=(uint32_t)(base+(wet?0:rise)+512)|0x2000u|(wet?0x1000u:0);
			cr[i]=0;
			if(wet&&roof) { g[i]=(base+20+512)|0x6000u|(1u<<15); cr[i]=(base+512)|(1u<<28); }
		}
		LmIn in={.geom=g,.crown=cr,.logN=6,.block=block,.neighbors=15,.tx=-1,.tz=-2};
		int count=mesh(&in,h,q,65536); check(count>0,"Mesh generation failed");
		int covered=0, nb=64/block, face=axis?(flip?LM_F_ZP:LM_F_ZN):(flip?LM_F_XP:LM_F_XN);
		for(int b=0;b<nb*nb;b++) for(int group=LM_G_XP;group<=LM_G_BZN;group++) {
			uint32_t range=h[b*LM_HDR+group]; int at=range&0xFFFFF, n=range>>20;
			for(int j=at;j<at+n;j++) {
				uint32_t a=q[2*j], v=q[2*j+1];
				if(((a>>26)&7)!=face || (int)(axis?(a>>7)&127:a&127)!=edge || ((a>>29)&3)==LM_K_WATER) continue;
				float lo=(int)(v&4095)-512-((v&(1u<<24))?1.0f/9+0.001f:0), hi=(int)((v>>12)&4095)-512;
				if(lo<=base-1.0f/9-0.001f+0.00001f && hi>=base) covered+=((a>>14)&63)+1;
			}
		}
		check(covered==(rise>=0?64:0),"Bank leaves an uncovered strip above the lowered water plane");
		cases++;
	}
	// Equal water surfaces and equal dry surfaces need no fractional bank strips.
	for(int wet=0;wet<2;wet++) {
		for(int i=0;i<4096;i++) { g[i]=(64+512)|0x2000|(wet?0x1000:0); cr[i]=0; }
		LmIn in={.geom=g,.crown=cr,.logN=6,.block=8,.neighbors=15};
		int n=mesh(&in,h,q,65536); check(n>0,"Uniform mesh failed");
		for(int i=0;i<n;i++) check((q[2*i+1]&(1u<<24))==0,"Unnecessary bank strip on uniform terrain");
	}
	free(g);free(cr);free(h);free(q);
	printf("PASS: %d shoreline layouts close raised/level banks in all directions, at block boundaries and negative heights, including water beneath roofs\n",cases);
}
