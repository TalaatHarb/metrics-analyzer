package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.AnalysisResult;
import net.talaatharb.analyzer.model.ClassMetrics;
import net.talaatharb.analyzer.model.DependencyRelation;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaSourceProjectAnalyzer implements MetricsAnalyzerService {
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

        Launcher launcher = new Launcher();
        launcher.addInputResource(sourcePath.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);

        CtModel model = launcher.buildModel();
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
            if (type.getQualifiedName().startsWith("java.")) {
                continue;
            }

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

            String sourceType = type.getQualifiedName();
            Set<String> usedTypes = extractInternalUsedTypeNames(type, projectTypeNames);
            for (String targetType : usedTypes) {
                String key = sourceType + "->" + targetType;
                if (classCouplingKeys.add(key)) {
                    classCouplings.add(new DependencyRelation(sourceType, targetType));
                }
            }
        }

        rows.sort(Comparator.comparing(ClassMetrics::getClassName));
        List<DependencyRelation> packageCouplings = buildPackageCouplings(classCouplings, typeToPackage);
        return new AnalysisResult(sourcePath, rows, classCouplings, packageCouplings);
    }

    private static Set<String> extractInternalUsedTypeNames(CtType<?> type, Set<String> projectTypeNames) {
        Set<String> result = new HashSet<>();
        for (CtTypeReference<?> usedType : type.getUsedTypes(false)) {
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
        return type.toString().split("\\R").length;
    }

    private static int calculateEfferentCoupling(CtType<?> type) {
        Set<CtTypeReference<?>> usedTypes = type.getUsedTypes(false);
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
            String body = methods.get(i).getBody() == null ? "" : methods.get(i).getBody().toString();
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
            String body = method.getBody() == null ? "" : method.getBody().toString();
            totalDecisionPoints += countDecisionPoints(body);
        }
        return Math.max(1, totalDecisionPoints);
    }

    private static int calculateWmc(CtType<?> type) {
        int total = 0;
        for (CtMethod<?> method : type.getMethods()) {
            String body = method.getBody() == null ? "" : method.getBody().toString();
            total += countDecisionPoints(body) + 1;
        }
        return total;
    }

    private static int calculateRfc(CtType<?> type) {
        Set<String> calls = new LinkedHashSet<>();
        Pattern callPattern = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\s*\\(");
        for (CtMethod<?> method : type.getMethods()) {
            String body = method.getBody() == null ? "" : method.getBody().toString();
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
        double normalized = (raw * 100.0) / 171.0;
        return Math.max(0.0, Math.min(100.0, normalized));
    }

    private static double calculateHalsteadVolume(CtType<?> type) {
        String code = type.toString();
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

        System.out.println("========================================");
        System.out.println("   CODE METRICS ANALYSIS REPORT");
        System.out.println("========================================");
        System.out.println(result.buildSummary());

        for (ClassMetrics row : result.getClassMetrics()) {
            System.out.println(row.getPackageName() + "." + row.getClassName());
            System.out.println("  LOC: " + row.getLinesOfCode()
                    + ", Methods: " + row.getMethodCount()
                    + ", Fields: " + row.getFieldCount());
            System.out.println("  Coupling: " + row.getEfferentCoupling()
                    + ", LCOM: " + String.format(java.util.Locale.US, "%.2f", row.getLcom())
                    + ", CC: " + row.getCyclomaticComplexity());
            System.out.println("  WMC: " + row.getWeightedMethodsPerClass()
                    + ", RFC: " + row.getResponseForClass()
                    + ", MI: " + String.format(java.util.Locale.US, "%.2f", row.getMaintainabilityIndex()));
            System.out.println();
        }
    }
}
