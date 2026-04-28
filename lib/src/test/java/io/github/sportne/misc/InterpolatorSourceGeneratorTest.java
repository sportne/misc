package io.github.sportne.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

public class InterpolatorSourceGeneratorTest {

  private static final String GENERATED_PACKAGE = "generated.interpolation";
  private static final double EPSILON = 1e-9;

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void generatesAndCompilesThroughFiveDimensions() throws Exception {
    GeneratedProject project = generateAndCompile(5);

    assertEquals(70, countJavaSources(project.sourceRoot));
    assertGeneratedFile(project, "Interpolator.java");
    assertGeneratedFile(project, "InterpolationSupport.java");
    assertGeneratedFile(project, "Interpolators.java");
    assertGeneratedFile(project, "Interpolator5D.java");
    assertGeneratedFile(project, "Interpolator5D_UUUUU.java");

    Class<?> interpolators = project.loadClass("Interpolators");
    assertNotNull(interpolators.getMethod("flatten5D", double[][][][][].class));
    assertNotNull(interpolators.getMethod("create5D", double[][].class, double[][][][][].class));
  }

  @Test
  public void generatesAndCompilesVectorInterpolatorsThroughFiveDimensions() throws Exception {
    GeneratedProject project = generateAndCompile(5, true);

    assertEquals(138, countJavaSources(project.sourceRoot));
    assertGeneratedFile(project, "VectorInterpolator.java");
    assertGeneratedFile(project, "VectorInterpolator5D.java");
    assertGeneratedFile(project, "VectorInterpolator5D_UUUUU.java");

    Class<?> interpolators = project.loadClass("Interpolators");
    assertNotNull(interpolators.getMethod("createVector", double[][].class, double[][].class));
    assertNotNull(interpolators.getMethod("createVector5D", double[][].class, double[][].class));
  }

  @Test
  public void generatesCompilesAndRunsOptionalJUnitTests() throws Exception {
    Path sourceRoot = temporaryFolder.newFolder("generated-src-with-tests").toPath();
    Path testSourceRoot = temporaryFolder.newFolder("generated-test-src").toPath();
    Path classes = temporaryFolder.newFolder("generated-classes-with-tests").toPath();
    Path testClasses = temporaryFolder.newFolder("generated-test-classes").toPath();

    InterpolatorSourceGenerator.main(
        new String[] {
          sourceRoot.toString(), GENERATED_PACKAGE, "5", "--tests", testSourceRoot.toString()
        });

    List<Path> productionSources = javaSources(sourceRoot);
    List<Path> testSources = javaSources(testSourceRoot);
    assertEquals(70, productionSources.size());
    assertEquals(1, testSources.size());
    assertTrue(
        Files.isRegularFile(
            testSourceRoot
                .resolve(GENERATED_PACKAGE.replace('.', File.separatorChar))
                .resolve("InterpolatorsGeneratedTest.java")));

    compileSources(productionSources, classes, null);
    compileSources(
        testSources,
        testClasses,
        classes.toString() + File.pathSeparator + System.getProperty("java.class.path"));

    URL[] urls = new URL[] {testClasses.toUri().toURL(), classes.toUri().toURL()};
    try (URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
      Class<?> generatedTestClass =
          classLoader.loadClass(GENERATED_PACKAGE + ".InterpolatorsGeneratedTest");
      Result result = JUnitCore.runClasses(generatedTestClass);
      assertTrue(result.getFailures().toString(), result.wasSuccessful());
      assertEquals(2, result.getRunCount());
    }
  }

