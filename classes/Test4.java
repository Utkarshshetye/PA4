public class Test4 {
  public static void main(String[] args) {
      TestTC04 o;
      int res;
      o = new TestTC04();
      res = o.foo();
      System.out.println(res);
  }
}

class TestTC04 {
  public int foo() {
      int a;
      int b;
      int c;
      int d;
      int e;
      boolean cond1;
      boolean cond2;
      
      a = 10;
      b = 5;
      e = 2;
      cond1 = true;
      cond2 = false;

      if (cond1) {
          c = a + b; // c = 10 + 5 = 15
          if (cond2) {
              d = c * e;
          } else {
              d = c - b; // d = 15 - 5 = 10
          }
      } else {
          d = 0;
      }

      return d;
  }
}
