public class Test15 {
    public static void main(String[] args) {
        TestTC15 o;
        int res;
        o = new TestTC15();
        res = o.foo();
        System.out.println(res);
    }
}

class TestTC15 {
    public int foo() {
        int x;
        int y;
        boolean z;
        TestTC15 obj1;
        Test2TC15 obj2;
        int a;
        int b;
        int c;
        z = true;
        x = 7;
        y = 3;
        a = x + y;
        if(z) {
            obj1 = new Test2TC15();
            y = obj1.bar(x);
            obj2 = new Test2TC15();
            x = obj2.bar(y);
        } else {
            obj2 = new Test2TC15();
            x = obj2.bar(x);
        }
        return x;
    }
    public int bar(int p2) {
        int a;
        int b;
        a = 10;
        b = 20;
        return a;
    }
}
class Test2TC15  extends TestTC15 {
    public int bar(int p1) {
        int x;
        int y;
        boolean z;
        z = true;
        if(z) {
            x = 10;
        } else {
            x = 10;
        }
        return x;
    }
}