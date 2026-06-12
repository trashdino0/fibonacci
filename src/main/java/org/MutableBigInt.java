package org;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

public class MutableBigInt {

    private static final ForkJoinPool POOL = new ForkJoinPool();
    static final int NTT_THRESHOLD = 32;

    int[] mag;
    int   length;

    public MutableBigInt(int size) {
        mag    = new int[size];
        length = 0;
    }

    public void fromLong(long value) {
        if (value == 0) { length = 0; return; }
        mag[0] = (int) value;
        mag[1] = (int) (value >>> 32);
        length = (mag[1] == 0) ? 1 : 2;
    }

    public void copy(MutableBigInt src) {
        if (src.length > mag.length)
            throw new IllegalStateException("copy: dest too small");
        System.arraycopy(src.mag, 0, mag, 0, src.length);
        length = src.length;
    }

    public void add(MutableBigInt other) {
        long carry = 0;
        int  max   = Math.max(length, other.length);
        for (int i = 0; i < max || carry != 0; i++) {
            if (i >= mag.length)
                throw new IllegalStateException("add: overflow");
            long sum = (i < length       ? (mag[i]       & 0xFFFFFFFFL) : 0L)
                    + (i < other.length ? (other.mag[i] & 0xFFFFFFFFL) : 0L)
                    + carry;
            mag[i] = (int) sum;
            carry  = sum >>> 32;
            if (i >= length) length = i + 1;
        }
    }

    public void subtract(MutableBigInt other) {
        long borrow = 0;
        for (int i = 0; i < length; i++) {
            long diff = (mag[i] & 0xFFFFFFFFL)
                    - (i < other.length ? (other.mag[i] & 0xFFFFFFFFL) : 0L)
                    - borrow;
            mag[i]  = (int) diff;
            borrow  = (diff >> 32) & 1L;
        }
        while (length > 0 && mag[length - 1] == 0) length--;
    }

    // NEW OPTIMIZATION: Dedicated shift-by-1 method for 2ab (much faster than division/modulo logic)
    public void shiftLeftOne() {
        if (length == 0) return;
        if (length >= mag.length)
            throw new IllegalStateException("shiftLeftOne: overflow");

        int carry = 0;
        for (int i = 0; i < length; i++) {
            int val = mag[i];
            mag[i] = (val << 1) | carry;
            carry = val >>> 31;
        }
        if (carry != 0) {
            mag[length++] = carry;
        }
    }

    public void shiftLeft(int bits) {
        if (bits == 0 || length == 0) return;
        if (bits == 1) { shiftLeftOne(); return; } // Route to optimized path
        int wordShift = bits / 32;
        int bitShift  = bits % 32;
        int needed    = length + wordShift + (bitShift == 0 ? 0 : 1);
        if (needed > mag.length)
            throw new IllegalStateException("shiftLeft: overflow");

        if (bitShift == 0) {
            System.arraycopy(mag, 0, mag, wordShift, length);
            Arrays.fill(mag, 0, wordShift, 0);
            length += wordShift;
        } else {
            int invShift = 32 - bitShift;
            int top      = (int) ((mag[length - 1] & 0xFFFFFFFFL) >>> invShift);
            for (int i = length - 1; i > 0; i--)
                mag[i + wordShift] = (mag[i] << bitShift)
                        | (int) ((mag[i - 1] & 0xFFFFFFFFL) >>> invShift);
            mag[wordShift] = mag[0] << bitShift;
            if (top != 0) { mag[length + wordShift] = top; length += wordShift + 1; }
            else            length += wordShift;
            Arrays.fill(mag, 0, wordShift, 0);
        }
        while (length > 0 && mag[length - 1] == 0) length--;
    }

    public void multiply(MutableBigInt other, MutableBigInt result, Workspace ws) {
        if (length == 0 || other.length == 0) { result.length = 0; return; }
        if (length < NTT_THRESHOLD || other.length < NTT_THRESHOLD)
            multiplySchoolbook(other, result);
        else
            multiplyNTT(other, result, ws);
    }