  @Test
  public void generatesCompilesAndRunsOptionalVectorJUnitTests() throws Exception {
    Path sourceRoot = temporaryFolder.newFolder("generated-vector-src-with-tests").toPath();
    Path testSourceRoot = temporaryFolder.newFolder("generated-vector-test-src").toPath();
    Path classes = temporaryFolder.newFolder("generated-vector-classes-with-tests").toPath();
    Path testClasses = temporaryFolder.newFolder("generated-vector-test-classes").toPath();

    InterpolatorSourceGenerator.main(
        new String[] {
          sourceRoot.toString(),
          GENERATED_PACKAGE,
          "5",
          "--vectors",
          "--tests",
          testSourceRoot.toString()
        });

    List<Path> productionSources = javaSources(sourceRoot);
    List<Path> testSources = javaSources(testSourceRoot);
    assertEquals(138, productionSources.size());
    assertEquals(1, testSources.size());

    compileSources(productionSources, classes, null);
    compileSources(
        testSources,
        testClasses,
        classes.toString() + File.pathSeparator + System.getProperty("java.class.path"));

    URL[] urls = new URL[] {testClasses.toUri().toURL(), classes.toUri().toURL()};
    try (URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
      Class<?> generatedTestClass =
          classLoader.loadClass(GENERATED_PACKAGE + ".InterpolatorsGeneratedTest");
      Result result = JUnitCore.runClasses(generatedTestClass);
      assertTrue(result.getFailures().toString(), result.wasSuccessful());
      assertEquals(3, result.getRunCount());
    }
  }

  @Test
  public void generatedSourcesContainRepresentativeJavadocs() throws Exception {
    GeneratedProject project = generateAndCompile(2);

    String interpolator = readGeneratedSource(project, "Interpolator.java");
    assertTrue(interpolator.contains("@return the dimensionality of this interpolator"));
    assertTrue(interpolator.contains("@param coordinates coordinates in axis order"));

    String interpolators = readGeneratedSource(project, "Interpolators.java");
    assertTrue(
        interpolators.contains("* Factory and flattening helpers for generated interpolators."));
    assertTrue(interpolators.contains("@param axes coordinate axes in axis order"));
    assertTrue(interpolators.contains("@return a typed 2D interpolator"));

    String implementation = readGeneratedSource(project, "Interpolator2D_UN.java");
    assertTrue(implementation.contains("/** Flattened grid values in sorted axis order. */"));
    assertTrue(implementation.contains("@param axes sorted axes in axis order"));
    assertTrue(implementation.contains("@param x coordinate for axis 0"));
    assertTrue(
        implementation.contains("@return the interpolated value, clamped to the grid bounds"));

    String support = readGeneratedSource(project, "InterpolationSupport.java");
    assertTrue(support.contains("@param dimensions expected number of axes"));
    assertTrue(support.contains("/** Sorted axis values. */"));
  }

  @Test
  public void generatedVectorSourcesContainRepresentativeJavadocs() throws Exception {
    GeneratedProject project = generateAndCompile(2, true);

    String vectorInterpolator = readGeneratedSource(project, "VectorInterpolator.java");
    assertTrue(vectorInterpolator.contains("@return the output value count"));
    assertTrue(vectorInterpolator.contains("@param out destination array"));

    String interpolators = readGeneratedSource(project, "Interpolators.java");
    assertTrue(interpolators.contains("Type-erased factory for vector-valued interpolators."));
    assertTrue(interpolators.contains("values[outputIndex][flatGridIndex]"));

    String implementation = readGeneratedSource(project, "VectorInterpolator2D_UN.java");
    assertTrue(implementation.contains("Generated 2D vector-valued interpolator"));
    assertTrue(implementation.contains("@return the output value count"));
    assertTrue(
        implementation.contains("@param out destination array with length equal to outputs()"));
  }

  @Test
  public void flattenHelpersUseAxisZeroFastestOrder() throws Exception {
    GeneratedProject project = generateAndCompile(5);
    Class<?> interpolators = project.loadClass("Interpolators");

    double[][] values2D = {
      {10.0, 11.0, 12.0},
      {20.0, 21.0, 22.0}
    };
    assertArrayEquals(
        new double[] {10.0, 11.0, 12.0, 20.0, 21.0, 22.0},
        flatten(interpolators, 2, values2D),
        0.0);

    double[][][] values3D = {
      {
        {0.0, 1.0},
        {10.0, 11.0}
      },
      {
        {100.0, 101.0},
        {110.0, 111.0}
      }
    };
    assertArrayEquals(
        new double[] {0.0, 1.0, 10.0, 11.0, 100.0, 101.0, 110.0, 111.0},
        flatten(interpolators, 3, values3D),
        0.0);

    int[] sizes = {2, 2, 2, 2, 2};
    double[] flat = new double[32];
    for (int i = 0; i < flat.length; i++) {
      flat[i] = i;
    }
    Object values5D = toNestedValues(flat, sizes);
    assertArrayEquals(flat, flatten(interpolators, 5, values5D), 0.0);
  }

