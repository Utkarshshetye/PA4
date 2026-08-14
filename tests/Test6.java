/* Chained Static Inlining with Complex Control Flow with multiple returns
 * 1. Inlining in Chain: main() -> ChainA.compute() -> ChainB.evaluate(), ChainC.factor()
 * 2. Complex Branching: Processor.calculate() contains 5 nested return paths
 */
public class Test6 {
    public static void main(String[] args) {
        long total = 0;
        long start = System.currentTimeMillis();

        for (int i = 0; i < 1000000; i++) {
            long x = i;
            long y = (i + 1) * 10;
            long z = 25;

            long res = Processor6.calculate(x, y, z); // Inline the calculate method with 5 return paths
            long res2 = ChainA6.compute(x, y); // Inline 3 methods compute, evaluate and factor into main by preserving
                                               // semantics
            total += res + res2;
        }

        long end = System.currentTimeMillis();
        System.out.println("Total: " + total);
        System.out.println("Time: " + (end - start) + " ms");
    }
}

class Processor6 {
    // Each return is replaced by an assignment in inlined version
    public static long calculate(long a, long b, long c) {
        if (a > b) {
            if (a > c) {
                return a + 100;
            } else {
                return c + 200;
            }
        } else {
            if (b > c) {
                if (b > 35) {
                    return b + 300;
                }
                return b + 400;
            }
            return c + 500;
        }
    }
}

class ChainA6 {
    public static long compute(long p, long q) {
        long mid = (p + q) / 2;
        return ChainB6.evaluate(mid) + ChainC6.factor(p);
    }
}

class ChainB6 {
    public static long evaluate(long val) {
        if (val % 2 == 0) {
            return val * 2;
        }
        return val * 3;
    }
}

class ChainC6 {
    public static long factor(long n) {
        if (n < 15)
            return 1;
        if (n < 25)
            return 2;
        if (n < 35)
            return 3;
        return 4;
    }
}
