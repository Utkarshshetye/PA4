/* Loop Re-allocation
 * monomorphic inlining even a variable points to multiple objects 
 * from different allocation sites
 */
public class Test9 {
    public static void main(String[] args) {
        A9 o1 = new A9();
        int b = 70;
        int c = 40;

        long start = System.currentTimeMillis();

        for (int i = 0; i < 1000000; i++) {
            if (i % 2 == 0) {
                o1 = new A9(); // Reallocation inside loop
            }
            b = o1.bar(b); // Inline A's bar
        }
        System.out.println(b);

        A9 o2 = new A9();
        for (int i = 0; i < 1000000; i++) {
            if (i % 2 == 0) {
                o2 = new B9(); // Reallocation inside loop
            }
            b = o2.bar(b); // No inlining for B's bar
        }

        b = new B9().bar(b); // Inline B's bar
        long end = System.currentTimeMillis();
        System.out.println("Result: " + b);
        System.out.println("Time: " + (end - start) + " ms");
    }
}

class A9 {
    public int bar(int p1) {
        return p1 + 39;
    }
}

class B9 extends A9 {
    @Override
    public int bar(int p2) {
        return p2 + 24;
    }
}
