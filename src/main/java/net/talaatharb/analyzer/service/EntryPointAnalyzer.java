package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects application entry points in Java source code.
 *
 * <p>Identifies the following kinds of entry points:
 * <ul>
 *   <li>Main methods ({@code public static void main(String[])})</li>
 *   <li>REST API endpoints ({@code @GetMapping}, {@code @PostMapping}, {@code @PutMapping},
 *       {@code @DeleteMapping}, {@code @PatchMapping}, {@code @RequestMapping})</li>
 *   <li>Spring Boot CLI runners ({@code CommandLineRunner}, {@code ApplicationRunner})</li>
 *   <li>Message-queue listeners ({@code @KafkaListener}, {@code @RabbitListener},
 *       {@code @SqsListener}, {@code @JmsListener})</li>
 *   <li>Scheduled / cron jobs ({@code @Scheduled})</li>
 *   <li>gRPC service endpoints ({@code @GrpcService} or extension of a generated
 *       {@code *Grpc.*ImplBase} stub)</li>
 *   <li>GraphQL endpoints ({@code @QueryMapping}, {@code @MutationMapping},
 *       {@code @SubscriptionMapping}, {@code @SchemaMapping}, {@code @GraphQlRepository})</li>
 *   <li>Spring Boot application classes ({@code @SpringBootApplication})</li>
 *   <li>Spring configuration classes ({@code @Configuration}, {@code @RestController})</li>
 * </ul>
 *
 * <p>Detection uses a line-by-line scan with an annotation-accumulation buffer: consecutive
 * annotation lines are collected, and when the next non-blank, non-annotation line is reached
 * the buffer is inspected together with that line to identify entry-point patterns.
 */
