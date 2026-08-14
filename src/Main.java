import java.util.Arrays;

import soot.*;
import soot.options.Options;

public class Main {
        static boolean analyzeLibraries = false;
        static boolean useCHAForUnknown = false;
        static boolean useInline = true;

        public static void main(String[] args) {

                if (args.length < 2) {
                        System.out.println(
                                        "Usage: java PA4 <testcase_dir> <MainClass>");
                        System.out.println(
                                        "Example: java PA4 Test1 Test1");
                        return;
                }

                String classPath = "./tests/" + args[0];
                String mainClass = args[1];
                // Options.v().set_output_format(Options.output_format_class);
                Options.v().set_keep_line_number(true);

                if (args.length > 2) {
                        if (args[2].equals("--analyze-libraries") || args[2].equals("-lib")) {
                                analyzeLibraries = true;
                        } else if (args[2].equals("-cha")) {
                                useCHAForUnknown = true;
                        }

                        if (args[2].equals("-no_inline")) {
                                useInline = false;
                        }
                }

                if (args.length > 3) {
                        if (args[3].equals("-cha")) {
                                useCHAForUnknown = true;
                        } else if (args[3].equals("--analyze-libraries") || args[3].equals("-lib")) {
                                analyzeLibraries = true;
                        }

                        else if (args[3].equals("-no_inline")) {
                                useInline = false;
                        }
                }

                if (args.length > 4) {
                        if (args[4].equals("-no_inline")) {
                                useInline = false;
                        } else if (args[4].equals("--analyze-libraries") || args[2].equals("-lib")) {
                                analyzeLibraries = true;
                        } else if (args[4].equals("-cha")) {
                                useCHAForUnknown = true;
                        }
                }

                ObjSensAnalysisTransformer analysisTransformer = new ObjSensAnalysisTransformer();
                PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", analysisTransformer));

                String[] sootArgs = {
                                "-cp", classPath,
                                "-pp",
                                "-w",
                                "-app",
                                "-allow-phantom-refs",
                                "-no-bodies-for-excluded",
                                // "-exclude", "java.",
                                // "-exclude", "javax.",
                                // "-exclude", "sun.",
                                // "-exclude", "com.sun.",
                                // "-exclude", "jdk.",
                                // "-exclude", "org.",
                                // "-exclude", "junit.",
                                // "-include", mainClass,
                                "-f", "c",
                                // "-d", "./sootOutput",
                                "-main-class", mainClass,
                                "-process-dir", classPath
                };

                Options.v().set_exclude(Arrays.asList(
                                "sun.*"));

                soot.Main.main(sootArgs);
        }
}