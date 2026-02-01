# Fibonacci Number Calculator

This project provides a Java-based command-line tool for calculating very large Fibonacci numbers efficiently.

## Features

-   Calculates Fibonacci numbers for a given input `n`.
-   Uses a fast doubling algorithm for efficient calculation.
-   Implements Karatsuba multiplication for large number arithmetic.
-   Leverages a `ForkJoinPool` for parallel processing to speed up calculations on multi-core systems.
-   Automatically tunes the level of parallelism based on the number of available processor cores.

## Requirements

-   Java 21 or higher
-   Apache Maven

## Building the Project

To build the project and create a JAR file, run the following command from the project's root directory:

```bash
mvn clean package
```

This will create a `fibonacci.jar` file in the `target` directory.

## Usage

To run the Fibonacci number calculator, use the following command:

```bash
java -jar target/fibonacci.jar <n>
```

Replace `<n>` with the desired Fibonacci sequence number to calculate.

### Example

```bash
java -jar target/fibonacci.jar 1000000
```

The tool will output the following information:

-   Number of detected CPU cores
-   Maximum memory available to the JVM
-   Time taken to calculate the Fibonacci number
-   The bit length of the resulting number

## Algorithm

The calculator uses a matrix-based exponentiation method, specifically the fast doubling algorithm, to compute the nth Fibonacci number. This approach reduces the number of operations significantly compared to a naive recursive or iterative approach.

For multiplying very large numbers, the implementation uses the Karatsuba algorithm, a divide-and-conquer algorithm that is faster than the standard long-form multiplication. The multiplication is parallelized using a `ForkJoinPool` to take advantage of multi-core processors, further speeding up the computation.