    private void multiplySchoolbook(MutableBigInt other, MutableBigInt result) {
        int newLen = length + other.length;
        Arrays.fill(result.mag, 0, newLen, 0);
        for (int i = 0; i < length; i++) {
            long carry = 0, aVal = mag[i] & 0xFFFFFFFFL;
            for (int j = 0; j < other.length; j++) {
                long prod = (result.mag[i + j] & 0xFFFFFFFFL)
                        + aVal * (other.mag[j] & 0xFFFFFFFFL) + carry;
                result.mag[i + j] = (int) prod;
                carry = prod >>> 32;
            }
            result.mag[i + other.length] = (int) carry;
        }
        result.length = newLen;
        while (result.length > 0 && result.mag[result.length - 1] == 0)
            result.length--;
    }

    private void multiplyNTT(MutableBigInt other, MutableBigInt result, Workspace ws) {
        int rawSize = length + other.length;
        int logN    = 32 - Integer.numberOfLeadingZeros(rawSize - 1);
        if (logN < 1) logN = 1;
        int n = 1 << logN;

        long[] a1 = ws.getLongArray(n), b1 = ws.getLongArray(n);
        long[] a2 = ws.getLongArray(n), b2 = ws.getLongArray(n);
        long[] a3 = ws.getLongArray(n), b3 = ws.getLongArray(n);
        long[] r1 = ws.getLongArray(n), r2 = ws.getLongArray(n), r3 = ws.getLongArray(n);

        fillLong(this,  a1); fillLong(other, b1);
        fillLong(this,  a2); fillLong(other, b2);
        fillLong(this,  a3); fillLong(other, b3);

        final int LN = logN;
        POOL.invoke(new RecursiveAction() { protected void compute() {
            invokeAll(
                    new RecursiveAction() { protected void compute() {
                        NTT.nttForward(a1, LN, 0); NTT.nttForward(b1, LN, 0);
                        long p = NTT.P1; double pI = NTT.INV_P1;
                        for (int i = 0; i < n; i++) r1[i] = NTT.mulmod(a1[i], b1[i], p, pI);
                        NTT.nttInverse(r1, LN, 0);
                    }},
                    new RecursiveAction() { protected void compute() {
                        NTT.nttForward(a2, LN, 1); NTT.nttForward(b2, LN, 1);
                        long p = NTT.P2; double pI = NTT.INV_P2;
                        for (int i = 0; i < n; i++) r2[i] = NTT.mulmod(a2[i], b2[i], p, pI);
                        NTT.nttInverse(r2, LN, 1);
                    }},
                    new RecursiveAction() { protected void compute() {
                        NTT.nttForward(a3, LN, 2); NTT.nttForward(b3, LN, 2);
                        long p = NTT.P3; double pI = NTT.INV_P3;
                        for (int i = 0; i < n; i++) r3[i] = NTT.mulmod(a3[i], b3[i], p, pI);
                        NTT.nttInverse(r3, LN, 2);
                    }}
            );
        }});

        garnerCRT(r1, r2, r3, result, n);

        ws.release(a1); ws.release(b1); ws.release(a2); ws.release(b2);
        ws.release(a3); ws.release(b3); ws.release(r1); ws.release(r2); ws.release(r3);
    }

