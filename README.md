To run the user test or benchmark program use the following command:

bash script.sh

Steps:
1. Run the script.sh file.

2. It will ask you to select option to run tests, to run user test type 1, to run benchmark type 2 and to run both type 3.(Press Enter)
    1. Student testcases (from the tests directory)
    2. Benchmarks (from tami-outs/)
    3. Both


If you type 1:
    A. You need to type y/n to enable or disable method inlining (library excluded for students tests) (Note: Benchmarks can be run with or without library)
    
    B. If y typed, then it will run all 10 tests cases and show statistics (Before vs After Inlining) and store the output in the student-out directory.

    C. If n, then it will skip inlining run.

If you type 2:

    A. It will ask you to select benchmark configuration (transformation is disabled for benchmark programs):
        1. -lib -cha (Both)
        2. -lib only
        3. -cha only
        4. none (No -lib, No -cha)

    (-lib will include library code for analysis, -cha will be fallback option to resolve no point to case)

    B. Select the option and press enter.

    C. It will run the tests and print the results (Execution time ranges from 3 min to 15 min depending on the configuration chosen)


To individually run the program use the following command:

Compilation:

javac -cp .:soot-4.6.0-jar-with-dependencies.jar src/*.java && javac -d tests/Test1 tests/Test1.java

Running a user Test:

java -cp .:src:soot-4.6.0-jar-with-dependencies.jar Main Test1 Test1

To provide flags(any order or exclude any)= -lib -cha -no_inline

Running benchmark program:

java -cp .:src:soot-4.6.0-jar-with-dependencies.jar Main1 tami-outs/tami-outs/out-xalan/ -no_inline -lib -cha

To provide flags(any order or exclude any)= -lib -cha