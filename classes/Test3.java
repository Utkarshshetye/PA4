public class Test3 {
    public static void main(String[] args) {
        TestTC03 o;
        int res;
        o = new TestTC03();
        res = o.foo();
        System.out.println(res);
    }
}
class TestTC03 {
    public int foo() {
        int a;
        int b;
        int c;
        int d;
        boolean z;
        a = 5;
        b = 6;
        z = false;
        d = 7;
        if(z) {
          c = a + b; // Should propagate c = 11;
          d = c + a; // Should propagate d = 16;
        } else {
          c = b - a; // Should propagate c = 1;
          d = a - c; // Should propagate d = 4;
        }
        //  d = 16;
        return d; // Should propagate d = 16;
    }
}
