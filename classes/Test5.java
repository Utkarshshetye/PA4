class Test5 {
    public static void main(String[] args) {
        TestTC10 o;
        int res;
        o = new TestTC10();
        res = o.foo();
        System.out.println(res);
    }
}
class TestTC10 {
    public int foo() {
        int a;
        int b;
        int c;
        int d;
        int e;
        boolean z;
        a = 5;
        b = 6;
        z = true;
        d = 7;
        c = a + b;
        e = c + a;        
        return e;
    }
}