public class EntryPointAnalyzer implements StaticAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntryPointAnalyzer.class);

    // ── Main method ─────────────────────────────────────────────────────────
    private static final Pattern MAIN_METHOD = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(\\s*String");

    // ── REST mapping annotations ─────────────────────────────────────────────
    private static final Pattern REST_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping\\b");

    // ── REST controller class annotation ────────────────────────────────────
    private static final Pattern REST_CONTROLLER = Pattern.compile(
            "@(RestController|Controller)\\b");

    // ── Message-queue listener annotations ──────────────────────────────────
    private static final Pattern KAFKA_LISTENER = Pattern.compile("@KafkaListener\\b");
    private static final Pattern RABBIT_LISTENER = Pattern.compile("@RabbitListener\\b");
    private static final Pattern SQS_LISTENER = Pattern.compile("@SqsListener\\b");
    private static final Pattern JMS_LISTENER = Pattern.compile("@JmsListener\\b");

    // ── Scheduling annotation ────────────────────────────────────────────────
    private static final Pattern SCHEDULED = Pattern.compile("@Scheduled\\b");

    // ── gRPC patterns ────────────────────────────────────────────────────────
    private static final Pattern GRPC_SERVICE_ANNOTATION = Pattern.compile("@GrpcService\\b");
    // Matches patterns like "extends FooGrpc.FooImplBase" produced by the gRPC Java code generator.
    // The class name ends in "Grpc" followed by a dot and an inner class ending in "ImplBase".
    private static final Pattern GRPC_IMPL_BASE = Pattern.compile(
            "\\bextends\\b[^{;]*Grpc\\.[^{;]*ImplBase\\b");

    // ── GraphQL annotation patterns ──────────────────────────────────────────
    private static final Pattern GRAPHQL_MAPPING = Pattern.compile(
            "@(QueryMapping|MutationMapping|SubscriptionMapping|SchemaMapping|GraphQlRepository)\\b");

    // ── Spring configuration annotations ────────────────────────────────────
    private static final Pattern SPRING_BOOT_APPLICATION = Pattern.compile(
            "@SpringBootApplication\\b");
    private static final Pattern CONFIGURATION = Pattern.compile("@Configuration\\b");

    // ── CommandLineRunner / ApplicationRunner (on class declaration) ─────────
    private static final Pattern CLI_RUNNER = Pattern.compile(
            "\\bimplements\\b[^{;]*\\bCommandLineRunner\\b");
    private static final Pattern APP_RUNNER = Pattern.compile(
            "\\bimplements\\b[^{;]*\\bApplicationRunner\\b");

    // ── Helper: extract method or class name from a declaration line ─────────
    private static final Pattern DECLARATION_NAME = Pattern.compile(
            "(?:public|protected|private|static|final|abstract|synchronized|default|"
            + "native|strictfp)?\\s*(?:(?:public|protected|private|static|final|abstract|"
            + "synchronized|default|native|strictfp)\\s+)*"
            + "(?:[\\w<>\\[\\],$?]+\\s+)+(\\w+)\\s*[({]");

    // ── Helper: extract quoted value from annotation (e.g. path) ────────────
    private static final Pattern ANNOTATION_QUOTED_VALUE = Pattern.compile("\"([^\"]+)\"");

    @Override
    public String getName() {
        return "Entry Point Analyzer";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null || !Files.exists(rootPath)) {
            return issues;
        }

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> analyzeFileInternal(p, issues));
        } catch (IOException e) {
            LOGGER.error("Failed to scan project files at {}", rootPath, e);
        }
        return issues;
    }

    @Override
    public boolean canAnalyzeSingleFile() {
        return true;
    }

    @Override
    public List<StaticIssue> analyzeFile(Path filePath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (filePath != null && Files.isRegularFile(filePath)
                && filePath.toString().endsWith(".java")) {
            analyzeFileInternal(filePath, issues);
        }
        return issues;
    }

    private void analyzeFileInternal(Path file, List<StaticIssue> issues) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Cannot read {}", file, e);
            return;
        }

        List<String> annotationBuffer = new ArrayList<>();
        int bufferStartLine = 0;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            int lineNum = i + 1;

            if (trimmed.isEmpty()) {
                continue; // blank lines do not break the annotation buffer
            }

            if (trimmed.startsWith("@")) {
                // Accumulate annotation lines
                if (annotationBuffer.isEmpty()) {
                    bufferStartLine = lineNum;
                }
                annotationBuffer.add(trimmed);
            } else {
                // Non-annotation line: process with any accumulated annotations
                processLine(file, lineNum, trimmed, annotationBuffer, bufferStartLine, issues);
                annotationBuffer.clear();
                bufferStartLine = 0;
            }
        }
    }

    /**
     * Inspects a non-annotation source line together with the annotations that immediately
     * preceded it and emits any detected entry-point issues.
     */
    private void processLine(Path file, int lineNum, String line, List<String> annotations,
            int bufferStartLine, List<StaticIssue> issues) {

        int reportLine = bufferStartLine > 0 ? bufferStartLine : lineNum;

        // ── Main method (detected directly on the declaration line) ──────────
        if (MAIN_METHOD.matcher(line).find()) {
            issues.add(createIssue(file, reportLine,
                    "Entry point detected: Main Method",
                    "ENTRY_POINT_MAIN_METHOD",
                    "The application entry point via public static void main(String[]).",
                    Arrays.asList("entry-point", "main-method")));
        }

        // ── CommandLineRunner (detected from the implements clause) ──────────
        if (CLI_RUNNER.matcher(line).find()) {
            issues.add(createIssue(file, reportLine,
                    "Entry point detected: CommandLineRunner",
                    "ENTRY_POINT_CLI_RUNNER",
                    "Class implements CommandLineRunner; the run(String...) method is an "
                    + "application entry point.",
                    Arrays.asList("entry-point", "cli-runner", "spring")));
        }

        // ── ApplicationRunner (detected from the implements clause) ──────────
        if (APP_RUNNER.matcher(line).find()) {
            issues.add(createIssue(file, reportLine,
                    "Entry point detected: ApplicationRunner",
                    "ENTRY_POINT_APP_RUNNER",
                    "Class implements ApplicationRunner; the run(ApplicationArguments) method "
                    + "is an application entry point.",
                    Arrays.asList("entry-point", "app-runner", "spring")));
        }

        // ── gRPC via generated ImplBase extension ────────────────────────────
        if (GRPC_IMPL_BASE.matcher(line).find()) {
            issues.add(createIssue(file, reportLine,
                    "Entry point detected: gRPC Service (ImplBase)",
                    "ENTRY_POINT_GRPC_IMPL",
                    "Class extends a gRPC-generated *ImplBase stub, exposing gRPC service "
                    + "endpoints.",
                    Arrays.asList("entry-point", "grpc")));
        }

        // ── Annotation-based entry points ─────────────────────────────────────
        for (String annotation : annotations) {

            // REST endpoint methods
            Matcher restMatcher = REST_MAPPING.matcher(annotation);
            if (restMatcher.find()) {
                String verb = restMatcher.group(1).toUpperCase();
                String path = extractAnnotationPath(annotation);
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: REST API [" + verb + "]"
                        + (path.isEmpty() ? "" : " " + path)
                        + (name.isEmpty() ? "" : " → " + name + "()"),
                        "ENTRY_POINT_REST_API",
                        "Method is mapped to a REST API endpoint via @" + verb + "Mapping.",
                        Arrays.asList("entry-point", "rest-api", "spring")));
            }

            // REST controller class
            Matcher controllerMatcher = REST_CONTROLLER.matcher(annotation);
            if (controllerMatcher.find()) {
                String annotationType = controllerMatcher.group(1);
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: REST Controller"
                        + (name.isEmpty() ? "" : " " + name),
                        "ENTRY_POINT_REST_CONTROLLER",
                        "Class is annotated with @" + annotationType
                        + ", acting as a Spring MVC/REST controller.",
                        Arrays.asList("entry-point", "rest-api", "spring")));
            }

            // Kafka listener
            if (KAFKA_LISTENER.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: Kafka Message Listener"
                        + (name.isEmpty() ? "" : " " + name + "()"),
                        "ENTRY_POINT_KAFKA_LISTENER",
                        "Method is a Kafka message listener via @KafkaListener.",
                        Arrays.asList("entry-point", "kafka", "messaging")));
            }

            // RabbitMQ listener
            if (RABBIT_LISTENER.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: RabbitMQ Message Listener"
                        + (name.isEmpty() ? "" : " " + name + "()"),
                        "ENTRY_POINT_RABBIT_LISTENER",
                        "Method is a RabbitMQ message listener via @RabbitListener.",
                        Arrays.asList("entry-point", "rabbitmq", "messaging")));
            }

            // SQS listener
            if (SQS_LISTENER.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: SQS Message Listener"
                        + (name.isEmpty() ? "" : " " + name + "()"),
                        "ENTRY_POINT_SQS_LISTENER",
                        "Method is an AWS SQS message listener via @SqsListener.",
                        Arrays.asList("entry-point", "sqs", "messaging")));
            }

            // JMS listener
            if (JMS_LISTENER.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: JMS Message Listener"
                        + (name.isEmpty() ? "" : " " + name + "()"),
                        "ENTRY_POINT_JMS_LISTENER",
                        "Method is a JMS message listener via @JmsListener.",
                        Arrays.asList("entry-point", "jms", "messaging")));
            }

            // Scheduled / cron job
            if (SCHEDULED.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: Scheduled Job"
                        + (name.isEmpty() ? "" : " " + name + "()"),
                        "ENTRY_POINT_SCHEDULED",
                        "Method is a scheduled task via @Scheduled (cron or fixed-rate).",
                        Arrays.asList("entry-point", "scheduled", "cron")));
            }

            // gRPC service (annotation)
            if (GRPC_SERVICE_ANNOTATION.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: gRPC Service"
                        + (name.isEmpty() ? "" : " " + name),
                        "ENTRY_POINT_GRPC_SERVICE",
                        "Class is annotated with @GrpcService, exposing gRPC endpoints.",
                        Arrays.asList("entry-point", "grpc")));
            }

            // GraphQL endpoint
            Matcher graphqlMatcher = GRAPHQL_MAPPING.matcher(annotation);
            if (graphqlMatcher.find()) {
                String annotationType = graphqlMatcher.group(1);
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: GraphQL " + annotationType
                        + (name.isEmpty() ? "" : " " + name + "()"),
                        "ENTRY_POINT_GRAPHQL",
                        "Method/class is a GraphQL endpoint via @" + annotationType + ".",
                        Arrays.asList("entry-point", "graphql")));
            }

            // Spring Boot application
            if (SPRING_BOOT_APPLICATION.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: Spring Boot Application"
                        + (name.isEmpty() ? "" : " " + name),
                        "ENTRY_POINT_SPRING_BOOT_APP",
                        "Class annotated with @SpringBootApplication is the Spring Boot "
                        + "application entry point.",
                        Arrays.asList("entry-point", "spring-boot", "configuration")));
            }

            // Spring configuration class
            if (CONFIGURATION.matcher(annotation).find()) {
                String name = extractDeclarationName(line);
                issues.add(createIssue(file, reportLine,
                        "Entry point detected: Spring Configuration Class"
                        + (name.isEmpty() ? "" : " " + name),
                        "ENTRY_POINT_CONFIGURATION",
                        "Class annotated with @Configuration provides Spring beans and "
                        + "configuration.",
                        Arrays.asList("entry-point", "configuration", "spring")));
            }
        }
    }

    /** Extracts the first quoted string from an annotation, used to retrieve path values. */
    private static String extractAnnotationPath(String annotation) {
        Matcher m = ANNOTATION_QUOTED_VALUE.matcher(annotation);
        return m.find() ? m.group(1) : "";
    }

    /** Extracts the declared name (class or method) from a declaration line. */
    private static String extractDeclarationName(String line) {
        Matcher m = DECLARATION_NAME.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private StaticIssue createIssue(Path file, int lineNum, String description, String ruleId,
            String suggestedFix, List<String> tags) {
        return new StaticIssue(
                file,
                lineNum,
                description,
                "Info",
                "entry-point",
                ruleId,
                getName(),
                0.85,
                "none",
                suggestedFix,
                "none",
                tags,
                "open"
        );
    }

    @Override
    public String toString() {
        return getName();
    }
}
