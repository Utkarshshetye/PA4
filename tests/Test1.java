// Global Monomorphic
abstract class Calculate1 {
    abstract int calculate(int no, int no2);
}

class Add1 extends Calculate1 {

    @Override
    int calculate(int no1, int no2) {
        return no1 + no2;
    }
}

class Sub1 extends Calculate1 {

    @Override
    int calculate(int no1, int no2) {
        return no1 - no2;
    }

}

public class Test1 {
    public static void main(String[] args) {
        Calculate1 c = new Add1();
        int ans = 0;

        for (int i = 0; i < 500000; i++) {
            ans += c.calculate(i, i + 1); // Should inline i + i + 1
        }

        System.out.println(ans);
    }
}