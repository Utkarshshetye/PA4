javac -cp ".:soot-4.6.0-jar-with-dependencies.jar" -d classes MainClass.java MethodInlining1.java

java -cp "classes:soot-4.6.0-jar-with-dependencies.jar" MainClass Test