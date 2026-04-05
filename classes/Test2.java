public class Test2 {
    public static void main(String[] args) {
        TestTC02 o;
        int res;
        o = new TestTC02();
        res = o.foo();
        System.out.println(res);
    }
}
class TestTC02 {
    public int foo() {
        int a;
        int b;
        int c;
        int d;
        boolean z;
        TestTC02 o;
        a = 1;
        b = 2;
        o = new TestTC02();
        c = a + b;
        d = c + a;
        d = o.bar(a, b, c);
        return d;
    }
    
    public int bar(int a, int b, int c) {
        a = b + c;
        return a;
    }

    // 
    // Sample inlining scheme for foo. This is without constant propogation and folding.
    // 

    // public int foo() {
    //     int a;
    //     int b;
    //     int c;
    //     int d;
    //     boolean z;
    //     TestTC02 o;
    //     // Inlined bar
    //     int TestTC02_bar_a;
    //     int TestTC02_bar_b;
    //     int TestTC02_bar_c;
    //     a = 1;
    //     b = 2;
    //     o = new TestTC02();
    //     c = a + b;
    //     d = c + a;
    //     // d = o.bar(a, b, c); 
    //     TestTC02_bar_a = a;
    //     TestTC02_bar_b = b;
    //     TestTC02_bar_c = c;
    //     TestTC02_bar_a = TestTC02_bar_b + TestTC02_bar_c;
    //     d = TestTC02_bar_a;
    //     return d;
    // }

}
