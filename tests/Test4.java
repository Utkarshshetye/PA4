// Per Context Monomorphic
interface Calculate4 {
    int execute(int x);
}

class Multiply4 implements Calculate4 {
    public int execute(int x) {
        return x * 10;
    }
}

class Add4 implements Calculate4 {
    public int execute(int x) {
        return x + 10;
    }
}

class Runner4 {
    int run(Calculate4 op, int val) {
        return op.execute(val);
    }
}

public class Test4 {
    public static void main(String[] args) {
        Multiply4 m = new Multiply4();
        Add4 a = new Add4();

        Runner4 r1 = new Runner4();
        Runner4 r2 = new Runner4();

        long result = 0;

        for (int i = 0; i < 1000000; i++) {
            result += r1.run(m, i); // Should inline Multiply
            result += r2.run(a, i); // Should inline Add
        }

        System.out.println(result);
    }
}