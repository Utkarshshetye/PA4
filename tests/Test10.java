/* Deep Field Chains + Alias + Polymorphic + Global Monomorphic case + Deep Nesting*/

public class Test10 {
    public static void main(String[] args) {
        B10 o1 = new B10();
        o1.f2 = new B10();
        o1.f2.f2 = new B10();

        B10 o4 = o1.f2.f2;

        long total = 0;
        int state = 0;
        long start = System.currentTimeMillis();

        for (int i = 0; i < 10000000; i++) {

            B10 oShort = o1.f2;
            B10 oLong = o1.f2.f2;

            if (i % 100 == 0) {
                total += oLong.bar(i); // oLong can be B(), C() so no inlining here
            } else {
                total += oShort.bar(i); // OShort is always global monomorphic, so inlining possible here
            }

            B10 target;

            if (i % 2 == 0) {
                if (i % 4 == 0) {
                    target = new C10();
                } else {
                    target = new B10();
                }
            } else {
                if (i % 10 == 0) {
                    target = (B10) new C10();
                } else {
                    target = o4;
                }
            }

            // Polymorphic, no inlining
            total += target.bar(i);

            if (state == 0) {
                if (i % 10 == 0)
                    total += 50;
                else
                    total += 1;

                state = 1;
            } else if (state == 1) {
                total += (i > 500 ? 10 : 2);

                state = 2;
            } else {
                for (int j = 0; j < 3; j++)
                    total += 1;

                state = 0;
            }

            if (i == 2500000) {
                o1.f2.f2 = new C10(); // o1.f2.f2 can now point to B, C both
            }
        }

        long end = System.currentTimeMillis();
        System.out.println("Final Result: " + total);
        System.out.println("Time: " + (end - start) + " ms");
    }
}

class B10 {
    int f1;
    B10 f2;

    public long bar(int p1) {
        this.f1 = p1;
        return (long) p1 + 10;
    }
}

class C10 extends B10 {
    @Override
    public long bar(int p2) {
        return (long) p2 + 500;
    }
}
