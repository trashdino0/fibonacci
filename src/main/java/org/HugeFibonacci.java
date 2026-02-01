package org;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class HugeFibonacci {

    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final ForkJoinPool POOL = new ForkJoinPool(CORES);
    private static final int THRESHOLD = 200_000;
    private static final int TO_STRING_THRESHOLD = 50_000;

    public static BigInteger fib(long n) {
        if (n < 0) throw new IllegalArgumentException("n must be non-negative");
        if (n == 0) return BigInteger.ZERO;
        if (n <= 2) return BigInteger.ONE;

        BigInteger a = BigInteger.ZERO;
        BigInteger b = BigInteger.ONE;

        // Fast Doubling: iterate from highest bit down to 0
        for (int i = 63 - Long.numberOfLeadingZeros(n); i >= 0; i--) {
            // F(2k) = a * (2b - a)
            BigInteger t1 = parallelMultiply(a, b.shiftLeft(1).subtract(a));
            // F(2k+1) = b^2 + a^2
            BigInteger t2 = parallelSquare(b).add(parallelSquare(a));

            a = t1;
            b = t2;

            if (((n >> i) & 1) != 0) {
                BigInteger nextA = b;
                b = a.add(b);
                a = nextA;
            }
        }
        return a;
    }

    // --- Fast Parallel Multiplication Logic ---

    private static BigInteger parallelMultiply(BigInteger x, BigInteger y) {
        if (x.bitLength() < THRESHOLD || y.bitLength() < THRESHOLD || CORES < 2) {
            return x.multiply(y);
        }
        return POOL.invoke(new KaratsubaTask(x, y));
    }

    private static BigInteger parallelSquare(BigInteger x) {
        if (x.bitLength() < THRESHOLD || CORES < 2) {
            return x.multiply(x);
        }
        return POOL.invoke(new KaratsubaTask(x, x));
    }

    static class KaratsubaTask extends RecursiveTask<BigInteger> {
        private final BigInteger x, y;

        KaratsubaTask(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        @Override
        protected BigInteger compute() {
            int n = Math.max(x.bitLength(), y.bitLength());
            if (n < THRESHOLD) return x.multiply(y);

            int half = (n / 32 / 2) * 32; // Align to word size
            if (half == 0) half = (n / 2);

            BigInteger xHigh = x.shiftRight(half);
            BigInteger xLow = x.subtract(xHigh.shiftLeft(half));
            BigInteger yHigh = y.shiftRight(half);
            BigInteger yLow = y.subtract(yHigh.shiftLeft(half));

            KaratsubaTask z2 = new KaratsubaTask(xHigh, yHigh);
            KaratsubaTask z0 = new KaratsubaTask(xLow, yLow);
            z2.fork();
            z0.fork();

            BigInteger z1 = new KaratsubaTask(xHigh.add(xLow), yHigh.add(yLow)).compute();
            BigInteger r2 = z2.join();
            BigInteger r0 = z0.join();

            return r2.shiftLeft(2 * half)
                    .add(z1.subtract(r2).subtract(r0).shiftLeft(half))
                    .add(r0);
        }
    }

    // --- Fast Parallel ToString Logic ---

    private static String fastToString(BigInteger n) {
        if (n.bitLength() < TO_STRING_THRESHOLD) return n.toString();
        return POOL.invoke(new ToStringTask(n));
    }

    static class ToStringTask extends RecursiveTask<String> {
        private final BigInteger n;

        ToStringTask(BigInteger n) { this.n = n; }

        @Override
        protected String compute() {
            int bitLen = n.bitLength();
            if (bitLen < TO_STRING_THRESHOLD) return n.toString();

            // Split point based on estimated digits
            int digits = (int) (bitLen * 0.30103) + 1;
            int half = digits / 2;
            BigInteger divisor = BigInteger.TEN.pow(half);

            BigInteger[] parts = n.divideAndRemainder(divisor);
            ToStringTask leftTask = new ToStringTask(parts[0]);
            leftTask.fork();

            String right = parts[1].toString();
            // Pad the right side with leading zeros
            String padding = "0".repeat(half - right.length());
            return leftTask.join() + padding + right;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java HugeFibonacci <n> [--save] [--out file] [--format dec|bin]");
            return;
        }

        long n = Long.parseLong(args[0]);
        boolean saveToFile = false;
        String outFile = null;
        String format = "dec";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--save" -> saveToFile = true;
                case "--out" -> {
                    if (i + 1 < args.length) {
                        outFile = args[++i];
                    } else {
                        System.err.println("Error: --out requires a value");
                        System.err.println("Usage: java HugeFibonacci <n> [--save] [--out <filename>] [--format <bin|dec|txt>]");
                        System.exit(1);
                    }
                }
                case "--format" -> {
                    if (i + 1 < args.length) {
                        format = args[++i].toLowerCase();
                        if (!format.equals("bin") && !format.equals("dec") && !format.equals("txt")) {
                            System.err.println("Error: invalid format '" + format + "'");
                            System.err.println("Supported formats: bin, dec, txt");
                            System.err.println("Usage: java HugeFibonacci <n> [--save] [--out <filename>] [--format <bin|dec|txt>]");
                            System.exit(1);
                        }
                    } else {
                        System.err.println("Error: --format requires a value");
                        System.err.println("Usage: java HugeFibonacci <n> [--save] [--out <filename>] [--format <bin|dec|txt>]");
                        System.exit(1);
                    }
                }
            }
        }

        if (saveToFile && outFile == null) {
            outFile = "fib_" + n + (format.equals("bin") ? ".bin" : ".txt");
        }

        System.out.println("Cores: " + CORES + " | Memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");

        long start = System.nanoTime();
        BigInteger result = fib(n);
        long end = System.nanoTime();

        System.out.printf("F(%d) calculated in %.4f s%n", n, (end - start) / 1e9);

        if (!saveToFile) return;

        if (format.equals("bin")) {
            Files.write(Path.of(outFile), result.toByteArray());
            System.out.println("Saved binary to " + outFile);
        } else {
            System.out.print("Converting to Decimal...");
            long cs = System.nanoTime();
            String s = fastToString(result);
            long ce = System.nanoTime();
            System.out.printf(" (Done in %.3f s)%n", (ce - cs) / 1e9);
            Files.writeString(Path.of(outFile), s);
            System.out.println("Saved text to " + outFile);
        }
    }
}