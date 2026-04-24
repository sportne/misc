# misc

Java source generator for specialized multilinear interpolators.

The generator emits Java 11 source for clamped, double-precision interpolators with dimensions from
`1` through the requested maximum dimension. For each dimension it generates every uniform and
non-uniform axis combination, plus typed interfaces and a factory class.

Generated interpolators use axis0-fastest flattened data:

```text
index = i0 + n0 * (i1 + n1 * (i2 + ...))
```

The generated factory also includes `flattenND(...)` helpers and multidimensional factory overloads
for callers that start with natural Java arrays such as `double[][]` or `double[][][]`.

## Build And Verify

```sh
./gradlew check
```

`check` runs the full project verification lifecycle:

- JUnit 4 tests
- Spotless formatting checks
- Checkstyle checks
- SpotBugs for main and test classes
- JaCoCo coverage verification
- SpotBugs against generated 5D interpolator sources

The generated-code SpotBugs path is intentional: `generateJavaSourcesForSpotbugs` emits generated
interpolator sources under `lib/build/generated/spotbugs/java-sources`, then
`compileGeneratedJavaForSpotbugs` compiles them and `spotbugsGeneratedJava` analyzes the generated
classes.

## Generate Sources

```sh
./gradlew :lib:classes
java -cp lib/build/classes/java/main io.github.sportne.misc.InterpolatorSourceGenerator generated-src generated.interpolation 5
```

Arguments:

1. Output source root, default `generated-src`
2. Generated package name, default `generated.interpolation`
3. Maximum dimension, default `4`, maximum `10`

The command above generates dimensions `1..5`.

## Project Layout

- `lib/src/main/java/io/github/sportne/misc/InterpolatorSourceGenerator.java` - source generator
- `lib/src/test/java/io/github/sportne/misc/InterpolatorSourceGeneratorTest.java` - generation,
  compilation, flattening, validation, and interpolation correctness tests
- `config/checkstyle/checkstyle.xml` - Checkstyle complexity and method-size rules
- `lib/build.gradle` - Java build, tests, coverage, static analysis, and generated-code SpotBugs
