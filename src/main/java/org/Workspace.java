package org;

import java.util.ArrayDeque;
import java.util.Arrays;

public class Workspace {

    @SuppressWarnings("unchecked")
    private final ArrayDeque<int[]>[]        intPool  = new ArrayDeque[32];
    @SuppressWarnings("unchecked")
    private final ArrayDeque<long[]>[]       longPool = new ArrayDeque[32];
    @SuppressWarnings("unchecked")
    private final ArrayDeque<MutableBigInt>[] mbiPool = new ArrayDeque[32];

    public Workspace() {
        for (int i = 0; i < 32; i++) {
            intPool[i]  = new ArrayDeque<>();
            longPool[i] = new ArrayDeque<>();
            mbiPool[i]  = new ArrayDeque<>();
        }
    }

    static int getIndex(int size) {
        if (size <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    public int[] getIntArray(int size) {
        int   idx = getIndex(size);
        int[] arr = intPool[idx].isEmpty() ? new int[1 << idx]
                : intPool[idx].pop();
        // OPTIMIZATION: Targeted zeroing. Only zero up to requested size, not full 2^N.
        Arrays.fill(arr, 0, size, 0);
        return arr;
    }

    public void release(int[] array) {
        intPool[getIndex(array.length)].push(array);
    }

    public long[] getLongArray(int size) {
        int    idx = getIndex(size);
        long[] arr = longPool[idx].isEmpty() ? new long[1 << idx]
                : longPool[idx].pop();
        // OPTIMIZATION: Targeted zeroing.
        Arrays.fill(arr, 0, size, 0L);
        return arr;
    }

    public void release(long[] array) {
        longPool[getIndex(array.length)].push(array);
    }

    public MutableBigInt getMutable(int size) {
        int idx = getIndex(size);
        if (!mbiPool[idx].isEmpty()) {
            MutableBigInt mbi = mbiPool[idx].pop();
            mbi.length = 0;
            return mbi;
        }
        return new MutableBigInt(1 << idx);
    }

    public void release(MutableBigInt mbi) {
        if (mbi == null) return;
        mbiPool[getIndex(mbi.mag.length)].push(mbi);
    }
}