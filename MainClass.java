import soot.PackManager;
import soot.SceneTransformer;
import soot.Transform;
import soot.options.Options;

public class MainClass {
    public static void main(String[] args) {
        String classPath = "classes";

        Options.v().set_keep_line_number(true);

        // 2. Add your transformer to the "wjtp" pack
        SceneTransformer sceneTransformer = new MethodInlining1();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.MethodInlining", sceneTransformer));

        // 3. Prepare arguments
        String[] sootArgs = {
                "-cp", classPath,
                "-pp", // sets the class path for Soot
                "-w",
                "-app",
                "-allow-phantom-refs",
                "-no-bodies-for-excluded",
                "-exclude", "java.*",
                "-exclude", "javax.*",
                "-exclude", "sun.*",
                "-exclude", "com.sun.*",
                "-exclude", "jdk.*",
                "-f", "J",
                "-t", "1",
                "Test"
        };

        // 4. Just call main. It will parse args, load classes, and run the packs.
        soot.Main.main(sootArgs);
    }
}
