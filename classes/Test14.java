public class Test14 {
    public static void main(String[] args) {
        TestTC14 o;
        int res;
        o = new TestTC14();
        res = o.foo();
        System.out.println(res);
    }
}

class TestTC14 {
    public int foo() {
        int x;
        int y;
        int z;
        TestTC14 obj1;
        Test2TC14 obj2;
        int a;
        int b;
        int c;
        x = 7;
        y = 3;
        a = x + y;
        obj1 = new Test2TC14();
        obj2 = new Test2TC14();
        c = obj2.bar(a);
        z = obj1.bar(c);
        return z;
    }
    public int bar(int p2) {
        int a;
        int b;
        a = 10;
        b = 20;
        return a;
    }
}
class Test2TC14  extends TestTC14 {
    public int bar(int p1) {
        int x;
        int y;
        int z;
        int a;
        x = 7;
        y = 3;
        a = x + y;
        z = a + p1;
        return p1;
    }
}