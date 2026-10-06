package com.millsboss.oneblockchunk;

final class Chunks {
    private Chunks() {
    }

    static long key(int cx, int cz) {
        return ((long) cx & 0xffffffffL) | (((long) cz & 0xffffffffL) << 32);
    }
}
