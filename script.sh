SOOT_JAR="./soot-4.6.0-jar-with-dependencies.jar"
SRC_DIR="./src"
SOOT_OUT="./sootOutput"
TESTS_DIR="./tests"
BENCHMARK_DIR="./tami-outs"

# Cleanup
echo "=== Cleaning .class files ==="
find src tests -name "*.class" -delete
echo "Done."
echo ""

# Compile Main
echo "=== Compiling src/Main.java ==="
javac -cp .:"$SOOT_JAR" "$SRC_DIR"/*.java ./*.java *.java
if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed!"
    exit 1
fi
echo "Compilation successful."
echo ""

# User Choices
echo "Select option to run tests:"
echo "1. Student testcases (from the tests directory)"
echo "2. Benchmarks (from tami-outs/)"
echo "3. Both"
read -p "Enter choice [1/2/3]: " CHOICE

case $CHOICE in
    1|3)
        read -p "Run student tests with inlining? [y/n]: " RUN_INLINE
        ;;
esac

run_student_tests() {
    local inlining=$1
    echo "=== Running Student Testcases ==="
    TESTCASES=()
    for f in "$TESTS_DIR"/Test[0-9]*.java; do
        [ -f "$f" ] || continue
        BASE=$(basename "$f" .java)
        TESTCASES+=("$BASE")
    done

    IFS=$'\n' TESTCASES=($(printf '%s\n' "${TESTCASES[@]}" | sort -t't' -k2 -n))
    unset IFS

    echo "Inlining of application classes methods only...."

    printf "%-10s %15s %15s %10s\n" "TestCase" "Before (ms)" "After (ms)" "Speedup"
    echo "────────────────────────────────────────────────────"
    mkdir -p student_out

    for TESTNAME in "${TESTCASES[@]}"; do
        TDIR="$TESTS_DIR/$TESTNAME"
        JAVA_SRC="$TESTS_DIR/$TESTNAME.java"
        mkdir -p "$TDIR"

        if [ -f "$JAVA_SRC" ]; then
            javac -d "$TDIR" "$JAVA_SRC" 2>/dev/null
        fi

        if [ ! -f "$TDIR/$TESTNAME.class" ]; then
            continue
        fi

        # --- Before
        T0=$(date +%s%N)
        java -Xint -cp "$TDIR" "$TESTNAME" > "student_out/${TESTNAME}_before.txt" 2>&1
        T1=$(date +%s%N)
        BEFORE_MS=$(( (T1 - T0) / 1000000 ))

        # --- Main execution
        INLINE_FLAG=""
        if [ "$inlining" == "n" ]; then
            INLINE_FLAG="-no_inline"
        fi

        java -cp "$SRC_DIR:.:$SOOT_JAR" Main "$TESTNAME" "$TESTNAME" $INLINE_FLAG -cha > /dev/null 2>&1

        # --- After
        if [ -f "$SOOT_OUT/$TESTNAME.class" ]; then
            T2=$(date +%s%N)
            java -Xint -cp "$SOOT_OUT:$TDIR" "$TESTNAME" > "student_out/${TESTNAME}_after.txt" 2>&1
            T3=$(date +%s%N)
            AFTER_MS=$(( (T3 - T2) / 1000000 ))

            if [ "$AFTER_MS" -gt 0 ] 2>/dev/null; then
                SPEEDUP=$(awk "BEGIN {printf \"%.2f\", $BEFORE_MS / $AFTER_MS}")
            else
                SPEEDUP="N/A"
            fi
            printf "%-10s %15s %15s %10s\n" "$TESTNAME" "${BEFORE_MS} ms" "${AFTER_MS} ms" "${SPEEDUP}x"
        else
            printf "%-10s %15s %15s %10s\n" "$TESTNAME" "${BEFORE_MS} ms" "no-output" "-"
        fi
    done
    echo "────────────────────────────────────────────────────"
}

# Function to run benchmarks
run_benchmarks() {
    local config=$1
    echo "=== Running Benchmarks ==="
    echo "Note: Running benchmarks takes approx 7 to 10 min per combination."
    echo "Output will be redirected to the 'logs/' directory."
    mkdir -p logs

    BENCHMARKS=(out-avrora out-batik out-fop out-luindex out-xalan)
    
    for BENCH in "${BENCHMARKS[@]}"; do
        if [ -d "$BENCHMARK_DIR/$BENCH" ]; then
            echo "===================================================="
            echo "Benchmark: $BENCH"
            echo "===================================================="
            
            # --- Combination 1: -lib -cha
            if [ "$config" == "1" ]; then
                LOG_FILE="logs/${BENCH}_lib_cha.log"
                echo "Running with -lib and -cha, Logging to $LOG_FILE"
                java -cp "$SRC_DIR:.:$SOOT_JAR" Main1 "$BENCHMARK_DIR/$BENCH/" -lib -cha -no_inline > "$LOG_FILE" 2>&1
            fi
            
            # --- Combination 2: -lib (no -cha)
            if [ "$config" == "2" ]; then
                LOG_FILE="logs/${BENCH}_lib.log"
                echo "Running with -lib only, Logging to $LOG_FILE"
                java -cp "$SRC_DIR:.:$SOOT_JAR" Main1 "$BENCHMARK_DIR/$BENCH/" -lib -no_inline > "$LOG_FILE" 2>&1
            fi
            
            # --- Combination 3: -cha (no -lib)
            if [ "$config" == "3" ]; then
                LOG_FILE="logs/${BENCH}_cha.log"
                echo "Running with -cha only, Logging to $LOG_FILE"
                java -cp "$SRC_DIR:.:$SOOT_JAR" Main1 "$BENCHMARK_DIR/$BENCH/" -cha -no_inline > "$LOG_FILE" 2>&1
            fi
            
            # --- Combination 4: neither
            if [ "$config" == "4" ]; then
                LOG_FILE="logs/${BENCH}_none.log"
                echo "Running with neither -lib nor -cha, Logging to $LOG_FILE"
                java -cp "$SRC_DIR:.:$SOOT_JAR" Main1 "$BENCHMARK_DIR/$BENCH/" -no_inline > "$LOG_FILE" 2>&1
            fi
        else
            echo "Warning: Benchmark directory $BENCHMARK_DIR/$BENCH/ not found."
        fi
        echo ""
    done
}

# Execution
case $CHOICE in
    1)
        run_student_tests "$RUN_INLINE"
        ;;
    2)
        echo ""
        echo "Select benchmark configuration:"
        echo "1. -lib -cha (Both)"
        echo "2. -lib only"
        echo "3. -cha only"
        echo "4. none (No -lib, No -cha)"
        read -p "Enter choice [1/2/3/4]: " BENCH_CONFIG
        run_benchmarks "$BENCH_CONFIG"
        ;;
    3)
        run_student_tests "$RUN_INLINE"
        echo ""
        echo "Select benchmark configuration for the next phase:"
        echo "1. -lib -cha (Both)"
        echo "2. -lib only"
        echo "3. -cha only"
        echo "4. none (No -lib, No -cha)"
        read -p "Enter choice [1/2/3/4]: " BENCH_CONFIG
        run_benchmarks "$BENCH_CONFIG"
        ;;
    *)
        echo "Invalid choice."
        ;;
esac