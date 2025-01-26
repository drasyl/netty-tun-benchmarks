## Run benchmarks

Each benchmark must have a class name ending with `Benchmark` and inherit
from [`AbstractBenchmark`](src/test/java/org/drasyl/AbstractBenchmark.java).

```shell
# run all benchmarks
./mvnw -DskipTests=false -Dforks=1 -Dwarmups=1 -Dmeasurements=1 test
# run specific benchmarks
./mvnw -DskipTests=false -Dforks=1 -Dwarmups=1 -Dmeasurements=1 -Dtest='org.drasyl.benchmarks.TunChannelWriteBenchmark.write,org.drasyl.benchmarks.TunChannelReadBenchmark.read' test
```

## Build benchmarks jar

```shell
./mvnw package
# run all benchmarks
sudo java -jar ./target/netty-tun-benchmarks.jar -rf json -f 1 -wi 1 -i 1
# run specific benchmarks
sudo java -jar ./target/netty-tun-benchmarks.jar 'org.drasyl.benchmarks.TunChannelWriteBenchmark.write' -rf json -f 1 -wi 1 -i 1
# run benchmark with custom parameters
sudo java -jar ./target/netty-tun-benchmarks.jar 'org.drasyl.benchmarks.TunChannelReadBenchmark.read' -rf json -f 1 -wi 1 -i 1 -p writeThreads=2
# run benchmarks with profiler
sudo java -jar ./target/netty-tun-benchmarks.jar 'org.drasyl.benchmarks.TunChannelWriteBenchmark.write' -rf json -f 1 -wi 1 -i 1 -prof async:output=flamegraph
```