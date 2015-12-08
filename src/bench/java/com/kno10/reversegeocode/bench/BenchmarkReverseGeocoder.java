package com.kno10.reversegeocode.bench;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import com.kno10.reversegeocode.query.ReverseGeocoder;

/**
 * JMH Microbenchmarks
 *
 * @author Erich Schubert
 */
@BenchmarkMode({ Mode.AverageTime, Mode.Throughput })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Thread)
public class BenchmarkReverseGeocoder {
  @Param({ "data/osm-20150928-0.0005-2.bin", "data/osm-20150928-0.005-2.bin", //
  "data/osm-20150928-0.05-2.bin", "data/osm-20150928-0.5-2.bin" })
  public String filename;

  ReverseGeocoder rgc;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    if(filename == null) {
      System.err.println("JMH bug? Param not initialized.");
      System.exit(1);
    }
    rgc = new ReverseGeocoder(filename);
  }

  @TearDown
  public void teardown() throws IOException {
    rgc.close();
  }

  /**
   * Benchmark random number generation itself.
   *
   * @return Sum
   */
  @Benchmark
  public int randomNumberGeneration() {
    float f1 = (float) (Math.random() * 360. - 180.);
    float f2 = (float) (Math.random() * 120. - 60.);
    return (int) (f1 + f2);
  }

  @Benchmark
  public int lookup() {
    float f1 = (float) (Math.random() * 360. - 180.);
    float f2 = (float) (Math.random() * 120. - 60.);
    return rgc.lookup(f1, f2).length;
  }

  @Benchmark
  public int lookupUncached() {
    float f1 = (float) (Math.random() * 360. - 180.);
    float f2 = (float) (Math.random() * 120. - 60.);
    return rgc.lookupEntryUncached(rgc.lookupUncached(f1, f2)).length;
  }

  @Benchmark
  public int openQueryClose() throws IOException {
    ReverseGeocoder lrgc = new ReverseGeocoder(filename);
    float f1 = (float) (Math.random() * 360. - 180.);
    float f2 = (float) (Math.random() * 120. - 60.);
    int ret = lrgc.lookup(f1, f2).length;
    lrgc.close();
    return ret;
  }

  /**
   * Static main method.
   *
   * @param args
   * @throws RunnerException
   */
  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder() //
    .include(BenchmarkReverseGeocoder.class.getSimpleName()) //
    .build();

    new Runner(opt).run();
  }
}
