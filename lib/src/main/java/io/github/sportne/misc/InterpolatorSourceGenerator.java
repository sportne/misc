package io.github.sportne.misc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates fast, specialized, clamped, multilinear interpolators for Java 11.
 *
 * <p>Output characteristics: - Dimensions 1 through maxDimension, default 4. - double precision
 * only. - x/axis0 fastest flattening order: index = i0 + n0 * (i1 + n1 * (i2 + ...)) - Construction
 * sorts axes and reorders values once. - The hot interpolate(...) paths are generated, unrolled,
 * allocation-free, and loop-free. - For each dimension, every uniform/non-uniform axis combination
 * is generated.
 *
 * <p>Usage: javac InterpolatorSourceGenerator.java java InterpolatorSourceGenerator src/main/java
 * com.example.interpolation 4
 *
 * <p>Arguments: 0: output source root, for example src/main/java. Default: generated-src 1: package
 * name. Default: generated.interpolation 2: max dimension. Default: 4
 */
public final class InterpolatorSourceGenerator {

  private static final String[] COORD_NAMES = {"x", "y", "z", "w", "u", "v", "q", "r", "s", "t"};

  private final String packageName;
  private final int maxDimension;
  private final Path packageDirectory;

  public static void main(String[] args) throws IOException {
    Path sourceRoot = args.length >= 1 ? Paths.get(args[0]) : Paths.get("generated-src");
    String packageName = args.length >= 2 ? args[1] : "generated.interpolation";
    int maxDimension = args.length >= 3 ? Integer.parseInt(args[2]) : 4;

    if (maxDimension < 1) {
      throw new IllegalArgumentException("maxDimension must be at least 1");
    }
    if (maxDimension > COORD_NAMES.length) {
      throw new IllegalArgumentException("maxDimension may not exceed " + COORD_NAMES.length);
    }

    InterpolatorSourceGenerator generator =
        new InterpolatorSourceGenerator(sourceRoot, packageName, maxDimension);
    generator.generateAll();
  }

  private InterpolatorSourceGenerator(Path sourceRoot, String packageName, int maxDimension) {
    this.packageName = packageName;
    this.maxDimension = maxDimension;
    this.packageDirectory = sourceRoot.resolve(packageName.replace('.', '/'));
  }

  private void generateAll() throws IOException {
    Files.createDirectories(packageDirectory);

    writeSource("Interpolator", generateBaseInterpolator());
    writeSource("InterpolationSupport", generateSupport());
    writeSource("Interpolators", generateFactory());

    for (int dimension = 1; dimension <= maxDimension; dimension++) {
      writeSource(interfaceName(dimension), generateDimensionInterface(dimension));

      int classCount = 1 << dimension;
      for (int mask = 0; mask < classCount; mask++) {
        String className = implementationName(dimension, mask);
        writeSource(className, generateImplementation(dimension, mask));
      }
    }
  }

  private void writeSource(String className, String source) throws IOException {
    Path file = packageDirectory.resolve(className + ".java");
    Files.write(file, source.getBytes(StandardCharsets.UTF_8));
    System.out.println("Wrote " + file);
  }

  private String packageLine() {
    return "package " + packageName + ";\n\n";
  }

  private String generateBaseInterpolator() {
    StringBuilder sb = new StringBuilder();
    sb.append(packageLine());
    sb.append("/**\n");
    sb.append(" * Generic view over a generated interpolator.\n");
    sb.append(" *\n");
    sb.append(" * Prefer Interpolator1D/2D/3D/4D typed methods in hot code.\n");
    sb.append(" * The varargs method is convenient but less optimal for tight loops.\n");
    sb.append(" */\n");
    sb.append("public interface Interpolator {\n");
    sb.append("    int dimensions();\n\n");
    sb.append("    double interpolate(double... coordinates);\n");
    sb.append("}\n");
    return sb.toString();
  }

  private String generateDimensionInterface(int dimension) {
    StringBuilder sb = new StringBuilder();
    sb.append(packageLine());
    sb.append("public interface ")
        .append(interfaceName(dimension))
        .append(" extends Interpolator {\n");
    sb.append("    ").append(typedMethodSignature(dimension)).append(";\n\n");

    sb.append("    @Override\n");
    sb.append("    default int dimensions() {\n");
    sb.append("        return ").append(dimension).append(";\n");
    sb.append("    }\n\n");

    sb.append("    @Override\n");
    sb.append("    default double interpolate(double... coordinates) {\n");
    sb.append("        if (coordinates == null || coordinates.length != ")
        .append(dimension)
        .append(") {\n");
    sb.append("            throw new IllegalArgumentException(\"Expected ")
        .append(dimension)
        .append(" coordinates\");\n");
    sb.append("        }\n");
    sb.append("        return interpolate(");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append("coordinates[").append(axis).append("]");
    }
    sb.append(");\n");
    sb.append("    }\n\n");