  @Test
  public void flattenHelpersRejectRaggedAndNullNestedArrays() throws Exception {
    GeneratedProject project = generateAndCompile(5);
    Class<?> interpolators = project.loadClass("Interpolators");

    assertIllegalArgument(() -> flatten(interpolators, 2, new double[][] {{1.0}, {2.0, 3.0}}));
    assertIllegalArgument(() -> flatten(interpolators, 2, new double[][] {null}));
    assertIllegalArgument(() -> flatten(interpolators, 3, new double[][][] {{null}}));
    assertIllegalArgument(() -> flatten(interpolators, 5, null));
  }

  @Test
  public void generatedInterpolatorsMatchReferenceForAllMasksThroughFiveDimensions()
      throws Exception {
    GeneratedProject project = generateAndCompile(5);
    Class<?> interpolators = project.loadClass("Interpolators");

    // This is the primary interpolation-correctness test. It creates every generated
    // implementation class from 1D through 5D, then compares each result with the
    // independent referenceInterpolate(...) helper below.
    for (int dimension = 1; dimension <= 5; dimension++) {
      int classCount = 1 << dimension;
      for (int mask = 0; mask < classCount; mask++) {
        // Each mask bit controls whether that axis is uniform or non-uniform, so this
        // loop covers Interpolator2D_NU, Interpolator5D_UUNNU, and every sibling.
        double[][] axes = axesForMask(dimension, mask);
        double[] values = valuesForAxes(axes);
        Object interpolator = createFlat(interpolators, dimension, axes, values);

        assertEquals(implementationName(dimension, mask), interpolator.getClass().getSimpleName());

        // Exercise the common interpolation paths: inside a cell, exactly on grid
        // coordinates, and outside the grid where generated interpolators clamp.
        assertInterpolationMatchesReference(
            project, interpolator, axes, values, firstCellMidpoint(axes));
        assertInterpolationMatchesReference(
            project, interpolator, axes, values, exactMiddlePoint(axes));
        assertInterpolationMatchesReference(
            project, interpolator, axes, values, clampedPoint(axes));
      }
    }
  }

  @Test
  public void generatedVectorInterpolatorsMatchReferenceForAllMasksThroughFiveDimensions()
      throws Exception {
    GeneratedProject project = generateAndCompile(5, true);
    Class<?> interpolators = project.loadClass("Interpolators");

    for (int dimension = 1; dimension <= 5; dimension++) {
      int classCount = 1 << dimension;
      for (int mask = 0; mask < classCount; mask++) {
        double[][] axes = axesForMask(dimension, mask);
        double[][] values = vectorValuesForAxes(axes, 3);
        Object interpolator = createVector(interpolators, dimension, axes, values);

        assertEquals(
            vectorImplementationName(dimension, mask), interpolator.getClass().getSimpleName());
        assertVectorInterpolationMatchesReference(
            project, interpolator, axes, values, firstCellMidpoint(axes));
        assertVectorInterpolationMatchesReference(
            project, interpolator, axes, values, exactMiddlePoint(axes));
        assertVectorInterpolationMatchesReference(
            project, interpolator, axes, values, clampedPoint(axes));
      }
    }
  }

