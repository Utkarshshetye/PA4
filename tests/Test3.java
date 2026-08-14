// Case for Branch Call with Polymorphic
abstract class Calculate3 {
    abstract double area(int side);
}

class Circle3 extends Calculate3 {

    @Override
    double area(int no1) {
        return 3.14 * no1 * no1;
    }
}

class Square3 extends Calculate3 {

    @Override
    double area(int no1) {
        return no1 * no1;
    }
}

class Triangle3 extends Calculate3 {

    @Override
    double area(int no1) {
        return 0.5 * no1 * no1;
    }
}

public class Test3 {
    public static void main(String[] args) {
        Calculate3 op;
        int res = 0;

        Circle3 cObj = new Circle3();
        Square3 sObj = new Square3();

        int pick = args.length % 3;

        if (pick == 0) {
            op = new Circle3();
        } else if (pick == 1) {
            op = new Square3();
        } else {
            op = new Triangle3();
        }

        for (int i = 0; i < 500000; i++) {
            res += op.area(i); // Should not inline - 3 types in points-to set
            res += cObj.area(i); // Should inline - Circle.area()
            res += sObj.area(i); // Should inline - Square.area()
        }

        System.out.println(res);
    }
}