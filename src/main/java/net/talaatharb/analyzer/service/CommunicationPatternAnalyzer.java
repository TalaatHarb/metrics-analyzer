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
 * Detects common communication and independently running integration patterns in Java source code.
 */
public class CommunicationPatternAnalyzer implements StaticAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommunicationPatternAnalyzer.class);

    private static final Pattern TYPE_DECLARATION = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)\\b");
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?:(?:public|protected|private|static|final|abstract|synchronized|default|native|strictfp)\\s+)*"
                    + "(?:<[^>]+>\\s+)?[\\w<>\\[\\],.?$]+\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:\\{|throws\\b)"
    );
    private static final Pattern QUOTED_VALUE = Pattern.compile("\"([^\"]+)\"");

    private static final Pattern FEIGN_CLIENT = Pattern.compile("@FeignClient\\b");
    private static final Pattern ASYNC_ANNOTATION = Pattern.compile("@Async\\b");

    private static final Pattern REST_TEMPLATE = Pattern.compile(
            "\\b\\w*restTemplate\\s*\\.\\s*(getForObject|getForEntity|postForObject|postForEntity|exchange|put|delete|patchForObject)\\s*\\("
    );
    private static final Pattern WEB_CLIENT = Pattern.compile(
            "\\b\\w*webClient\\s*\\.\\s*(get|post|put|delete|method)\\s*\\("
    );
    private static final Pattern HTTP_CLIENT = Pattern.compile(
            "\\b\\w*httpClient\\s*\\.\\s*(send|sendAsync|execute)\\s*\\("
    );
    private static final Pattern GRAPHQL_CLIENT = Pattern.compile(
            "\\b(?:HttpGraphQlClient|WebClientGraphQlClient|RestClientGraphQlClient|GraphQlClient)\\b"
                    + "|\\b\\w*graphQlClient\\s*\\.\\s*(document|execute|retrieve)\\s*\\("
    );
    private static final Pattern GRPC_CLIENT = Pattern.compile(
            "\\bManagedChannelBuilder\\s*\\.\\s*(forAddress|forTarget)\\s*\\("
                    + "|\\b\\w+Grpc\\s*\\.\\s*new(?:Blocking|Future)?Stub\\s*\\("
    );
    private static final Pattern JMS_TEMPLATE = Pattern.compile(
            "\\b\\w*jmsTemplate\\s*\\.\\s*(send|convertAndSend|receive|receiveSelected|browse)\\s*\\("
    );
    private static final Pattern KAFKA_TEMPLATE = Pattern.compile("\\b\\w*kafkaTemplate\\s*\\.\\s*send\\s*\\(");
    private static final Pattern RABBIT_TEMPLATE = Pattern.compile(
            "\\b\\w*rabbitTemplate\\s*\\.\\s*(send|convertAndSend|convertSendAndReceive|receive)\\s*\\("
    );
    private static final Pattern SQS_TEMPLATE = Pattern.compile(
            "\\b\\w*sqsTemplate\\s*\\.\\s*(send|receive|sendMany|receiveMany)\\s*\\("
    );
    private static final Pattern DATABASE_ACCESS = Pattern.compile(
            "\\b\\w*(?:jdbcTemplate|namedParameterJdbcTemplate)\\s*\\.\\s*(query|update|execute|batchUpdate|queryForObject|queryForList)\\s*\\("
                    + "|\\b\\w*entityManager\\s*\\.\\s*(createQuery|createNativeQuery|persist|merge|find|remove)\\s*\\("
                    + "|\\b\\w*dataSource\\s*\\.\\s*getConnection\\s*\\("
    );
    private static final Pattern TCP_SOCKET = Pattern.compile(
            "\\bnew\\s+Socket\\s*\\(|\\bSocketChannel\\s*\\.\\s*open\\s*\\("
    );
    private static final Pattern UDP_SOCKET = Pattern.compile(
            "\\bnew\\s+DatagramSocket\\s*\\(|\\bDatagramChannel\\s*\\.\\s*open\\s*\\("
    );
    private static final Pattern THREAD_OR_EXECUTOR = Pattern.compile(
            "\\bnew\\s+Thread\\s*\\("
                    + "|\\b\\w*[eE]xecutor(?:[sS]ervice)?\\s*\\.\\s*(submit|execute|invokeAll|invokeAny|schedule|scheduleAtFixedRate|scheduleWithFixedDelay)\\s*\\("
                    + "|\\bCompletableFuture\\s*\\.\\s*(runAsync|supplyAsync)\\s*\\("
    );
    private static final Pattern SHARED_MEMORY = Pattern.compile(
            "\\bnew\\s+(?:ArrayBlockingQueue|LinkedBlockingQueue|PriorityBlockingQueue|SynchronousQueue|ConcurrentLinkedQueue|LinkedTransferQueue|DelayQueue|ConcurrentLinkedDeque)\\s*\\("
                    + "|\\b\\w*(?:queue|deque|buffer)\\s*\\.\\s*(put|take|offer|poll|transfer)\\s*\\("
    );
    private static final Pattern EXTERNAL_PROCESS = Pattern.compile(
            "\\bnew\\s+ProcessBuilder\\s*\\(|\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\)\\s*\\.\\s*exec\\s*\\("
    );

    private static final double COMMUNICATION_PATTERN_CONFIDENCE = 0.85;

    @Override
    public String getName() {
        return "Communication Pattern Analyzer";
    }

    @Override
    public List<StaticIssue> analyzeProject(Path rootPath) {
        List<StaticIssue> issues = new ArrayList<>();
        if (rootPath == null || !Files.exists(rootPath)) {
            return issues;
        }

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> analyzeFileInternal(path, issues));
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
        if (filePath != null && Files.isRegularFile(filePath) && filePath.toString().endsWith(".java")) {
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
        ScanContext context = new ScanContext();
        boolean insideBufferedBlockComment = false;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            int lineNum = i + 1;

            if (insideBufferedBlockComment) {
                if (trimmed.contains("*/")) {
                    insideBufferedBlockComment = false;
                    annotationBuffer.clear();
                    bufferStartLine = 0;
                }
                continue;
            }

            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("@")) {
                if (annotationBuffer.isEmpty()) {
                    bufferStartLine = lineNum;
                }
                annotationBuffer.add(trimmed);
                continue;
            }

            if (!annotationBuffer.isEmpty() && trimmed.startsWith("//")) {
                continue;
            }

            if (!annotationBuffer.isEmpty() && trimmed.startsWith("/*")) {
                if (trimmed.contains("*/")) {
                    continue;
                }
                insideBufferedBlockComment = true;
                continue;
            }

            processLine(file, lineNum, trimmed, annotationBuffer, bufferStartLine, context, issues);
            annotationBuffer.clear();
            bufferStartLine = 0;
        }

        if (!annotationBuffer.isEmpty()) {
            processLine(file, lines.size(), "", annotationBuffer, bufferStartLine, context, issues);
        }
    }

    private void processLine(Path file, int lineNum, String line, List<String> annotations,
            int bufferStartLine, ScanContext context, List<StaticIssue> issues) {
        int reportLine = bufferStartLine > 0 ? bufferStartLine : lineNum;
        String declaredType = extractDeclaredType(line);
        String declaredMethod = extractDeclaredMethod(line);
        ScanContext declarationContext = context.copy();
        if (!declaredType.isBlank()) {
            declarationContext.currentType = declaredType;
            declarationContext.currentMethod = "";
        }
        if (!declaredMethod.isBlank()) {
            declarationContext.currentMethod = declaredMethod;
        }

        for (String annotation : annotations) {
            if (FEIGN_CLIENT.matcher(annotation).find()) {
                issues.add(createIssue(file, reportLine,
                        "Communication pattern detected: HTTP client via @FeignClient"
                                + formatLocation(declarationContext),
                        "COMM_PATTERN_FEIGN_CLIENT",
                        "Class declares an external HTTP client through a Feign interface.",
                        Arrays.asList("communication", "http", "feign", "external-service")));
            }

            if (ASYNC_ANNOTATION.matcher(annotation).find()) {
                issues.add(createIssue(file, reportLine,
                        "Communication pattern detected: Independent async execution via @Async"
                                + formatLocation(declarationContext),
                        "COMM_PATTERN_ASYNC_ANNOTATION",
                        "Method executes independently on an async executor and typically communicates through futures, callbacks, or shared state.",
                        Arrays.asList("communication", "async", "threading", "shared-memory")));
            }
        }

        emitCallIssue(file, reportLine, line, context, issues, REST_TEMPLATE,
                "HTTP client via RestTemplate.%s",
                "COMM_PATTERN_HTTP_REST_TEMPLATE",
                "Code performs outbound HTTP communication using Spring RestTemplate.",
                Arrays.asList("communication", "http", "resttemplate", "external-service"),
                true);
        emitCallIssue(file, reportLine, line, context, issues, WEB_CLIENT,
                "HTTP client via WebClient.%s",
                "COMM_PATTERN_HTTP_WEBCLIENT",
                "Code performs outbound HTTP communication using Spring WebClient.",
                Arrays.asList("communication", "http", "webclient", "external-service"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, HTTP_CLIENT,
                "HTTP client via HttpClient.%s",
                "COMM_PATTERN_HTTP_CLIENT",
                "Code performs outbound HTTP communication using an HttpClient-style API.",
                Arrays.asList("communication", "http", "client", "external-service"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, GRAPHQL_CLIENT,
                "GraphQL client communication via %s",
                "COMM_PATTERN_GRAPHQL_CLIENT",
                "Code communicates with a GraphQL endpoint from the application.",
                Arrays.asList("communication", "graphql", "external-service"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, GRPC_CLIENT,
                "gRPC client communication via %s",
                "COMM_PATTERN_GRPC_CLIENT",
                "Code communicates with another service over gRPC.",
                Arrays.asList("communication", "grpc", "external-service"),
                true);
        emitCallIssue(file, reportLine, line, context, issues, JMS_TEMPLATE,
                "JMS messaging via JmsTemplate.%s",
                "COMM_PATTERN_JMS",
                "Code exchanges messages through JMS queues or topics.",
                Arrays.asList("communication", "jms", "messaging", "queue"),
                true);
        emitCallIssue(file, reportLine, line, context, issues, KAFKA_TEMPLATE,
                "Kafka messaging via KafkaTemplate.%s",
                "COMM_PATTERN_KAFKA",
                "Code publishes messages to Kafka.",
                Arrays.asList("communication", "kafka", "messaging", "eventing"),
                true);
        emitCallIssue(file, reportLine, line, context, issues, RABBIT_TEMPLATE,
                "RabbitMQ messaging via RabbitTemplate.%s",
                "COMM_PATTERN_RABBITMQ",
                "Code exchanges messages with RabbitMQ.",
                Arrays.asList("communication", "rabbitmq", "messaging", "queue"),
                true);
        emitCallIssue(file, reportLine, line, context, issues, SQS_TEMPLATE,
                "SQS messaging via SqsTemplate.%s",
                "COMM_PATTERN_SQS",
                "Code exchanges messages with Amazon SQS.",
                Arrays.asList("communication", "sqs", "messaging", "queue"),
                true);
        emitCallIssue(file, reportLine, line, context, issues, DATABASE_ACCESS,
                "Shared database access via %s",
                "COMM_PATTERN_DATABASE",
                "Code communicates through a shared database or direct SQL/JPA access.",
                Arrays.asList("communication", "database", "shared-database", "persistence"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, TCP_SOCKET,
                "TCP socket communication via %s",
                "COMM_PATTERN_TCP_SOCKET",
                "Code opens direct TCP socket communication.",
                Arrays.asList("communication", "tcp", "socket", "network"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, UDP_SOCKET,
                "UDP socket communication via %s",
                "COMM_PATTERN_UDP_SOCKET",
                "Code opens direct UDP socket communication.",
                Arrays.asList("communication", "udp", "socket", "network"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, THREAD_OR_EXECUTOR,
                "Independent execution via %s",
                "COMM_PATTERN_ASYNC_EXECUTION",
                "Code starts work that can run independently within the application process.",
                Arrays.asList("communication", "async", "threading", "independent-execution"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, SHARED_MEMORY,
                "Shared-memory coordination via %s",
                "COMM_PATTERN_SHARED_MEMORY",
                "Code coordinates work through in-memory queues or buffers shared between threads.",
                Arrays.asList("communication", "shared-memory", "queue", "threading"),
                false);
        emitCallIssue(file, reportLine, line, context, issues, EXTERNAL_PROCESS,
                "External process execution via %s",
                "COMM_PATTERN_EXTERNAL_PROCESS",
                "Code launches a process outside the application JVM and may communicate through command arguments, files, or process I/O streams.",
                Arrays.asList("communication", "process", "external-process", "integration"),
                false);

        updateContext(context, declaredType, declaredMethod);
    }

    private void updateContext(ScanContext context, String declaredType, String declaredMethod) {
        if (!declaredType.isBlank()) {
            context.currentType = declaredType;
            context.currentMethod = "";
        }
        if (!declaredMethod.isBlank()) {
            context.currentMethod = declaredMethod;
        }
    }

    private void emitCallIssue(Path file, int reportLine, String line, ScanContext context,
            List<StaticIssue> issues, Pattern pattern, String titleTemplate, String ruleId,
            String suggestedFix, List<String> tags, boolean includeQuotedValue) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            return;
        }

        String operation = firstNonBlankGroup(matcher);
        String description = "Communication pattern detected: "
                + String.format(titleTemplate, operation)
                + formatLocation(context)
                + formatQuotedValue(line, includeQuotedValue);
        issues.add(createIssue(file, reportLine, description, ruleId, suggestedFix, tags));
    }

    private String extractDeclaredType(String line) {
        Matcher typeMatcher = TYPE_DECLARATION.matcher(line);
        return typeMatcher.find() ? typeMatcher.group(1) : "";
    }

    private String extractDeclaredMethod(String line) {
        Matcher methodMatcher = METHOD_DECLARATION.matcher(line);
        return methodMatcher.find() ? methodMatcher.group(1) : "";
    }

    private String firstNonBlankGroup(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return matcher.group().replaceAll("\\s+", " ").trim();
    }

    private String formatLocation(ScanContext context) {
        StringBuilder builder = new StringBuilder();
        if (context.currentType != null && !context.currentType.isBlank()) {
            builder.append(" in ").append(context.currentType);
        }
        if (context.currentMethod != null && !context.currentMethod.isBlank()) {
            builder.append(".").append(context.currentMethod).append("()");
        }
        return builder.toString();
    }

    private String formatQuotedValue(String line, boolean includeQuotedValue) {
        if (!includeQuotedValue) {
            return "";
        }
        String value = extractSingleQuotedValue(line);
        if (value.isEmpty()) {
            return "";
        }
        return " → " + value;
    }

    private String extractSingleQuotedValue(String line) {
        Matcher matcher = QUOTED_VALUE.matcher(line);
        String value = "";
        int count = 0;
        while (matcher.find()) {
            value = matcher.group(1);
            count++;
            if (count > 1) {
                return "";
            }
        }
        return count == 1 ? value : "";
    }

    private StaticIssue createIssue(Path file, int lineNum, String description, String ruleId,
            String suggestedFix, List<String> tags) {
        return new StaticIssue(
                file,
                lineNum,
                description,
                "Info",
                "communication-pattern",
                ruleId,
                getName(),
                COMMUNICATION_PATTERN_CONFIDENCE,
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

    private static final class ScanContext {
        private String currentType = "";
        private String currentMethod = "";

        private ScanContext copy() {
            ScanContext copy = new ScanContext();
            copy.currentType = currentType;
            copy.currentMethod = currentMethod;
            return copy;
        }
    }
}
