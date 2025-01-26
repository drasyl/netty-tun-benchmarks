package org.drasyl.benchmarks;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.SystemPropertyUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Fork(AbstractBenchmark.DEFAULT_FORKS)
@Warmup(iterations = AbstractBenchmark.DEFAULT_WARMUP_ITERATIONS)
@Measurement(iterations = AbstractBenchmark.DEFAULT_MEASURE_ITERATIONS)
@State(Scope.Thread)
@SuppressWarnings("java:S5786")
public abstract class AbstractBenchmark {
    protected static final int DEFAULT_FORKS = 2;
    protected static final int DEFAULT_WARMUP_ITERATIONS = 10;
    protected static final int DEFAULT_MEASURE_ITERATIONS = 10;

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
    }

    protected static final String[] BASE_JVM_ARGS = {
            "-server",
            "-dsa",
            "-da",
            "-ea:org.drasyl...",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-Xms768m",
            "-Xmx768m",
            "-XX:MaxDirectMemorySize=768m",
//            "-XX:BiasedLockingStartupDelay=0",
            "-Dio.netty.leakDetection.level=disabled"
    };

    @Test
    // prevent parallel execution of benchmarks
    @ResourceLock("Benchmark")
    void run() throws Exception {
        final Collection<RunResult> runResults = new Runner(newOptionsBuilder().build()).run();

        assertFalse(runResults.isEmpty());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    protected ChainedOptionsBuilder newOptionsBuilder() throws IOException {
        final String className = getClass().getSimpleName();

        final ChainedOptionsBuilder runnerOptions = new OptionsBuilder()
                .include(className)
                .jvmArgs(jvmArgs());

        if (getForks() > 0) {
            runnerOptions.forks(getForks());
        }

        if (getWarmupIterations() > 0) {
            runnerOptions.warmupIterations(getWarmupIterations());
        }

        if (getMeasureIterations() > 0) {
            runnerOptions.measurementIterations(getMeasureIterations());
        }

        if (getTimeout() > 0) {
            runnerOptions.timeout(TimeValue.minutes(getTimeout()));
        }

        if (getReportDir() != null) {
            final String filePath = getReportDir() + className + ".json";
            final File file = new File(filePath);
            if (file.exists()) {
                file.delete();
            }
            else {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }

            runnerOptions.resultFormat(ResultFormatType.JSON);
            runnerOptions.result(filePath);
        }

        return runnerOptions;
    }

    protected String[] jvmArgs() {
        return BASE_JVM_ARGS;
    }

    protected int getForks() {
        return SystemPropertyUtil.getInt("forks", -1);
    }

    protected int getWarmupIterations() {
        return SystemPropertyUtil.getInt("warmups", -1);
    }

    protected int getMeasureIterations() {
        return SystemPropertyUtil.getInt("measurements", -1);
    }

    protected int getTimeout() {
        return SystemPropertyUtil.getInt("timeout", -1);
    }

    protected String getReportDir() {
        return SystemPropertyUtil.get("perfReportDir");
    }

    @SuppressWarnings("unused")
    public static void handleUnexpectedException(final Throwable t) {
        assertNull(t);
    }
}