    public static void fibDoubleNTT(MutableBigInt a, MutableBigInt b,
                                    MutableBigInt outA, MutableBigInt outB, Workspace ws) {
        int maxLen = Math.max(Math.max(a.length, b.length), 1);
        int rawSize = 2 * maxLen;
        int logN    = 32 - Integer.numberOfLeadingZeros(rawSize - 1);
        if (logN < 1) logN = 1;
        int n = 1 << logN;

        long[] fa1 = ws.getLongArray(n), fb1 = ws.getLongArray(n);
        long[] fa2 = ws.getLongArray(n), fb2 = ws.getLongArray(n);
        long[] fa3 = ws.getLongArray(n), fb3 = ws.getLongArray(n);

        long[] rA2_1 = ws.getLongArray(n), rB2_1 = ws.getLongArray(n), rAB_1 = ws.getLongArray(n);
        long[] rA2_2 = ws.getLongArray(n), rB2_2 = ws.getLongArray(n), rAB_2 = ws.getLongArray(n);
        long[] rA2_3 = ws.getLongArray(n), rB2_3 = ws.getLongArray(n), rAB_3 = ws.getLongArray(n);

        fillLong(a, fa1); fillLong(b, fb1);
        System.arraycopy(fa1, 0, fa2, 0, n); System.arraycopy(fa1, 0, fa3, 0, n);
        System.arraycopy(fb1, 0, fb2, 0, n); System.arraycopy(fb1, 0, fb3, 0, n);

        final int LN = logN;
        POOL.invoke(new RecursiveAction() { protected void compute() {
            invokeAll(
                    new RecursiveAction() { protected void compute() {
                        RecursiveAction fwdFb1 = new RecursiveAction() { protected void compute() { NTT.nttForward(fb1, LN, 0); } };
                        fwdFb1.fork(); NTT.nttForward(fa1, LN, 0); fwdFb1.join();

                        long p = NTT.P1; double pI = NTT.INV_P1;
                        for (int i = 0; i < n; i++) {
                            long ai = fa1[i], bi = fb1[i];
                            rA2_1[i] = NTT.mulmod(ai, ai, p, pI);
                            rB2_1[i] = NTT.mulmod(bi, bi, p, pI);
                            rAB_1[i] = NTT.mulmod(ai, bi, p, pI);
                        }

                        RecursiveAction invA2 = new RecursiveAction() { protected void compute() { NTT.nttInverse(rA2_1, LN, 0); } };
                        RecursiveAction invB2 = new RecursiveAction() { protected void compute() { NTT.nttInverse(rB2_1, LN, 0); } };
                        invA2.fork(); invB2.fork(); NTT.nttInverse(rAB_1, LN, 0); invA2.join(); invB2.join();
                    }},
                    new RecursiveAction() { protected void compute() {
                        RecursiveAction fwdFb2 = new RecursiveAction() { protected void compute() { NTT.nttForward(fb2, LN, 1); } };
                        fwdFb2.fork(); NTT.nttForward(fa2, LN, 1); fwdFb2.join();

                        long p = NTT.P2; double pI = NTT.INV_P2;
                        for (int i = 0; i < n; i++) {
                            long ai = fa2[i], bi = fb2[i];
                            rA2_2[i] = NTT.mulmod(ai, ai, p, pI);
                            rB2_2[i] = NTT.mulmod(bi, bi, p, pI);
                            rAB_2[i] = NTT.mulmod(ai, bi, p, pI);
                        }

                        RecursiveAction invA2 = new RecursiveAction() { protected void compute() { NTT.nttInverse(rA2_2, LN, 1); } };
                        RecursiveAction invB2 = new RecursiveAction() { protected void compute() { NTT.nttInverse(rB2_2, LN, 1); } };
                        invA2.fork(); invB2.fork(); NTT.nttInverse(rAB_2, LN, 1); invA2.join(); invB2.join();
                    }},
                    new RecursiveAction() { protected void compute() {
                        RecursiveAction fwdFb3 = new RecursiveAction() { protected void compute() { NTT.nttForward(fb3, LN, 2); } };
                        fwdFb3.fork(); NTT.nttForward(fa3, LN, 2); fwdFb3.join();

                        long p = NTT.P3; double pI = NTT.INV_P3;
                        for (int i = 0; i < n; i++) {
                            long ai = fa3[i], bi = fb3[i];
                            rA2_3[i] = NTT.mulmod(ai, ai, p, pI);
                            rB2_3[i] = NTT.mulmod(bi, bi, p, pI);
                            rAB_3[i] = NTT.mulmod(ai, bi, p, pI);
                        }

                        RecursiveAction invA2 = new RecursiveAction() { protected void compute() { NTT.nttInverse(rA2_3, LN, 2); } };
                        RecursiveAction invB2 = new RecursiveAction() { protected void compute() { NTT.nttInverse(rB2_3, LN, 2); } };
                        invA2.fork(); invB2.fork(); NTT.nttInverse(rAB_3, LN, 2); invA2.join(); invB2.join();
                    }}
            );
        }});

        MutableBigInt A2 = ws.getMutable(n);
        MutableBigInt B2 = ws.getMutable(n);
        MutableBigInt AB = ws.getMutable(n);

        garnerCRT(rA2_1, rA2_2, rA2_3, A2, n);
        garnerCRT(rB2_1, rB2_2, rB2_3, B2, n);
        garnerCRT(rAB_1, rAB_2, rAB_3, AB, n);

        outB.copy(A2);
        outB.add(B2);

        outA.copy(AB);
        outA.shiftLeftOne();  // OPTIMIZATION: Use specialized in-place shift
        outA.subtract(A2);

        ws.release(A2); ws.release(B2); ws.release(AB);
        ws.release(fa1); ws.release(fb1); ws.release(fa2); ws.release(fb2); ws.release(fa3); ws.release(fb3);
        ws.release(rA2_1); ws.release(rB2_1); ws.release(rAB_1);
        ws.release(rA2_2); ws.release(rB2_2); ws.release(rAB_2);
        ws.release(rA2_3); ws.release(rB2_3); ws.release(rAB_3);
    }

