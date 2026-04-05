javac -cp ".:soot-4.6.0-jar-with-dependencies.jar" -d classes MainClass.java MethodInlining.java

java -cp "classes:soot-4.6.0-jar-with-dependencies.jar" MainClass ${1:-Test}