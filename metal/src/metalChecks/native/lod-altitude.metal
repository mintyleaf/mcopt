// Calls the production tier selector: every footprint belongs to exactly one tier, including shifted PK selection cameras.
kernel void test_altitude_levels(constant MeshFrame* frames [[buffer(0)]], device atomic_uint* errors [[buffer(1)]],
                                 uint gid [[thread_position_in_grid]]) {
    const uint samples = 4096;
    constant MeshFrame& f = frames[gid / samples];
    uint seed = (gid % samples) * 747796405u + 2891336453u;
    int x = int(seed % 12001u) - 6000 + f.origin.x;
    seed = seed * 1664525u + 1013904223u;
    int z = int(seed % 12001u) - 6000 + f.origin.z;
    // Include exact cell/parent boundaries and the protected near ring, in all quadrants.
    if (gid % samples < 256u) { x = f.origin.x + int(gid % 16u)*16 - 128; z = f.origin.z + int((gid/16u)%16u)*16 - 128; }
    for (int selection = 0; selection < 2; selection++) {
        int owners = 0, chosen = -1;
        for (int L=0; L<f.origin.w; L++) {
            int bx = x >> (4+L), bz = z >> (4+L);
            bool right = selection ? meshRightLevelNoMask<true>(f,L,bx,bz) : meshRightLevelNoMask(f,L,bx,bz);
            if (right) { owners++; chosen=L; }
        }
        if (owners != 1 || (gid % samples < 256u && chosen != 0)) atomic_fetch_add_explicit(errors,1u,memory_order_relaxed);
    }
}
