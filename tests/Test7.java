/* Test Case: Deep Static Call Chain
 * inline a long sequence of calls into a single block in main.
 */
public class Test7 {
    public static void main(String[] args) {
        long total = 0;
        for (int i = 0; i < 1000000; i++) {
            total += Level1_7.m1(i); // Should resolve to last level call
        }
        System.out.println("Result: " + total);
    }
}

class Level1_7 {
    public static int m1(int x) {
        return Level2_7.m2(x) + 1;
    }
}

class Level2_7 {
    public static int m2(int x) {
        return Level3_7.m3(x) + 1;
    }
}

class Level3_7 {
    public static int m3(int x) {
        return Level4_7.m4(x) + 1;
    }
}

class Level4_7 {
    public static int m4(int x) {
        return Level5_7.m5(x) + 1;
    }
}

class Level5_7 {
    public static int m5(int x) {
        return Level6_7.m6(x) + 1;
    }
}

class Level6_7 {
    public static int m6(int x) {
        return Level7_7.m7(x) + 1;
    }
}

class Level7_7 {
    public static int m7(int x) {
        return Level8_7.m8(x) + 1;
    }
}

class Level8_7 {
    public static int m8(int x) {
        return Level9_7.m9(x) + 1;
    }
}

class Level9_7 {
    public static int m9(int x) {
        return Level10_7.m10(x) + 1;
    }
}

class Level10_7 {
    public static int m10(int x) {
        return x * 2;
    }
}
