// If the same enclosing method has PER-CTX sites for multiple targets 
// it creates 2 different clone and perform inlining
interface Op5 {
    int exec(int x);
}

class Add5 implements Op5 {
    public int exec(int x) {
        return x + 1;
    }
}

class Wrapper5 {
    public int execute(Op5 op, int val) {
        return op.exec(val);
    }
}

public class Test5 {
    public static void main(String[] args) {
        Wrapper5 w = new Wrapper5();
        Add5 a = new Add5();

        // Warm-up phase
        for (int i = 0; i < 100000; i++) {
            w.execute(a, i);
        }

        long total = 0;

        for (int i = 0; i < 5000000; i++) {
            total += w.execute(a, i); // target same, both should inline separately
            total += w.execute(a, i);
        }

        System.out.println("Result: " + total);
    }
}
