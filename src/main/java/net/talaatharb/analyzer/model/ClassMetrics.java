package net.talaatharb.analyzer.model;

public final class ClassMetrics {
    private final String packageName;
    private final String className;
    private final int linesOfCode;
    private final int methodCount;
    private final int fieldCount;
    private final int efferentCoupling;
    private final double lcom;
    private final int cyclomaticComplexity;
    private final int weightedMethodsPerClass;
    private final int responseForClass;
    private final double maintainabilityIndex;

    public ClassMetrics(
            String packageName,
            String className,
            int linesOfCode,
            int methodCount,
            int fieldCount,
            int efferentCoupling,
            double lcom,
            int cyclomaticComplexity,
            int weightedMethodsPerClass,
            int responseForClass,
            double maintainabilityIndex
    ) {
        this.packageName = packageName;
        this.className = className;
        this.linesOfCode = linesOfCode;
        this.methodCount = methodCount;
        this.fieldCount = fieldCount;
        this.efferentCoupling = efferentCoupling;
        this.lcom = lcom;
        this.cyclomaticComplexity = cyclomaticComplexity;
        this.weightedMethodsPerClass = weightedMethodsPerClass;
        this.responseForClass = responseForClass;
        this.maintainabilityIndex = maintainabilityIndex;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    public int getLinesOfCode() {
        return linesOfCode;
    }

    public int getMethodCount() {
        return methodCount;
    }

    public int getFieldCount() {
        return fieldCount;
    }

    public int getEfferentCoupling() {
        return efferentCoupling;
    }

    public double getLcom() {
        return lcom;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public int getWeightedMethodsPerClass() {
        return weightedMethodsPerClass;
    }

    public int getResponseForClass() {
        return responseForClass;
    }

    public double getMaintainabilityIndex() {
        return maintainabilityIndex;
    }
}
