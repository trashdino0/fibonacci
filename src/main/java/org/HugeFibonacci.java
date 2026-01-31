package org;

import java.math.BigInteger;

public class HugeFibonacci {

    public static BigInteger fib(long n) {
        if (n == 0) return BigInteger.ZERO;
        if (n <= 2) return BigInteger.ONE;

        BigInteger a = BigInteger.ZERO;
        BigInteger b = BigInteger.ONE;

        int bitLength = 63 - Long.numberOfLeadingZeros(n);

        for (int i = bitLength; i >= 0; i--) {
            BigInteger aSquared = a.multiply(a);
            BigInteger bSquared = b.multiply(b);

            BigInteger f2k = a.multiply(b.shiftLeft(1).subtract(a));
            BigInteger f2kplus1 = aSquared.add(bSquared);

            a = f2k;
            b = f2kplus1;

            if (((n >> i) & 1) != 0) {
                BigInteger nextA = b;
                b = a.add(b);
                a = nextA;
            }
        }
        return a;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -jar fibonacci.jar <n>");
            return;
        }

        long n = Long.parseLong(args[0]);
        long start = System.nanoTime();
        BigInteger result = fib(n);
        long end = System.nanoTime();

        System.out.printf("F(%d) calculated in %.4f seconds%n", n, (end - start) / 1e9);
        System.out.println("Result length: " + result.bitLength() + " bits");
    }
}