// clang -O2 metal/src/metalChecks/native/overhang-mesh.c metal/src/main/native/lodmesh.c -o /tmp/mcopt-overhang-mesh
#include "../../main/native/lodmesh.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

int main(void) {
	uint32_t *geom = calloc(4096,4), *crown = calloc(4096,4), *runs = calloc(4096,4);
	uint32_t *headers = calloc(64 * LM_HDR,4), *quads = calloc(65536 * 2,4);
	for (int i = 0; i < 4096; i++) geom[i] = (64 + 512) | 0x2000;
	for (int z = 16; z < 32; z++) for (int x = 16; x < 32; x++) {
		int i = z * 64 + x;
		geom[i] = (81 + 512) | 0x2000 | 0x4000 | (1 << 15);
		crown[i] = 64 + 512;
	}
	LmIn in = {.geom=geom, .crown=crown, .runs=runs, .logN=6, .block=8, .neighbors=15};
	int n = lm_mesh(&in,headers,quads,65536), topArea=0, bottomArea=0, groundArea=0;
	assert(n > 0);
	for (int i = 0; i < n; i++) {
		uint32_t q = quads[2*i], h = quads[2*i+1];
		int face=(q>>26)&7, kind=(q>>29)&3, y0=(h&4095)-512, y1=((h>>12)&4095)-512;
		int area=(((q>>14)&63)+1)*(((q>>20)&63)+1);
		if (kind == LM_K_CROWN) {
			assert(y0 >= 80 && y0 <= 81);
			if (face == LM_F_TOP) { assert(y0 == 81); topArea += area; }
			else if (face == LM_F_BOTTOM) { assert(y0 == 80); bottomArea += area; }
			else assert(y1 == 81); // No roof wall is allowed to fill the air gap.
		} else {
			assert(y0 <= 64);
			if (face == LM_F_TOP && kind == LM_K_UNDER) { assert(y0 == 64); groundArea += area; }
			if (face != LM_F_TOP && face != LM_F_BOTTOM && face != LM_F_PLANT) assert(y1 <= 64);
		}
	}
	assert(topArea == 256 && bottomArea == 256 && groundArea == 256);
	// The same cap over water keeps both surfaces and their different materials. No water-colored/cap-colored wall in the gap.
	for (int z = 16; z < 32; z++) for (int x = 16; x < 32; x++) crown[z*64+x] |= 1u << 28;
	n = lm_mesh(&in,headers,quads,65536);
	topArea = bottomArea = groundArea = 0;
	for (int i=0;i<n;i++) {
		uint32_t q=quads[2*i], h=quads[2*i+1];
		int face=(q>>26)&7, kind=(q>>29)&3, y0=(h&4095)-512, y1=((h>>12)&4095)-512;
		int area=(((q>>14)&63)+1)*(((q>>20)&63)+1);
		if (kind==LM_K_CROWN) {
			assert(y0>=80 && y0<=81);
			if (face==LM_F_TOP) topArea+=area;
			else if (face==LM_F_BOTTOM) bottomArea+=area;
			else assert(y1==81);
		} else if (kind==LM_K_WATER) {
			assert(y0<=64);
			if (face==LM_F_TOP) groundArea+=area;
			else assert(y1<=64);
		}
	}
	assert(topArea==256 && bottomArea==256 && groundArea==256);
	// Three floors separated by twenty-block gaps. The lower surface can also be water or a finite End island.
	for(int mode=0;mode<3;mode++) {
		for(int z=16;z<32;z++) for(int x=16;x<32;x++) {
			int i=z*64+x;
			geom[i]=(121+512)|0x6000u|(41u<<15)|(8u<<25);
			crown[i]=(64+512)|(1u<<30)|(mode==1?1u<<28:mode==2?1u<<29:0);
			runs[i]=(3u<<30)|(20u<<10)|(40u<<20);
		}
		n=lm_mesh(&in,headers,quads,65536);int tops[3]={0},bottoms[3]={0};groundArea=0;
		assert(n>0);
		for(int i=0;i<n;i++) {
			uint32_t q=quads[2*i],h=quads[2*i+1];
			int face=(q>>26)&7,kind=(q>>29)&3,y0=(h&4095)-512,y1=((h>>12)&4095)-512;
			int area=(((q>>14)&63)+1)*(((q>>20)&63)+1);
			if(kind==LM_K_CROWN) {
				int k=(y0-80)/20;assert(k>=0 && k<3);
				if(face==LM_F_TOP) {assert(y0==81+20*k);tops[k]+=area;}
				else if(face==LM_F_BOTTOM) {assert(y0==80+20*k);bottoms[k]+=area;}
				else {assert(y0==80+20*k && y1==81+20*k);}
			} else if(face==LM_F_TOP && y0==64 && kind==(mode==1?LM_K_WATER:LM_K_UNDER)) groundArea+=area;
		}
		for(int k=0;k<3;k++) assert(tops[k]==256 && bottoms[k]==256);
		assert(groundArea==256);
	}
	// Build-height roof over the ocean: the long empty gap is not a wall or an upper water interval.
	for (int roof=200;roof<=319;roof+=119) {
		for (int z=16;z<32;z++) for (int x=16;x<32;x++) {
			int i=z*64+x;
			geom[i]=(roof+1+512)|0x6000u|(1u<<15)|(1u<<24);
			crown[i]=(63+512)|(1u<<28)|(1u<<30);runs[i]=1u<<30;
		}
		n=lm_mesh(&in,headers,quads,65536);topArea=bottomArea=groundArea=0;
		for (int i=0;i<n;i++) {
			uint32_t q=quads[2*i],h=quads[2*i+1];
			int face=(q>>26)&7,kind=(q>>29)&3,y0=(h&4095)-512,y1=((h>>12)&4095)-512;
			int area=(((q>>14)&63)+1)*(((q>>20)&63)+1);
			if (kind==LM_K_CROWN) {
				assert(y0>=roof && y0<=roof+1);
				if (face==LM_F_TOP) {assert(y0==roof+1);topArea+=area;}
				else if (face==LM_F_BOTTOM) {assert(y0==roof);bottomArea+=area;}
				else assert(y1==roof+1);
			} else if (kind==LM_K_WATER && face==LM_F_TOP && y0==63) groundArea+=area;
		}
		assert(topArea==256 && bottomArea==256 && groundArea==256);
	}
	free(geom); free(crown); free(runs); free(headers); free(quads);
	puts("PASS: actual LOD mesh preserves roofs and three separate floors over dry/wet/finite ground, with no walls filling the air gaps");
}