  @Test
  public void vectorInterpolatorsReorderEveryOutputRowForUnsortedAxes() throws Exception {
    GeneratedProject project = generateAndCompile(5, true);
    Class<?> interpolators = project.loadClass("Interpolators");

    double[][] unsortedAxes = {{2.0, 0.0, 1.0}, {4.0, -2.0, 1.0}};
    double[][] sortedAxes = {{0.0, 1.0, 2.0}, {-2.0, 1.0, 4.0}};
    double[][] valuesInOriginalOrder = vectorValuesForAxes(unsortedAxes, 3);
    double[][] valuesInSortedOrder = vectorValuesForAxes(sortedAxes, 3);
    Object interpolator = createVector(interpolators, 2, unsortedAxes, valuesInOriginalOrder);

    assertVectorInterpolationMatchesReference(
        project, interpolator, sortedAxes, valuesInSortedOrder, new double[] {0.5, 0.25});
  }

  @Test
  public void multidimensionalFactoriesMatchFlatFactories() throws Exception {
    GeneratedProject project = generateAndCompile(5);
    Class<?> interpolators = project.loadClass("Interpolators");

    double[][] axes =
        new double[][] {
          {-1.0, 0.0},
          {1.0, 4.0},
          {2.0, 5.0},
          {-2.0, 3.0},
          {0.5, 1.5}
        };
    int[] sizes = axisSizes(axes);
    double[] flatValues = valuesForAxes(axes);
    Object nestedValues = toNestedValues(flatValues, sizes);

    Object flatInterpolator = createFlat(interpolators, 5, axes, flatValues);
    Object nestedInterpolator = createNested(interpolators, 5, axes, nestedValues);

    double[] point = {-0.25, 2.5, 3.0, 1.0, 1.25};
    double flatResult = invokeTypedInterpolate(project, flatInterpolator, 5, point);
    double nestedResult = invokeTypedInterpolate(project, nestedInterpolator, 5, point);

    assertEquals(flatResult, nestedResult, EPSILON);

    Method typedCreate =
        interpolators.getMethod(
            "create", double[].class, double[].class, double[].class, double[][][].class);
    double[][][] values3D =
        (double[][][])
            toNestedValues(
                valuesForAxes(Arrays.copyOf(axes, 3)), axisSizes(Arrays.copyOf(axes, 3)));
    Object typedInterpolator =
        typedCreate.invoke(null, axes[0], axes[1], axes[2], (Object) values3D);
    assertInterpolationMatchesReference(
        project,
        typedInterpolator,
        Arrays.copyOf(axes, 3),
        valuesForAxes(Arrays.copyOf(axes, 3)),
        new double[] {-0.25, 2.5, 3.0});
  }

  @Test
  public void vectorValidationApisRejectInvalidInputs() throws Exception {
    GeneratedProject project = generateAndCompile(5, true);
    Class<?> interpolators = project.loadClass("Interpolators");

    double[][] axes = {{0.0, 1.0}, {0.0, 2.0}};
    double[][] values = vectorValuesForAxes(axes, 3);

    assertIllegalArgument(() -> createVector(interpolators, 2, axes, null));
    assertIllegalArgument(() -> createVector(interpolators, 2, axes, new double[0][]));
    assertIllegalArgument(() -> createVector(interpolators, 2, axes, new double[][] {null}));
    assertIllegalArgument(
        () -> createVector(interpolators, 2, axes, new double[][] {{1.0, 2.0, 3.0}}));
    assertIllegalArgument(
        () ->
            createVector(
                interpolators, 1, new double[][] {{0.0, 0.0}}, new double[][] {{1.0, 2.0}}));
    assertIllegalArgument(
        () ->
            createVector(
                interpolators,
                1,
                new double[][] {{0.0, Double.POSITIVE_INFINITY}},
                new double[][] {{1.0, 2.0}}));

    Object vectorInterpolator = createVector(interpolators, 2, axes, values);
    Method typedInterpolate =
        project
            .loadClass("VectorInterpolator2D")
            .getMethod("interpolate", double.class, double.class, double[].class);
    assertIllegalArgument(() -> typedInterpolate.invoke(vectorInterpolator, 0.5, 1.0, null));
    assertIllegalArgument(
        () -> typedInterpolate.invoke(vectorInterpolator, 0.5, 1.0, new double[2]));

    Method coordinateArrayInterpolate =
        project
            .loadClass("VectorInterpolator")
            .getMethod("interpolate", double[].class, double[].class);
    assertIllegalArgument(
        () ->
            coordinateArrayInterpolate.invoke(
                vectorInterpolator, new double[] {0.5}, new double[3]));
  }

