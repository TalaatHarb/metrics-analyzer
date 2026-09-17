package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.AnalysisResult;
import net.talaatharb.analyzer.model.ClassMetrics;
import net.talaatharb.analyzer.model.DependencyRelation;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaSourceProjectAnalyzer implements MetricsAnalyzerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JavaSourceProjectAnalyzer.class);
    private static final String MAVEN_CLASSPATH_GOAL =
            "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath";

    @Override
    public String getDisplayName() {
        return "Java Source Project Analyzer";
    }

    @Override
    public boolean supports(Path projectRoot) {
        Path sourcePath = resolveSourcePath(projectRoot);
        if (!Files.exists(sourcePath)) {
            return false;
        }

        if (Files.isDirectory(sourcePath)) {
            try (Stream<Path> paths = Files.walk(sourcePath)) {
                return paths.anyMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"));
            } catch (IOException e) {
                return false;
            }
        }

        return sourcePath.toString().endsWith(".java");
    }

    @Override
    public AnalysisResult analyzeProject(Path projectRoot) {
        Path sourcePath = resolveSourcePath(projectRoot);
        if (!Files.exists(sourcePath)) {
            throw new IllegalArgumentException("Source path does not exist: " + sourcePath);
        }

        List<String> sourceClasspath = resolveSourceClasspath(projectRoot);
        CtModel model = buildModel(sourcePath, sourceClasspath, projectRoot);
        List<ClassMetrics> rows = new ArrayList<>();
        List<DependencyRelation> classCouplings = new ArrayList<>();
        Set<String> classCouplingKeys = new HashSet<>();
        Set<String> projectTypeNames = new HashSet<>();
        Map<String, String> typeToPackage = new HashMap<>();

        for (CtType<?> type : model.getAllTypes()) {
            String qualifiedName = type.getQualifiedName();
            if (qualifiedName != null && !qualifiedName.startsWith("java.")) {
                projectTypeNames.add(qualifiedName);
                typeToPackage.put(qualifiedName, safePackageName(type));
            }
        }

        for (CtType<?> type : model.getAllTypes()) {
            String qualifiedName = type.getQualifiedName();
            if (qualifiedName == null || qualifiedName.startsWith("java.")) {
                continue;
            }

            try {
                int loc = calculateLinesOfCode(type);
                int methods = type.getMethods().size();
                int fields = type.getFields().size();
                int coupling = calculateEfferentCoupling(type);
                double lcom = calculateLcom(type);
                int complexity = calculateCyclomaticComplexity(type);
                int wmc = calculateWmc(type);
                int rfc = calculateRfc(type);
                double maintainability = calculateMaintainabilityIndex(type, loc, complexity);

                rows.add(new ClassMetrics(
                        safePackageName(type),
                        type.getSimpleName(),
                        loc,
                        methods,
                        fields,
                        coupling,
                        lcom,
                        complexity,
                        wmc,
                        rfc,
                        maintainability
                ));

                Set<String> usedTypes = extractInternalUsedTypeNames(type, projectTypeNames);
                for (String targetType : usedTypes) {
                    String key = qualifiedName + "->" + targetType;
                    if (classCouplingKeys.add(key)) {
                        classCouplings.add(new DependencyRelation(qualifiedName, targetType));
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Skipping metrics for type {} because Spoon could not fully resolve it: {}",
                        qualifiedName, ex.getMessage());
            }
        }

        rows.sort(Comparator.comparing(ClassMetrics::getClassName));
        List<DependencyRelation> packageCouplings = buildPackageCouplings(classCouplings, typeToPackage);
        return new AnalysisResult(sourcePath, rows, classCouplings, packageCouplings);
    }

    private static CtModel buildModel(Path sourcePath, List<String> sourceClasspath, Path projectRoot) {
        try {
            return createLauncher(sourcePath, sourceClasspath).buildModel();
        } catch (RuntimeException ex) {
            if (sourceClasspath.isEmpty()) {
                throw ex;
            }
            LOGGER.warn("Spoon model build with Maven classpath failed for {}. Falling back to no-classpath mode: {}",
                    projectRoot, ex.getMessage());
            return createLauncher(sourcePath, List.of()).buildModel();
        }
    }

    private static Launcher createLauncher(Path sourcePath, List<String> sourceClasspath) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourcePath.toString());
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setNoClasspath(sourceClasspath.isEmpty());
        if (!sourceClasspath.isEmpty()) {
            launcher.getEnvironment().setSourceClasspath(sourceClasspath.toArray(String[]::new));
        }
        return launcher;
    }

    static List<String> parseClasspathEntries(String rawClasspath) {
        if (rawClasspath == null || rawClasspath.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawClasspath.split(Pattern.quote(File.pathSeparator)))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<String> resolveSourceClasspath(Path projectRoot) {
        if (ProjectTypeDetector.detect(projectRoot) != ProjectType.JAVA_MAVEN) {
            return List.of();
        }

        List<String> entries = new ArrayList<>();
        addClasspathEntryIfExists(entries, projectRoot.resolve("target").resolve("classes"));
        addClasspathEntryIfExists(entries, projectRoot.resolve("target").resolve("test-classes"));
        addClasspathEntryIfExists(entries, projectRoot.resolve("target").resolve("generated-sources").resolve("annotations"));

        Path classpathFile = null;
        Path logFile = null;
        try {
            classpathFile = Files.createTempFile("metrics-analyzer-maven-classpath", ".txt");
            List<String> command = List.of(
                    resolveMavenCommand(projectRoot),
                    MAVEN_CLASSPATH_GOAL,
                    "-DincludeScope=compile",
                    "-Dmdep.outputFile=" + classpathFile.toAbsolutePath(),
                    "-q"
            );
            ServicePackageStaticAnalyzerSupport.ProcessExecution execution =
                    ServicePackageStaticAnalyzerSupport.runCommand(projectRoot, "spoon-maven-classpath", command);
            logFile = execution.getLogFile();
            if (execution.getExitCode() != 0) {
                LOGGER.warn("Could not resolve Maven classpath for {} (exit code {}). Log tail: {}",
                        projectRoot,
                        execution.getExitCode(),
                        ServicePackageStaticAnalyzerSupport.getLogTail(logFile, 10));
                return entries;
            }

            entries.addAll(parseClasspathEntries(Files.readString(classpathFile))
                    .stream()
                    .filter(JavaSourceProjectAnalyzer::classpathEntryExists)
                    .collect(Collectors.toList()));
        } catch (Exception ex) {
            LOGGER.warn("Failed to resolve Maven classpath for {}: {}", projectRoot, ex.getMessage());
        } finally {
            if (classpathFile != null) {
                ServicePackageStaticAnalyzerSupport.deleteFileQuietly(classpathFile);
            }
            if (logFile != null) {
                ServicePackageStaticAnalyzerSupport.deleteFileQuietly(logFile);
            }
        }

        List<String> distinctEntries = entries.stream().distinct().collect(Collectors.toList());
        if (!distinctEntries.isEmpty()) {
            LOGGER.info("Resolved {} Maven classpath entries for {}", distinctEntries.size(), projectRoot);
        }
        return distinctEntries;
    }

    private static void addClasspathEntryIfExists(List<String> entries, Path path) {
        if (Files.exists(path)) {
            entries.add(path.toAbsolutePath().normalize().toString());
        }
    }

    private static boolean classpathEntryExists(String entry) {
        try {
            return Files.exists(Path.of(entry));
        } catch (RuntimeException ex) {
            LOGGER.debug("Ignoring invalid classpath entry {}: {}", entry, ex.getMessage());
            return false;
        }
    }

    private static String resolveMavenCommand(Path projectRoot) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = projectRoot.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath().normalize().toString();
        }
        return ServicePackageStaticAnalyzerSupport.getMavenCommand();
    }

    private static Set<String> extractInternalUsedTypeNames(CtType<?> type, Set<String> projectTypeNames) {
        Set<String> result = new HashSet<>();
        for (CtTypeReference<?> usedType : safeUsedTypes(type)) {
            String targetName = usedType.getQualifiedName();
            if (targetName == null || targetName.isBlank()) {
                continue;
            }
            if (targetName.startsWith("java.")) {
                continue;
            }
            if (targetName.equals(type.getQualifiedName())) {
                continue;
            }
            if (projectTypeNames.contains(targetName)) {
                result.add(targetName);
            }
        }
        return result;
    }

    private static List<DependencyRelation> buildPackageCouplings(
            List<DependencyRelation> classCouplings,
            Map<String, String> typeToPackage
    ) {
        Set<String> packageKeys = new LinkedHashSet<>();
        List<DependencyRelation> packageRelations = new ArrayList<>();
        for (DependencyRelation relation : classCouplings) {
            String sourcePackage = typeToPackage.getOrDefault(relation.getSource(), "");
            String targetPackage = typeToPackage.getOrDefault(relation.getTarget(), "");
            if (sourcePackage.equals(targetPackage)) {
                continue;
            }
            String key = sourcePackage + "->" + targetPackage;
            if (packageKeys.add(key)) {
                packageRelations.add(new DependencyRelation(sourcePackage, targetPackage));
            }
        }
        return packageRelations;
    }

    private static Path resolveSourcePath(Path projectRoot) {
        Path mavenStyle = projectRoot.resolve("src").resolve("main").resolve("java");
        if (Files.exists(mavenStyle)) {
            return mavenStyle;
        }
        return projectRoot;
    }

    private static String safePackageName(CtType<?> type) {
        if (type.getPackage() == null) {
            return "";
        }
        return type.getPackage().getQualifiedName();
    }

    private static int calculateLinesOfCode(CtType<?> type) {
        if (type.getPosition() != null && type.getPosition().isValidPosition()) {
            return Math.max(1, type.getPosition().getEndLine() - type.getPosition().getLine() + 1);
        }
        return safeElementText(type).split("\\R").length;
    }

    private static int calculateEfferentCoupling(CtType<?> type) {
        Set<CtTypeReference<?>> usedTypes = safeUsedTypes(type);
        return (int) usedTypes.stream()
                .map(CtTypeReference::getQualifiedName)
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> !name.startsWith("java."))
                .filter(name -> !name.equals(type.getQualifiedName()))
                .distinct()
                .count();
    }

    private static double calculateLcom(CtType<?> type) {
        List<CtMethod<?>> methods = type.getMethods().stream()
                .filter(method -> !method.isStatic())
                .collect(Collectors.toList());
        List<CtField<?>> fields = type.getFields();

        if (methods.size() < 2 || fields.isEmpty()) {
            return 0.0;
        }

        int[][] matrix = new int[methods.size()][fields.size()];
        for (int i = 0; i < methods.size(); i++) {
            String body = safeElementText(methods.get(i).getBody());
            for (int j = 0; j < fields.size(); j++) {
                String field = fields.get(j).getSimpleName();
                if (body.contains(field)) {
                    matrix[i][j] = 1;
                }
            }
        }

        int disjointPairs = 0;
        int totalPairs = 0;
        for (int i = 0; i < methods.size() - 1; i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                totalPairs++;
                boolean shareField = false;
                for (int k = 0; k < fields.size(); k++) {
                    if (matrix[i][k] == 1 && matrix[j][k] == 1) {
                        shareField = true;
                        break;
                    }
                }
                if (!shareField) {
                    disjointPairs++;
                }
            }
        }

        return totalPairs == 0 ? 0.0 : (double) disjointPairs / totalPairs;
    }

    private static int calculateCyclomaticComplexity(CtType<?> type) {
        int totalDecisionPoints = 0;
        for (CtMethod<?> method : type.getMethods()) {
            String body = safeElementText(method.getBody());
            totalDecisionPoints += countDecisionPoints(body);
        }
        return Math.max(1, totalDecisionPoints);
    }

    private static int calculateWmc(CtType<?> type) {
        int total = 0;
        for (CtMethod<?> method : type.getMethods()) {
            String body = safeElementText(method.getBody());
            total += countDecisionPoints(body) + 1;
        }
        return total;
    }

    private static int calculateRfc(CtType<?> type) {
        Set<String> calls = new LinkedHashSet<>();
        Pattern callPattern = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\s*\\(");
        for (CtMethod<?> method : type.getMethods()) {
            String body = safeElementText(method.getBody());
            Matcher matcher = callPattern.matcher(body);
            while (matcher.find()) {
                String token = matcher.group().replaceAll("\\s*\\($", "");
                if (!token.equals("if") && !token.equals("for") && !token.equals("while") && !token.equals("switch")
                        && !token.equals("catch") && !token.equals("return") && !token.equals("new")) {
                    calls.add(token);
                }
            }
        }
        return calls.size();
    }

    private static int countDecisionPoints(String body) {
        return count(body, "\\bif\\s*\\(")
                + count(body, "\\belse\\s+if\\s*\\(")
                + count(body, "\\bfor\\s*\\(")
                + count(body, "\\bwhile\\s*\\(")
                + count(body, "\\bswitch\\s*\\(")
                + count(body, "\\bcatch\\s*\\(")
                + count(body, "\\&\\&|\\|\\|")
                + count(body, "\\?.*:");
    }

    private static int count(String text, String regex) {
        return (int) Pattern.compile(regex).matcher(text).results().count();
    }

    private static double calculateMaintainabilityIndex(CtType<?> type, int loc, int complexity) {
        double halsteadVolume = calculateHalsteadVolume(type);
        double raw = 171.0
                - 5.2 * Math.log(Math.max(halsteadVolume, 1.0))
                - 0.23 * complexity
                - 16.2 * Math.log(Math.max(loc, 1));
        double normalized = raw * 100.0 / 171.0;
        return Math.max(0.0, Math.min(100.0, normalized));
    }

    private static double calculateHalsteadVolume(CtType<?> type) {
        String code = safeElementText(type);
        if (code.isBlank()) {
            return 0.0;
        }

        Pattern operatorPattern = Pattern.compile(
                "\\b(instanceof|new|return|throw|if|else|for|while|switch|case|catch|try)\\b"
                        + "|==|!=|<=|>=|&&|\\|\\||\\+\\+|--|->"
                        + "|[+\\-*/%=&|^~<>?:.]"
        );
        Pattern operandPattern = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b|\\b\\d+(?:\\.\\d+)?\\b");

        Matcher operatorMatcher = operatorPattern.matcher(code);
        Matcher operandMatcher = operandPattern.matcher(code);

        int totalOperators = 0;
        int totalOperands = 0;
        Set<String> distinctOperators = new LinkedHashSet<>();
        Set<String> distinctOperands = new LinkedHashSet<>();

        while (operatorMatcher.find()) {
            String token = operatorMatcher.group();
            distinctOperators.add(token);
            totalOperators++;
        }

        while (operandMatcher.find()) {
            String token = operandMatcher.group();
            if (isKeyword(token)) {
                continue;
            }
            distinctOperands.add(token);
            totalOperands++;
        }

        int vocabulary = distinctOperators.size() + distinctOperands.size();
        int length = totalOperators + totalOperands;
        if (vocabulary <= 1 || length == 0) {
            return 0.0;
        }
        return length * (Math.log(vocabulary) / Math.log(2.0));
    }

    private static Set<CtTypeReference<?>> safeUsedTypes(CtType<?> type) {
        try {
            return type.getUsedTypes(false);
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not compute used types for {}: {}", type.getQualifiedName(), ex.getMessage());
            return Set.of();
        }
    }

    private static String safeElementText(CtElement element) {
        if (element == null) {
            return "";
        }

        try {
            if (element.getPosition() != null
                    && element.getPosition().isValidPosition()
                    && element.getPosition().getCompilationUnit() != null) {
                String originalSource = element.getPosition().getCompilationUnit().getOriginalSourceCode();
                int start = element.getPosition().getSourceStart();
                int end = element.getPosition().getSourceEnd();
                if (originalSource != null && start >= 0 && end >= start && end < originalSource.length()) {
                    return originalSource.substring(start, end + 1);
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("Could not read original source for Spoon element: {}", ex.getMessage());
        }

        try {
            return element.toString();
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not render Spoon element as source text: {}", ex.getMessage());
            return "";
        }
    }

    private static boolean isKeyword(String token) {
        return "abstract".equals(token)
                || "assert".equals(token)
                || "boolean".equals(token)
                || "break".equals(token)
                || "byte".equals(token)
                || "case".equals(token)
                || "catch".equals(token)
                || "char".equals(token)
                || "class".equals(token)
                || "const".equals(token)
                || "continue".equals(token)
                || "default".equals(token)
                || "do".equals(token)
                || "double".equals(token)
                || "else".equals(token)
                || "enum".equals(token)
                || "extends".equals(token)
                || "final".equals(token)
                || "finally".equals(token)
                || "float".equals(token)
                || "for".equals(token)
                || "goto".equals(token)
                || "if".equals(token)
                || "implements".equals(token)
                || "import".equals(token)
                || "instanceof".equals(token)
                || "int".equals(token)
                || "interface".equals(token)
                || "long".equals(token)
                || "native".equals(token)
                || "new".equals(token)
                || "package".equals(token)
                || "private".equals(token)
                || "protected".equals(token)
                || "public".equals(token)
                || "return".equals(token)
                || "short".equals(token)
                || "static".equals(token)
                || "strictfp".equals(token)
                || "super".equals(token)
                || "switch".equals(token)
                || "synchronized".equals(token)
                || "this".equals(token)
                || "throw".equals(token)
                || "throws".equals(token)
                || "transient".equals(token)
                || "try".equals(token)
                || "void".equals(token)
                || "volatile".equals(token)
                || "while".equals(token)
                || "true".equals(token)
                || "false".equals(token)
                || "null".equals(token);
    }

    public static void main(String[] args) {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        JavaSourceProjectAnalyzer analyzer = new JavaSourceProjectAnalyzer();
        AnalysisResult result = analyzer.analyzeProject(root);

        LOGGER.info("========================================");
        LOGGER.info("   CODE METRICS ANALYSIS REPORT");
        LOGGER.info("========================================");
        LOGGER.info("{}", result.buildSummary());

        for (ClassMetrics row : result.getClassMetrics()) {
            LOGGER.info("{}.{}", row.getPackageName(), row.getClassName());
            LOGGER.info("  LOC: " + row.getLinesOfCode()
                    + ", Methods: " + row.getMethodCount()
                    + ", Fields: " + row.getFieldCount());
            LOGGER.info("  Coupling: " + row.getEfferentCoupling()
                    + ", LCOM: " + String.format(java.util.Locale.US, "%.2f", row.getLcom())
                    + ", CC: " + row.getCyclomaticComplexity());
            LOGGER.info("  WMC: " + row.getWeightedMethodsPerClass()
                    + ", RFC: " + row.getResponseForClass()
                    + ", MI: " + String.format(java.util.Locale.US, "%.2f", row.getMaintainabilityIndex()));
            LOGGER.info("");
        }
    }
}
