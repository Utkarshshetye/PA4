// Case for Branch Call
abstract class Calculate2 {
    abstract double area(int side);
}

class Circle2 extends Calculate2 {

    @Override
    double area(int no1) {
        return 3.14 * no1 * no1;
    }
}

class Square2 extends Calculate2 {

    @Override
    double area(int no1) {
        return no1 * no1;
    }
}

public class Test2 {
    public static void main(String[] args) {
        Calculate2 op;
        int res = 0;
        int choise = 'c';

        if (choise == 'c') {
            op = new Circle2();
        } else {
            op = new Square2();
        }

        for (int i = 0; i < 500000; i++) {
            res += op.area(i); // Polymorphic call, Should not inline
        }

        System.out.println(res);
    }
}