    static void garnerCRT(long[] r1, long[] r2, long[] r3, MutableBigInt result, int n) {
        long p1p2      = NTT.P1 * NTT.P2;
        long p1InvP2   = NTT.modInverse(NTT.P1, NTT.P2);
        long p12InvP3  = NTT.modInverse(p1p2 % NTT.P3, NTT.P3);
        long p1p2lo    = p1p2 & 0xFFFFFFFFL;
        long p1p2hi    = p1p2 >>> 32;

        if (result.mag.length < n)
            throw new IllegalStateException("garnerCRT: result buffer too small");

        // OPTIMIZATION: Targeted zeroing. Only clear up to n+2 to catch draining carries,
        // avoiding O(2^N) zero-fills when the array size is larger than necessary.
        int clearLimit = Math.min(result.mag.length, n + 2);
        Arrays.fill(result.mag, 0, clearLimit, 0);

        long carry = 0;
        for (int i = 0; i < n; i++) {
            long v1 = r1[i];
            long v2 = ((r2[i] - v1 % NTT.P2 + NTT.P2) * p1InvP2) % NTT.P2;
            long t  = (v1 + NTT.P1 * v2) % NTT.P3;
            long v3 = ((r3[i] - t + NTT.P3) % NTT.P3 * p12InvP3) % NTT.P3;

            long partial = v1 + NTT.P1 * v2;
            long acc0    = partial + carry;

            long lo     = p1p2lo * v3;
            long hi     = p1p2hi * v3;
            long lo32   = lo & 0xFFFFFFFFL;
            long loCarry= lo >>> 32;
            long mid    = hi + loCarry;

            long word0  = (acc0 & 0xFFFFFFFFL) + lo32;
            result.mag[i] = (int) word0;
            carry = (acc0 >>> 32) + (word0 >>> 32) + mid;
        }

        int ci = n;
        while (carry != 0) {
            if (ci >= result.mag.length)
                throw new IllegalStateException("garnerCRT: carry overflow");
            long w = (result.mag[ci] & 0xFFFFFFFFL) + carry;
            result.mag[ci++] = (int) w;
            carry = w >>> 32;
        }

        result.length = ci > n ? ci : n;
        while (result.length > 0 && result.mag[result.length - 1] == 0)
            result.length--;
    }

    static void fillLong(MutableBigInt src, long[] dst) {
        Arrays.fill(dst, 0L); // This remains safe because fillLong works on exact N sizes
        for (int i = 0; i < src.length; i++) dst[i] = src.mag[i] & 0xFFFFFFFFL;
    }

    public BigInteger toBigInteger() {
        if (length == 0) return BigInteger.ZERO;
        byte[] b = new byte[length * 4];
        for (int i = 0; i < length; i++) {
            int w = mag[length - 1 - i];
            b[i * 4]     = (byte)(w >> 24);
            b[i * 4 + 1] = (byte)(w >> 16);
            b[i * 4 + 2] = (byte)(w >>  8);
            b[i * 4 + 3] = (byte) w;
        }
        return new BigInteger(1, b);
    }
}