  @Test
  public void validationAndConvenienceApisRemainIntact() throws Exception {
    GeneratedProject project = generateAndCompile(5);
    Class<?> interpolators = project.loadClass("Interpolators");

    assertIllegalArgument(
        () ->
            createFlat(
                interpolators,
                2,
                new double[][] {{0.0, 1.0}, {0.0, 1.0}},
                new double[] {1.0, 2.0, 3.0}));
    assertIllegalArgument(
        () -> createFlat(interpolators, 1, new double[][] {{0.0, 0.0}}, new double[] {1.0, 2.0}));
    assertIllegalArgument(
        () ->
            createFlat(
                interpolators, 1, new double[][] {{0.0, Double.NaN}}, new double[] {1.0, 2.0}));

    Method erasedCreate = interpolators.getMethod("create", double[][].class, double[].class);
    assertIllegalArgument(() -> erasedCreate.invoke(null, new double[6][], new double[64]));

    double[][] axes = {{0.0, 1.0}, {0.0, 2.0}};
    double[] values = {0.0, 1.0, 2.0, 3.0};
    Object interpolator = createFlat(interpolators, 2, axes, values);

    Method varargsInterpolate =
        project.loadClass("Interpolator").getMethod("interpolate", double[].class);
    assertIllegalArgument(
        () -> varargsInterpolate.invoke(interpolator, (Object) new double[] {0.5}));

    Method batchInterpolate =
        project
            .loadClass("Interpolator2D")
            .getMethod("interpolate", double[].class, double[].class, double[].class);
    double[] xs = {0.0, 0.5, 1.0};
    double[] ys = {0.0, 1.0, 2.0};
    double[] out = new double[3];
    batchInterpolate.invoke(interpolator, xs, ys, out);
    for (int i = 0; i < out.length; i++) {
      assertEquals(
          invokeTypedInterpolate(project, interpolator, 2, new double[] {xs[i], ys[i]}),
          out[i],
          EPSILON);
    }
    assertIllegalArgument(
        () ->
            batchInterpolate.invoke(
                interpolator, new double[] {0.0}, new double[] {0.0, 1.0}, new double[1]));
  }

  private GeneratedProject generateAndCompile(int maxDimension) throws Exception {
    return generateAndCompile(maxDimension, false);
  }

  private GeneratedProject generateAndCompile(int maxDimension, boolean vectors) throws Exception {
    Path sourceRoot = temporaryFolder.newFolder("generated-src").toPath();
    Path classes = temporaryFolder.newFolder("generated-classes").toPath();

    if (vectors) {
      InterpolatorSourceGenerator.main(
          new String[] {
            sourceRoot.toString(), GENERATED_PACKAGE, Integer.toString(maxDimension), "--vectors"
          });
    } else {
      InterpolatorSourceGenerator.main(
          new String[] {sourceRoot.toString(), GENERATED_PACKAGE, Integer.toString(maxDimension)});
    }

    List<Path> sources = javaSources(sourceRoot);
    compileSources(sources, classes, null);

    return new GeneratedProject(sourceRoot, classes);
  }

