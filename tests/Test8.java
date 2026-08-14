/* Deep chain of virtual calls combined with complex branch logic inside each target with non static method calls
 */
public class Test8 {
    public static void main(String[] args) {
        Processor8 p = new ComplexProcessor8();
        Aggregator8 a = new AlphaAggregator8(p);

        long total = 0;
        long start = System.currentTimeMillis();

        for (int i = 0; i < 5000000; i++) {
            long val1 = i;
            long val2 = i * 2;
            long val3 = i % 100;

            // Virtual chain
            total += a.aggregate(val1, val2, val3);
        }

        long end = System.currentTimeMillis();
        System.out.println("Total: " + total);
        System.out.println("Time: " + (end - start) + " ms");
    }
}

interface Aggregator8 {
    long aggregate(long x, long y, long z);
}

class AlphaAggregator8 implements Aggregator8 {
    private Processor8 p;

    AlphaAggregator8(Processor8 p) {
        this.p = p;
    }

    public long aggregate(long x, long y, long z) {
        ComplexProcessor8 cp = new ComplexProcessor8();
        long way1 = cp.process(x, y);
        long way2 = cp.process(y, z);
        long way3 = cp.process(z, x); // Inline for all combinations
        long way4 = cp.process(x, z);
        long way5 = cp.process(y, x);
        long way6 = cp.process(z, y);

        return way1 + way2 + way3 + way4 + way5 + way6;
    }
}

interface Processor8 {
    long process(long a, long b);
}

class ComplexProcessor8 implements Processor8 {
    public long process(long a, long b) {
        if (a > b) {
            if (a > 999) {
                return a + (b % 10);
            } else if (a > 499) {
                return a - b;
            } else {
                return a * 3;
            }
        } else {
            if (b > 999) {
                if (b % 3 == 0) {
                    return b + a;
                }
                return b - a;
            }
            return b * 2;
        }
    }
}