    appendBatchMethod(sb, dimension);

    sb.append("}\n");
    return sb.toString();
  }

  private void appendBatchMethod(StringBuilder sb, int dimension) {
    sb.append("    default void interpolate(\n");
    for (int axis = 0; axis < dimension; axis++) {
      sb.append("        double[] ").append(arrayName(axis)).append(",\n");
    }
    sb.append("        double[] out\n");
    sb.append("    ) {\n");

    sb.append("        if (");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(" || ");
      }
      sb.append(arrayName(axis)).append(" == null");
    }
    sb.append(" || out == null) {\n");
    sb.append(
        "            throw new IllegalArgumentException(\"Input and output arrays must not be null\");\n");
    sb.append("        }\n\n");

    sb.append("        int count = ").append(arrayName(0)).append(".length;\n\n");
    sb.append("        if (");
    boolean wroteLengthCheck = false;
    for (int axis = 1; axis < dimension; axis++) {
      if (wroteLengthCheck) {
        sb.append(" || ");
      }
      sb.append(arrayName(axis)).append(".length != count");
      wroteLengthCheck = true;
    }
    if (wroteLengthCheck) {
      sb.append(" || ");
    }
    sb.append("out.length != count) {\n");
    sb.append(
        "            throw new IllegalArgumentException(\"All input arrays and out must have the same length\");\n");
    sb.append("        }\n\n");

    sb.append("        for (int i = 0; i < count; i++) {\n");
    sb.append("            out[i] = interpolate(");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append(arrayName(axis)).append("[i]");
    }
    sb.append(");\n");
    sb.append("        }\n");
    sb.append("    }\n");
  }

  private String generateFactory() {
    StringBuilder sb = new StringBuilder();
    sb.append(packageLine());
    sb.append("public final class Interpolators {\n\n");
    sb.append("    private Interpolators() {}\n\n");

    sb.append("    /**\n");
    sb.append("     * Type-erased factory for callers that only know the dimension at runtime.\n");
    sb.append("     * For hot code, prefer the dimension-specific overloads below.\n");
    sb.append("     */\n");
    sb.append("    public static Interpolator create(double[][] axes, double[] values) {\n");
    sb.append("        if (axes == null) {\n");
    sb.append("            throw new IllegalArgumentException(\"axes must not be null\");\n");
    sb.append("        }\n");
    sb.append("        switch (axes.length) {\n");
    for (int dimension = 1; dimension <= maxDimension; dimension++) {
      sb.append("            case ").append(dimension).append(":\n");
      sb.append("                return create").append(dimension).append("D(axes, values);\n");
    }
    sb.append("            default:\n");
    sb.append(
        "                throw new IllegalArgumentException(\"Unsupported dimension: \" + axes.length);\n");
    sb.append("        }\n");
    sb.append("    }\n\n");

    for (int dimension = 1; dimension <= maxDimension; dimension++) {
      appendFlattenHelper(sb, dimension);
      appendTypedFactoryOverload(sb, dimension);
      if (dimension > 1) {
        appendTypedMultidimensionalFactoryOverload(sb, dimension);
      }
      appendArrayFactory(sb, dimension);
      if (dimension > 1) {
        appendArrayMultidimensionalFactory(sb, dimension);
      }
    }

    appendCheckedGridSize(sb);

    sb.append("}\n");
    return sb.toString();
  }

  private void appendTypedFactoryOverload(StringBuilder sb, int dimension) {
    sb.append("    public static ").append(interfaceName(dimension)).append(" create(");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append("double[] ").append(COORD_NAMES[axis]);
    }
    sb.append(", double[] values) {\n");
    sb.append("        return create").append(dimension).append("D(new double[][] { ");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append(COORD_NAMES[axis]);
    }
    sb.append(" }, values);\n");
    sb.append("    }\n\n");
  }

  private void appendTypedMultidimensionalFactoryOverload(StringBuilder sb, int dimension) {
    sb.append("    public static ").append(interfaceName(dimension)).append(" create(");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append("double[] ").append(COORD_NAMES[axis]);
    }
    sb.append(", ").append(multidimensionalValuesType(dimension)).append(" values) {\n");
    sb.append("        return create").append(dimension).append("D(new double[][] { ");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append(COORD_NAMES[axis]);
    }
    sb.append(" }, flatten").append(dimension).append("D(values));\n");
    sb.append("    }\n\n");
  }

  private void appendArrayFactory(StringBuilder sb, int dimension) {
    sb.append("    public static ")
        .append(interfaceName(dimension))
        .append(" create")
        .append(dimension)
        .append("D(double[][] axes, double[] values) {\n");
    sb.append("        InterpolationSupport.PreparedGrid grid = InterpolationSupport.prepare(")
        .append(dimension)
        .append(", axes, values);\n");
    sb.append("        switch (grid.uniformMask) {\n");
    int classCount = 1 << dimension;
    for (int mask = 0; mask < classCount; mask++) {
      sb.append("            case ").append(mask).append(":\n");
      sb.append("                return new ")
          .append(implementationName(dimension, mask))
          .append("(grid.axes, grid.values);\n");
    }
    sb.append("            default:\n");
    sb.append(
        "                throw new AssertionError(\"Unexpected uniformity mask: \" + grid.uniformMask);\n");
    sb.append("        }\n");
    sb.append("    }\n\n");
  }

  private void appendArrayMultidimensionalFactory(StringBuilder sb, int dimension) {
    sb.append("    public static ")
        .append(interfaceName(dimension))
        .append(" create")
        .append(dimension)
        .append("D(double[][] axes, ")
        .append(multidimensionalValuesType(dimension))
        .append(" values) {\n");
    sb.append("        return create")
        .append(dimension)
        .append("D(axes, flatten")
        .append(dimension)
        .append("D(values));\n");
    sb.append("    }\n\n");
  }

  private void appendFlattenHelper(StringBuilder sb, int dimension) {
    sb.append("    public static double[] flatten")
        .append(dimension)
        .append("D(")
        .append(multidimensionalValuesType(dimension))
        .append(" values) {\n");
    sb.append("        if (values == null) {\n");
    sb.append("            throw new IllegalArgumentException(\"values must not be null\");\n");
    sb.append("        }\n");

    if (dimension == 1) {
      sb.append("        return values.clone();\n");
      sb.append("    }\n\n");
      return;
    }

    sb.append("        int n").append(dimension - 1).append(" = values.length;\n");
    for (int axis = dimension - 2; axis >= 0; axis--) {
      sb.append("        int n").append(axis).append(" = -1;\n");
    }
    sb.append("\n");

    for (int axis = dimension - 2; axis >= 0; axis--) {
      appendLoopsOpen(sb, dimension - 1, axis + 1, 2);
      String subarray = valuesAccess(dimension - 1, axis + 1);
      sb.append(indent(2 + dimension - 1 - axis))
          .append("if (")
          .append(subarray)
          .append(" == null) {\n");
      sb.append(indent(3 + dimension - 1 - axis))
          .append("throw new IllegalArgumentException(\"values contains a null subarray\");\n");
      sb.append(indent(2 + dimension - 1 - axis)).append("}\n");
      sb.append(indent(2 + dimension - 1 - axis))
          .append("if (n")
          .append(axis)
          .append(" == -1) {\n");
      sb.append(indent(3 + dimension - 1 - axis))
          .append("n")
          .append(axis)
          .append(" = ")
          .append(subarray)
          .append(".length;\n");
      sb.append(indent(2 + dimension - 1 - axis))
          .append("} else if (")
          .append(subarray)
          .append(".length != n")
          .append(axis)
          .append(") {\n");
      sb.append(indent(3 + dimension - 1 - axis))
          .append("throw new IllegalArgumentException(\"values must be rectangular\");\n");
      sb.append(indent(2 + dimension - 1 - axis)).append("}\n");
      appendLoopsClose(sb, dimension - 1, axis + 1, 2);
      sb.append("\n");
    }

    for (int axis = dimension - 2; axis >= 0; axis--) {
      sb.append("        if (n").append(axis).append(" == -1) {\n");
      sb.append("            n").append(axis).append(" = 0;\n");
      sb.append("        }\n");
    }
    sb.append("\n");

    sb.append("        double[] flattened = new double[checkedGridSize(");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append("n").append(axis);
    }
    sb.append(")];\n");
    sb.append("        int offset = 0;\n");
    appendLoopsOpen(sb, dimension - 1, 0, 2);
    sb.append(indent(2 + dimension))
        .append("flattened[offset++] = ")
        .append(valuesAccess(dimension - 1, 0))
        .append(";\n");
    appendLoopsClose(sb, dimension - 1, 0, 2);
    sb.append("        return flattened;\n");
    sb.append("    }\n\n");
  }

  private void appendCheckedGridSize(StringBuilder sb) {
    sb.append("    private static int checkedGridSize(int... sizes) {\n");
    sb.append("        long size = 1L;\n");
    sb.append("        for (int component : sizes) {\n");
    sb.append("            if (component < 0) {\n");
    sb.append(
        "                throw new IllegalArgumentException(\"Grid size must not be negative\");\n");
    sb.append("            }\n");
    sb.append("            if (component != 0 && size > Integer.MAX_VALUE / (long) component) {\n");
    sb.append("                throw new IllegalArgumentException(\"Grid is too large\");\n");
    sb.append("            }\n");
    sb.append("            size *= component;\n");
    sb.append("        }\n");
    sb.append("        return (int) size;\n");
    sb.append("    }\n");
  }

  private String generateSupport() {
    StringBuilder sb = new StringBuilder();
    sb.append(packageLine());
    sb.append("import java.util.Arrays;\n\n");
    sb.append("final class InterpolationSupport {\n\n");
    sb.append("    private InterpolationSupport() {}\n\n");

    sb.append(
        "    static PreparedGrid prepare(int dimensions, double[][] inputAxes, double[] inputValues) {\n");
    sb.append("        if (inputAxes == null) {\n");
    sb.append("            throw new IllegalArgumentException(\"axes must not be null\");\n");
    sb.append("        }\n");
    sb.append("        if (inputValues == null) {\n");
    sb.append("            throw new IllegalArgumentException(\"values must not be null\");\n");
    sb.append("        }\n");
    sb.append("        if (inputAxes.length != dimensions) {\n");
    sb.append(
        "            throw new IllegalArgumentException(\"Expected \" + dimensions + \" axes but got \" + inputAxes.length);\n");
    sb.append("        }\n\n");
    sb.append("        SortedAxis[] sortedAxes = new SortedAxis[dimensions];\n");
    sb.append("        int[] sizes = new int[dimensions];\n");
    sb.append("        long expectedLength = 1L;\n\n");
    sb.append("        for (int axis = 0; axis < dimensions; axis++) {\n");
    sb.append("            sortedAxes[axis] = sortAxis(inputAxes[axis], axis);\n");
    sb.append("            sizes[axis] = sortedAxes[axis].values.length;\n");
    sb.append("            if (expectedLength > Integer.MAX_VALUE / (long) sizes[axis]) {\n");
    sb.append("                throw new IllegalArgumentException(\"Grid is too large\");\n");
    sb.append("            }\n");
    sb.append("            expectedLength *= sizes[axis];\n");
    sb.append("        }\n\n");
    sb.append("        if (inputValues.length != (int) expectedLength) {\n");
    sb.append(
        "            throw new IllegalArgumentException(\"values length must equal the product of all axis lengths. Expected \" + expectedLength + \" but got \" + inputValues.length);\n");
    sb.append("        }\n\n");
    sb.append("        double[][] axes = new double[dimensions][];\n");
    sb.append("        boolean anyReordered = false;\n");
    sb.append("        int uniformMask = 0;\n\n");
    sb.append("        for (int axis = 0; axis < dimensions; axis++) {\n");
    sb.append("            axes[axis] = sortedAxes[axis].values;\n");
    sb.append("            anyReordered |= sortedAxes[axis].wasReordered();\n");
    sb.append("            if (isUniform(axes[axis])) {\n");
    sb.append("                uniformMask |= 1 << axis;\n");
    sb.append("            }\n");
    sb.append("        }\n\n");
    sb.append("        double[] values = anyReordered\n");
    sb.append("            ? reorderValues(inputValues, sizes, sortedAxes)\n");
    sb.append("            : inputValues.clone();\n\n");
    sb.append("        return new PreparedGrid(axes, values, uniformMask);\n");
    sb.append("    }\n\n");

    sb.append("    static int lowerIndex(double[] axis, double value) {\n");
    sb.append("        int low = 0;\n");
    sb.append("        int high = axis.length - 1;\n\n");
    sb.append("        while (high - low > 1) {\n");
    sb.append("            int mid = (low + high) >>> 1;\n");
    sb.append("            if (axis[mid] <= value) {\n");
    sb.append("                low = mid;\n");
    sb.append("            } else {\n");
    sb.append("                high = mid;\n");
    sb.append("            }\n");
    sb.append("        }\n\n");
    sb.append("        return low;\n");
    sb.append("    }\n\n");

    sb.append("    private static SortedAxis sortAxis(double[] axis, int axisNumber) {\n");
    sb.append("        if (axis == null) {\n");
    sb.append(
        "            throw new IllegalArgumentException(\"Axis \" + axisNumber + \" must not be null\");\n");
    sb.append("        }\n");
    sb.append("        if (axis.length < 2) {\n");
    sb.append(
        "            throw new IllegalArgumentException(\"Axis \" + axisNumber + \" must contain at least two values\");\n");
    sb.append("        }\n\n");
    sb.append("        boolean strictlyIncreasing = true;\n\n");
    sb.append("        for (int i = 0; i < axis.length; i++) {\n");
    sb.append("            double value = axis[i];\n");
    sb.append("            if (!Double.isFinite(value)) {\n");
    sb.append(
        "                throw new IllegalArgumentException(\"Axis \" + axisNumber + \" contains a non-finite value: \" + value);\n");
    sb.append("            }\n");
    sb.append("            if (i > 0 && !(axis[i] > axis[i - 1])) {\n");
    sb.append("                strictlyIncreasing = false;\n");
    sb.append("            }\n");
    sb.append("        }\n\n");
    sb.append("        if (strictlyIncreasing) {\n");
    sb.append("            return new SortedAxis(axis.clone(), null);\n");
    sb.append("        }\n\n");
    sb.append("        Integer[] order = new Integer[axis.length];\n");
    sb.append("        for (int i = 0; i < axis.length; i++) {\n");
    sb.append("            order[i] = i;\n");
    sb.append("        }\n\n");
    sb.append("        Arrays.sort(order, (a, b) -> Double.compare(axis[a], axis[b]));\n\n");
    sb.append("        double[] sorted = new double[axis.length];\n");
    sb.append("        int[] originalIndices = new int[axis.length];\n\n");
    sb.append("        for (int sortedIndex = 0; sortedIndex < axis.length; sortedIndex++) {\n");
    sb.append("            int originalIndex = order[sortedIndex];\n");
    sb.append("            double value = axis[originalIndex];\n");
    sb.append("            sorted[sortedIndex] = value;\n");
    sb.append("            originalIndices[sortedIndex] = originalIndex;\n\n");
    sb.append(
        "            if (sortedIndex > 0 && !(sorted[sortedIndex] > sorted[sortedIndex - 1])) {\n");
    sb.append(
        "                throw new IllegalArgumentException(\"Axis \" + axisNumber + \" values must be unique. Duplicate value: \" + value);\n");
    sb.append("            }\n");
    sb.append("        }\n\n");
    sb.append("        return new SortedAxis(sorted, originalIndices);\n");
    sb.append("    }\n\n");

    sb.append(
        "    private static double[] reorderValues(double[] inputValues, int[] sizes, SortedAxis[] axes) {\n");
    sb.append("        int dimensions = sizes.length;\n");
    sb.append("        int[] strides = new int[dimensions];\n");
    sb.append("        strides[0] = 1;\n");
    sb.append("        for (int axis = 1; axis < dimensions; axis++) {\n");
    sb.append("            strides[axis] = strides[axis - 1] * sizes[axis - 1];\n");
    sb.append("        }\n\n");
    sb.append("        double[] reordered = new double[inputValues.length];\n\n");
    sb.append("        for (int newLinear = 0; newLinear < inputValues.length; newLinear++) {\n");
    sb.append("            int oldLinear = 0;\n\n");
    sb.append("            for (int axis = 0; axis < dimensions; axis++) {\n");
    sb.append("                int newAxisIndex = (newLinear / strides[axis]) % sizes[axis];\n");
    sb.append("                int oldAxisIndex = axes[axis].originalIndex(newAxisIndex);\n");
    sb.append("                oldLinear += oldAxisIndex * strides[axis];\n");
    sb.append("            }\n\n");
    sb.append("            reordered[newLinear] = inputValues[oldLinear];\n");
    sb.append("        }\n\n");
    sb.append("        return reordered;\n");
    sb.append("    }\n\n");

    sb.append("    private static boolean isUniform(double[] axis) {\n");
    sb.append("        double step = axis[1] - axis[0];\n");
    sb.append("        double tolerance = Math.max(Math.abs(step) * 1e-12, 1e-15);\n\n");
    sb.append("        for (int i = 2; i < axis.length; i++) {\n");
    sb.append("            double currentStep = axis[i] - axis[i - 1];\n");
    sb.append("            if (Math.abs(currentStep - step) > tolerance) {\n");
    sb.append("                return false;\n");
    sb.append("            }\n");
    sb.append("        }\n\n");
    sb.append("        return true;\n");
    sb.append("    }\n\n");

    sb.append("    static final class PreparedGrid {\n");
    sb.append("        final double[][] axes;\n");
    sb.append("        final double[] values;\n");
    sb.append("        final int uniformMask;\n\n");
    sb.append("        PreparedGrid(double[][] axes, double[] values, int uniformMask) {\n");
    sb.append("            this.axes = axes;\n");
    sb.append("            this.values = values;\n");
    sb.append("            this.uniformMask = uniformMask;\n");
    sb.append("        }\n");
    sb.append("    }\n\n");

    sb.append("    private static final class SortedAxis {\n");
    sb.append("        final double[] values;\n");
    sb.append("        final int[] originalIndices;\n\n");
    sb.append("        SortedAxis(double[] values, int[] originalIndices) {\n");
    sb.append("            this.values = values;\n");
    sb.append("            this.originalIndices = originalIndices;\n");
    sb.append("        }\n\n");
    sb.append("        boolean wasReordered() {\n");
    sb.append("            return originalIndices != null;\n");
    sb.append("        }\n\n");
    sb.append("        int originalIndex(int sortedIndex) {\n");
    sb.append(
        "            return originalIndices == null ? sortedIndex : originalIndices[sortedIndex];\n");
    sb.append("        }\n");
    sb.append("    }\n");
    sb.append("}\n");
    return sb.toString();
  }

  private String generateImplementation(int dimension, int uniformMask) {
    StringBuilder sb = new StringBuilder();
    sb.append(packageLine());

    String className = implementationName(dimension, uniformMask);
    String interfaceName = interfaceName(dimension);

    sb.append("final class ")
        .append(className)
        .append(" implements ")
        .append(interfaceName)
        .append(" {\n\n");

    sb.append("    private final double[] values;\n\n");
    for (int axis = 0; axis < dimension; axis++) {
      sb.append("    private final int n").append(axis).append(";\n");
    }
    sb.append("\n");
    for (int axis = 1; axis < dimension; axis++) {
      sb.append("    private final int stride").append(axis).append(";\n");
    }
    if (dimension > 1) {
      sb.append("\n");
    }

    for (int axis = 0; axis < dimension; axis++) {
      if (isUniform(uniformMask, axis)) {
        sb.append("    private final double axis").append(axis).append("First;\n");
        sb.append("    private final double axis").append(axis).append("InvStep;\n\n");
      } else {
        sb.append("    private final double[] axis").append(axis).append(";\n");
        sb.append("    private final double axis").append(axis).append("First;\n");
        sb.append("    private final double axis").append(axis).append("Last;\n\n");
      }
    }

    sb.append("    ").append(className).append("(double[][] axes, double[] values) {\n");
    sb.append("        this.values = values;\n\n");
    for (int axis = 0; axis < dimension; axis++) {
      sb.append("        this.n")
          .append(axis)
          .append(" = axes[")
          .append(axis)
          .append("].length;\n");
    }
    if (dimension > 1) {
      sb.append("\n");
      for (int axis = 1; axis < dimension; axis++) {
        sb.append("        this.stride").append(axis).append(" = ");
        for (int term = 0; term < axis; term++) {
          if (term > 0) {
            sb.append(" * ");
          }
          sb.append("n").append(term);
        }
        sb.append(";\n");
      }
    }
    sb.append("\n");

    for (int axis = 0; axis < dimension; axis++) {
      if (isUniform(uniformMask, axis)) {
        sb.append("        this.axis")
            .append(axis)
            .append("First = axes[")
            .append(axis)
            .append("][0];\n");
        sb.append("        this.axis")
            .append(axis)
            .append("InvStep = 1.0 / (axes[")
            .append(axis)
            .append("][1] - axes[")
            .append(axis)
            .append("][0]);\n");
      } else {
        sb.append("        this.axis").append(axis).append(" = axes[").append(axis).append("];\n");
        sb.append("        this.axis")
            .append(axis)
            .append("First = axes[")
            .append(axis)
            .append("][0];\n");
        sb.append("        this.axis")
            .append(axis)
            .append("Last = axes[")
            .append(axis)
            .append("][n")
            .append(axis)
            .append(" - 1];\n");
      }
      if (axis + 1 < dimension) {
        sb.append("\n");
      }
    }
    sb.append("    }\n\n");

    sb.append("    @Override\n");
    sb.append("    public ").append(typedMethodSignature(dimension)).append(" {\n");

    for (int axis = 0; axis < dimension; axis++) {
      appendAxisLookup(sb, dimension, uniformMask, axis);
    }

    sb.append("        int base = ").append(baseExpression(dimension)).append(";\n\n");
    sb.append("        double[] data = values;\n\n");

    appendCornerLoads(sb, dimension);
    appendBlendCode(sb, dimension);

    sb.append("    }\n");
    sb.append("}\n");
    return sb.toString();
  }

  private void appendAxisLookup(StringBuilder sb, int dimension, int uniformMask, int axis) {
    String coord = COORD_NAMES[axis];
    sb.append("        int i").append(axis).append(";\n");
    sb.append("        double t").append(axis).append(";\n");
    if (isUniform(uniformMask, axis)) {
      sb.append("        double p")
          .append(axis)
          .append(" = (")
          .append(coord)
          .append(" - axis")
          .append(axis)
          .append("First) * axis")
          .append(axis)
          .append("InvStep;\n");
      sb.append("        if (p").append(axis).append(" <= 0.0) {\n");
      sb.append("            i").append(axis).append(" = 0;\n");
      sb.append("            t").append(axis).append(" = 0.0;\n");
      sb.append("        } else if (p")
          .append(axis)
          .append(" >= n")
          .append(axis)
          .append(" - 1) {\n");
      sb.append("            i").append(axis).append(" = n").append(axis).append(" - 2;\n");
      sb.append("            t").append(axis).append(" = 1.0;\n");
      sb.append("        } else {\n");
      sb.append("            i").append(axis).append(" = (int) p").append(axis).append(";\n");
      sb.append("            t")
          .append(axis)
          .append(" = p")
          .append(axis)
          .append(" - i")
          .append(axis)
          .append(";\n");
      sb.append("        }\n\n");
    } else {
      sb.append("        if (").append(coord).append(" <= axis").append(axis).append("First) {\n");
      sb.append("            i").append(axis).append(" = 0;\n");
      sb.append("            t").append(axis).append(" = 0.0;\n");
      sb.append("        } else if (")
          .append(coord)
          .append(" >= axis")
          .append(axis)
          .append("Last) {\n");
      sb.append("            i").append(axis).append(" = n").append(axis).append(" - 2;\n");
      sb.append("            t").append(axis).append(" = 1.0;\n");
      sb.append("        } else {\n");
      sb.append("            i")
          .append(axis)
          .append(" = InterpolationSupport.lowerIndex(axis")
          .append(axis)
          .append(", ")
          .append(coord)
          .append(");\n");
      sb.append("            t")
          .append(axis)
          .append(" = (")
          .append(coord)
          .append(" - axis")
          .append(axis)
          .append("[i")
          .append(axis)
          .append("]) / (axis")
          .append(axis)
          .append("[i")
          .append(axis)
          .append(" + 1] - axis")
          .append(axis)
          .append("[i")
          .append(axis)
          .append("]);\n");
      sb.append("        }\n\n");
    }
  }

  private void appendCornerLoads(StringBuilder sb, int dimension) {
    int corners = 1 << dimension;
    for (int mask = 0; mask < corners; mask++) {
      sb.append("        double ")
          .append(valueVar(mask, dimension))
          .append(" = data[")
          .append(offsetExpression(mask, dimension))
          .append("];\n");
    }
    sb.append("\n");
  }

  private void appendBlendCode(StringBuilder sb, int dimension) {
    int corners = 1 << dimension;
    String[] variableByMask = new String[corners];
    List<Integer> activeMasks = new ArrayList<Integer>();
    for (int mask = 0; mask < corners; mask++) {
      variableByMask[mask] = valueVar(mask, dimension);
      activeMasks.add(mask);
    }

    for (int axis = 0; axis < dimension; axis++) {
      String[] nextVariableByMask = new String[corners];
      List<Integer> nextActiveMasks = new ArrayList<Integer>();

      for (int index = 0; index < activeMasks.size(); index++) {
        int lowerMask = activeMasks.get(index);
        if ((lowerMask & (1 << axis)) != 0) {
          continue;
        }

        int upperMask = lowerMask | (1 << axis);
        String lower = variableByMask[lowerMask];
        String upper = variableByMask[upperMask];
        String blended = blendVar(axis, lowerMask, dimension);

        sb.append("        double ")
            .append(blended)
            .append(" = ")
            .append(lower)
            .append(" + t")
            .append(axis)
            .append(" * (")
            .append(upper)
            .append(" - ")
            .append(lower)
            .append(");\n");

        nextVariableByMask[lowerMask] = blended;
        nextActiveMasks.add(lowerMask);
      }

      variableByMask = nextVariableByMask;
      activeMasks = nextActiveMasks;

      if (axis + 1 < dimension) {
        sb.append("\n");
      }
    }

    sb.append("\n");
    sb.append("        return ").append(variableByMask[0]).append(";\n");
  }

  private String typedMethodSignature(int dimension) {
    StringBuilder sb = new StringBuilder();
    sb.append("double interpolate(");
    for (int axis = 0; axis < dimension; axis++) {
      if (axis > 0) {
        sb.append(", ");
      }
      sb.append("double ").append(COORD_NAMES[axis]);
    }
    sb.append(")");
    return sb.toString();
  }

  private String multidimensionalValuesType(int dimension) {
    StringBuilder sb = new StringBuilder("double");
    for (int axis = 0; axis < dimension; axis++) {
      sb.append("[]");
    }
    return sb.toString();
  }

  private void appendLoopsOpen(StringBuilder sb, int highestAxis, int lowestAxis, int baseIndent) {
    for (int axis = highestAxis; axis >= lowestAxis; axis--) {
      sb.append(indent(baseIndent + highestAxis - axis))
          .append("for (int i")
          .append(axis)
          .append(" = 0; i")
          .append(axis)
          .append(" < n")
          .append(axis)
          .append("; i")
          .append(axis)
          .append("++) {\n");
    }
  }

  private void appendLoopsClose(StringBuilder sb, int highestAxis, int lowestAxis, int baseIndent) {
    for (int axis = lowestAxis; axis <= highestAxis; axis++) {
      sb.append(indent(baseIndent + highestAxis - axis)).append("}\n");
    }
  }

  private String valuesAccess(int highestAxis, int lowestAxis) {
    StringBuilder sb = new StringBuilder("values");
    for (int axis = highestAxis; axis >= lowestAxis; axis--) {
      sb.append("[i").append(axis).append("]");
    }
    return sb.toString();
  }

  private String indent(int levels) {
    StringBuilder sb = new StringBuilder();
    for (int level = 0; level < levels; level++) {
      sb.append("    ");
    }
    return sb.toString();
  }

  private String baseExpression(int dimension) {
    StringBuilder sb = new StringBuilder();
    sb.append("i0");
    for (int axis = 1; axis < dimension; axis++) {
      sb.append(" + stride").append(axis).append(" * i").append(axis);
    }
    return sb.toString();
  }

  private String offsetExpression(int mask, int dimension) {
    StringBuilder sb = new StringBuilder();
    sb.append("base");
    for (int axis = 0; axis < dimension; axis++) {
      if ((mask & (1 << axis)) != 0) {
        if (axis == 0) {
          sb.append(" + 1");
        } else {
          sb.append(" + stride").append(axis);
        }
      }
    }
    return sb.toString();
  }

  private String valueVar(int mask, int dimension) {
    return "v" + bits(mask, dimension);
  }

  private String blendVar(int axis, int lowerMask, int dimension) {
    return "b" + axis + "_" + bits(lowerMask, dimension);
  }

  private String bits(int mask, int dimension) {
    StringBuilder sb = new StringBuilder();
    for (int axis = 0; axis < dimension; axis++) {
      sb.append((mask & (1 << axis)) == 0 ? '0' : '1');
    }
    return sb.toString();
  }

  private String patternName(int dimension, int mask) {
    StringBuilder sb = new StringBuilder();
    for (int axis = 0; axis < dimension; axis++) {
      sb.append(isUniform(mask, axis) ? 'U' : 'N');
    }
    return sb.toString();
  }

  private boolean isUniform(int mask, int axis) {
    return (mask & (1 << axis)) != 0;
  }

  private String interfaceName(int dimension) {
    return "Interpolator" + dimension + "D";
  }

  private String implementationName(int dimension, int mask) {
    return "Interpolator" + dimension + "D_" + patternName(dimension, mask);
  }

  private String arrayName(int axis) {
    return COORD_NAMES[axis] + "s";
  }
}