  private static void compileSources(List<Path> sources, Path classes, String classpath)
      throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull("Tests must run on a JDK, not a JRE", compiler);

    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
      Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
          fileManager.getJavaFileObjectsFromFiles(
              sources.stream().map(Path::toFile).collect(Collectors.toList()));
      List<String> options =
          classpath == null
              ? Arrays.asList("--release", "11", "-d", classes.toString())
              : Arrays.asList("--release", "11", "-classpath", classpath, "-d", classes.toString());
      Boolean success =
          compiler.getTask(null, fileManager, null, options, null, compilationUnits).call();
      assertEquals(Boolean.TRUE, success);
    }
  }

  private static long countJavaSources(Path sourceRoot) throws Exception {
    return javaSources(sourceRoot).size();
  }

  private static List<Path> javaSources(Path sourceRoot) throws Exception {
    try (Stream<Path> stream = Files.walk(sourceRoot)) {
      return stream.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  private static void assertGeneratedFile(GeneratedProject project, String fileName) {
    Path path =
        project
            .sourceRoot
            .resolve(GENERATED_PACKAGE.replace('.', File.separatorChar))
            .resolve(fileName);
    assertTrue("Expected generated file " + path, Files.isRegularFile(path));
  }

  private static String readGeneratedSource(GeneratedProject project, String fileName)
      throws Exception {
    Path path =
        project
            .sourceRoot
            .resolve(GENERATED_PACKAGE.replace('.', File.separatorChar))
            .resolve(fileName);
    return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
  }

  private static double[] flatten(Class<?> interpolators, int dimension, Object values)
      throws Exception {
    Method method =
        interpolators.getMethod("flatten" + dimension + "D", doubleArrayType(dimension));
    return (double[]) method.invoke(null, values);
  }

  private static Object createFlat(
      Class<?> interpolators, int dimension, double[][] axes, double[] values) throws Exception {
    Method method =
        interpolators.getMethod("create" + dimension + "D", double[][].class, double[].class);
    return method.invoke(null, axes, values);
  }

  private static Object createNested(
      Class<?> interpolators, int dimension, double[][] axes, Object values) throws Exception {
    Method method =
        interpolators.getMethod(
            "create" + dimension + "D", double[][].class, doubleArrayType(dimension));
    return method.invoke(null, axes, values);
  }

  private static Object createVector(
      Class<?> interpolators, int dimension, double[][] axes, double[][] values) throws Exception {
    Method method =
        interpolators.getMethod(
            "createVector" + dimension + "D", double[][].class, double[][].class);
    return method.invoke(null, axes, values);
  }

  private static double invokeTypedInterpolate(
      GeneratedProject project, Object interpolator, int dimension, double[] coordinates)
      throws Exception {
    Class<?> interfaceType = project.loadClass("Interpolator" + dimension + "D");
    Method method = interfaceType.getMethod("interpolate", repeatedDoubleTypes(dimension));
    Object[] arguments = new Object[dimension];
    for (int i = 0; i < dimension; i++) {
      arguments[i] = coordinates[i];
    }
    return (Double) method.invoke(interpolator, arguments);
  }

  private static double[] invokeTypedVectorInterpolate(
      GeneratedProject project, Object interpolator, int dimension, double[] coordinates)
      throws Exception {
    Class<?> interfaceType = project.loadClass("VectorInterpolator" + dimension + "D");
    Class<?>[] parameterTypes = Arrays.copyOf(repeatedDoubleTypes(dimension), dimension + 1);
    parameterTypes[dimension] = double[].class;
    Method method = interfaceType.getMethod("interpolate", parameterTypes);
    Method outputsMethod = project.loadClass("VectorInterpolator").getMethod("outputs");
    double[] out = new double[(Integer) outputsMethod.invoke(interpolator)];
    Object[] arguments = new Object[dimension + 1];
    for (int i = 0; i < dimension; i++) {
      arguments[i] = coordinates[i];
    }
    arguments[dimension] = out;
    method.invoke(interpolator, arguments);
    return out;
  }

  private static void assertInterpolationMatchesReference(
      GeneratedProject project,
      Object interpolator,
      double[][] axes,
      double[] values,
      double[] point)
      throws Exception {
    // All generated interpolation calls funnel through here during correctness tests.
    assertEquals(
        referenceInterpolate(axes, values, point),
        invokeTypedInterpolate(project, interpolator, axes.length, point),
        EPSILON);
  }

  private static void assertVectorInterpolationMatchesReference(
      GeneratedProject project,
      Object interpolator,
      double[][] axes,
      double[][] values,
      double[] point)
      throws Exception {
    assertArrayEquals(
        referenceVectorInterpolate(axes, values, point),
        invokeTypedVectorInterpolate(project, interpolator, axes.length, point),
        EPSILON);
  }

  private static double[][] axesForMask(int dimension, int mask) {
    double[][] axes = new double[dimension][];
    for (int axis = 0; axis < dimension; axis++) {
      if ((mask & (1 << axis)) != 0) {
        axes[axis] = new double[] {-1.0 - axis, 0.5 + axis, 2.0 + 3.0 * axis};
      } else {
        axes[axis] = new double[] {-2.0 - axis, -0.5 + axis, 3.25 + 2.0 * axis};
      }
    }
    return axes;
  }

  private static double[] valuesForAxes(double[][] axes) {
    int[] sizes = axisSizes(axes);
    int total = 1;
    for (int size : sizes) {
      total *= size;
    }

    double[] values = new double[total];
    int[] indices = new int[axes.length];
    for (int linear = 0; linear < total; linear++) {
      values[linear] = sampleValue(axes, indices);
      increment(indices, sizes);
    }
    return values;
  }

  private static double[][] vectorValuesForAxes(double[][] axes, int outputs) {
    double[] base = valuesForAxes(axes);
    double[][] values = new double[outputs][base.length];
    for (int output = 0; output < outputs; output++) {
      for (int linear = 0; linear < base.length; linear++) {
        values[output][linear] = (output + 1.0) * base[linear] + 17.0 * output;
      }
    }
    return values;
  }

  // Deterministic grid data for interpolation tests. The values vary by every axis,
  // so swapped strides, wrong flattening, or missing corner weights are visible.
  private static double sampleValue(double[][] axes, int[] indices) {
    double value = 7.0;
    for (int axis = 0; axis < axes.length; axis++) {
      double coordinate = axes[axis][indices[axis]];
      value += (axis + 1.25) * coordinate;
      value += 0.1 * (axis + 1) * coordinate * coordinate;
    }
    return value;
  }

  private static void increment(int[] indices, int[] sizes) {
    for (int axis = 0; axis < indices.length; axis++) {
      indices[axis]++;
      if (indices[axis] < sizes[axis]) {
        return;
      }
      indices[axis] = 0;
    }
  }

  private static double[] firstCellMidpoint(double[][] axes) {
    double[] point = new double[axes.length];
    for (int axis = 0; axis < axes.length; axis++) {
      point[axis] = 0.5 * (axes[axis][0] + axes[axis][1]);
    }
    return point;
  }

  private static double[] exactMiddlePoint(double[][] axes) {
    double[] point = new double[axes.length];
    for (int axis = 0; axis < axes.length; axis++) {
      point[axis] = axes[axis][1];
    }
    return point;
  }

  private static double[] clampedPoint(double[][] axes) {
    double[] point = new double[axes.length];
    for (int axis = 0; axis < axes.length; axis++) {
      point[axis] =
          axis % 2 == 0 ? axes[axis][0] - 100.0 : axes[axis][axes[axis].length - 1] + 100.0;
    }
    return point;
  }

  // Independent, loop-based multilinear interpolation oracle. It deliberately does
  // not reuse generated code, so a mismatch points at generated lookup/blending logic.
  private static double referenceInterpolate(double[][] axes, double[] values, double[] point) {
    int dimension = axes.length;
    int[] lower = new int[dimension];
    double[] t = new double[dimension];
    int[] strides = strides(axisSizes(axes));

    for (int axis = 0; axis < dimension; axis++) {
      double[] axisValues = axes[axis];
      if (point[axis] <= axisValues[0]) {
        lower[axis] = 0;
        t[axis] = 0.0;
      } else if (point[axis] >= axisValues[axisValues.length - 1]) {
        lower[axis] = axisValues.length - 2;
        t[axis] = 1.0;
      } else {
        lower[axis] = lowerIndex(axisValues, point[axis]);
        t[axis] =
            (point[axis] - axisValues[lower[axis]])
                / (axisValues[lower[axis] + 1] - axisValues[lower[axis]]);
      }
    }

    double result = 0.0;
    int corners = 1 << dimension;
    for (int mask = 0; mask < corners; mask++) {
      double weight = 1.0;
      int linear = 0;
      for (int axis = 0; axis < dimension; axis++) {
        boolean upper = (mask & (1 << axis)) != 0;
        weight *= upper ? t[axis] : 1.0 - t[axis];
        linear += (lower[axis] + (upper ? 1 : 0)) * strides[axis];
      }
      result += weight * values[linear];
    }
    return result;
  }

  private static double[] referenceVectorInterpolate(
      double[][] axes, double[][] values, double[] point) {
    double[] out = new double[values.length];
    for (int output = 0; output < values.length; output++) {
      out[output] = referenceInterpolate(axes, values[output], point);
    }
    return out;
  }

  private static int lowerIndex(double[] axis, double value) {
    int low = 0;
    int high = axis.length - 1;
    while (high - low > 1) {
      int mid = (low + high) >>> 1;
      if (axis[mid] <= value) {
        low = mid;
      } else {
        high = mid;
      }
    }
    return low;
  }

  private static int[] axisSizes(double[][] axes) {
    int[] sizes = new int[axes.length];
    for (int axis = 0; axis < axes.length; axis++) {
      sizes[axis] = axes[axis].length;
    }
    return sizes;
  }

  private static int[] strides(int[] sizes) {
    int[] strides = new int[sizes.length];
    strides[0] = 1;
    for (int axis = 1; axis < sizes.length; axis++) {
      strides[axis] = strides[axis - 1] * sizes[axis - 1];
    }
    return strides;
  }

  private static Object toNestedValues(double[] flatValues, int[] axisSizes) {
    int dimension = axisSizes.length;
    int[] javaSizes = new int[dimension];
    for (int i = 0; i < dimension; i++) {
      javaSizes[i] = axisSizes[dimension - 1 - i];
    }

    Object nested = Array.newInstance(double.class, javaSizes);
    int[] offset = {0};
    fillNestedValues(nested, 0, javaSizes.length, flatValues, offset);
    return nested;
  }

  private static void fillNestedValues(
      Object array, int depth, int dimensions, double[] flatValues, int[] offset) {
    int length = Array.getLength(array);
    if (depth == dimensions - 1) {
      for (int i = 0; i < length; i++) {
        Array.setDouble(array, i, flatValues[offset[0]++]);
      }
      return;
    }

    for (int i = 0; i < length; i++) {
      fillNestedValues(Array.get(array, i), depth + 1, dimensions, flatValues, offset);
    }
  }

  private static Class<?> doubleArrayType(int dimension) {
    return Array.newInstance(double.class, new int[dimension]).getClass();
  }

  private static Class<?>[] repeatedDoubleTypes(int dimension) {
    Class<?>[] types = new Class<?>[dimension];
    Arrays.fill(types, double.class);
    return types;
  }

  private static String implementationName(int dimension, int mask) {
    StringBuilder sb = new StringBuilder("Interpolator").append(dimension).append("D_");
    for (int axis = 0; axis < dimension; axis++) {
      sb.append((mask & (1 << axis)) == 0 ? 'N' : 'U');
    }
    return sb.toString();
  }

  private static String vectorImplementationName(int dimension, int mask) {
    return "Vector" + implementationName(dimension, mask);
  }

  private static void assertIllegalArgument(ThrowingRunnable runnable) throws Exception {
    try {
      runnable.run();
      fail("Expected IllegalArgumentException");
    } catch (InvocationTargetException e) {
      assertTrue(
          "Expected IllegalArgumentException but got " + e.getCause(),
          e.getCause() instanceof IllegalArgumentException);
    } catch (IllegalArgumentException expected) {
      // Expected path for direct calls.
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class GeneratedProject {
    private final Path sourceRoot;
    private final URLClassLoader classLoader;

    GeneratedProject(Path sourceRoot, Path classes) throws Exception {
      this.sourceRoot = sourceRoot;
      URL[] urls = new URL[] {classes.toUri().toURL()};
      this.classLoader = new URLClassLoader(urls);
    }

    Class<?> loadClass(String simpleName) throws Exception {
      return classLoader.loadClass(GENERATED_PACKAGE + "." + simpleName);
    }
  }
}
