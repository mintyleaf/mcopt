// clang -O2 metal/src/metalChecks/native/water-mesh.c metal/src/main/native/lodmesh.c -o /tmp/mcopt-water-mesh
#include "../../main/native/lodmesh.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

int main(void) {
	uint32_t *geom = calloc(64 * 64, 4), *headers = calloc(64 * LM_HDR, 4), *quads = calloc(65536 * 2, 4);
	for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) geom[z * 64 + x] = 575 | 0x2000 | (x < 32 ? 0x1000 : 0);
	LmIn in = {.geom = geom, .logN = 6, .block = 8, .neighbors = 15};
	int n = lm_mesh(&in, headers, quads, 65536);
	assert(n > 0);
	int wetArea = 0, dryArea = 0;
	for (int i = 0; i < n; i++) {
		uint32_t q = quads[2 * i];
		if (((q >> 26) & 7) != LM_F_TOP) continue;
		int area = (((q >> 14) & 63) + 1) * (((q >> 20) & 63) + 1);
		int kind = (q >> 29) & 3;
		assert(kind == LM_K_WATER || kind == LM_K_GROUND);
		if (kind == LM_K_WATER) wetArea += area; else dryArea += area;
	}
	assert(wetArea == 32 * 64 && dryArea == 32 * 64);
	free(geom); free(headers); free(quads);
	puts("PASS: wet/dry surfaces at the same integer height keep separate mesh materials and full coverage");
}
