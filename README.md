# misc

Java source generator for specialized multilinear interpolators.

## Build

```sh
./gradlew test
```

## Generate Interpolators

```sh
./gradlew :lib:classes
java -cp lib/build/classes/java/main io.github.sportne.misc.InterpolatorSourceGenerator generated-src generated.interpolation 5
```
