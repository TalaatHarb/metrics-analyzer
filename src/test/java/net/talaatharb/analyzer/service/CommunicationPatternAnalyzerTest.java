package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationPatternAnalyzerTest {

    private final CommunicationPatternAnalyzer analyzer = new CommunicationPatternAnalyzer();

    @Test
    void shouldHaveCorrectName() {
        assertEquals("Communication Pattern Analyzer", analyzer.getName());
    }

    @Test
    void shouldSupportSingleFileAnalysis() {
        assertTrue(analyzer.canAnalyzeSingleFile());
    }

    @Test
    void shouldReturnEmptyForNullOrMissingRoot() {
        assertTrue(analyzer.analyzeProject(null).isEmpty());
        assertTrue(analyzer.analyzeProject(Path.of("nonexistent-dir-xyz")).isEmpty());
    }

    @Test
    void shouldDetectRestTemplateHttpCall(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ApiClient.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class ApiClient {",
                "    private final RestTemplate restTemplate = new RestTemplate();",
                "    String fetch() {",
                "        return restTemplate.getForObject(\"http://inventory/api/items\", String.class);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_HTTP_REST_TEMPLATE".equals(issue.getRuleId())
                        && issue.getDescription().contains("getForObject")
                        && issue.getDescription().contains("inventory/api/items")));
    }

    @Test
    void shouldDetectFeignClientAnnotation(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("CatalogClient.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "@FeignClient(name = \"catalog\")",
                "interface CatalogClient {",
                "    String getCatalog();",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_FEIGN_CLIENT".equals(issue.getRuleId())
                        && issue.getDescription().contains("@FeignClient")));
    }

    @Test
    void shouldDetectGraphQlAndGrpcClients(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("GatewayClient.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class GatewayClient {",
                "    void callClients() {",
                "        graphQlClient.document(\"query { status }\").execute();",
                "        ManagedChannelBuilder.forAddress(\"orders\", 9090).usePlaintext().build();",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_GRAPHQL_CLIENT".equals(issue.getRuleId())
                        && issue.getDescription().contains("document")));
        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_GRPC_CLIENT".equals(issue.getRuleId())
                        && issue.getDescription().contains("forAddress")));
    }

    @Test
    void shouldDetectMessagingTemplates(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Publisher.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Publisher {",
                "    void publish() {",
                "        kafkaTemplate.send(\"orders\", payload);",
                "        jmsTemplate.convertAndSend(\"billing.queue\", payload);",
                "        rabbitTemplate.convertAndSend(\"exchange\", \"route\", payload);",
                "        sqsTemplate.send(to -> to.queue(\"jobs\").payload(payload));",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_KAFKA".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_JMS".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_RABBITMQ".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_SQS".equals(issue.getRuleId())));
    }

    @Test
    void shouldDetectDatabaseAccess(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("OrderRepository.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class OrderRepository {",
                "    void save(Order order) {",
                "        jdbcTemplate.update(\"insert into orders(id) values (?)\", order.id());",
                "        entityManager.persist(order);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_DATABASE".equals(issue.getRuleId())
                        && issue.getDescription().contains("update")));
        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_DATABASE".equals(issue.getRuleId())
                        && issue.getDescription().contains("persist")));
    }

    @Test
    void shouldDetectSocketCommunication(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("SocketBridge.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class SocketBridge {",
                "    void bridge() throws Exception {",
                "        new Socket(\"localhost\", 8080);",
                "        new DatagramSocket();",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_TCP_SOCKET".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_UDP_SOCKET".equals(issue.getRuleId())));
    }

    @Test
    void shouldDetectAsyncSharedMemoryAndExternalProcesses(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("WorkerCoordinator.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class WorkerCoordinator {",
                "    @Async",
                "    void dispatch() throws Exception {",
                "        executorService.submit(() -> process());",
                "        CompletableFuture.runAsync(() -> process());",
                "        queue.put(payload);",
                "        new ProcessBuilder(\"python\", \"worker.py\").start();",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_ASYNC_ANNOTATION".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_ASYNC_EXECUTION".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_SHARED_MEMORY".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_EXTERNAL_PROCESS".equals(issue.getRuleId())));
    }

    @Test
    void shouldDetectScheduledExecutorVariant(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Scheduler.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Scheduler {",
                "    void schedule() {",
                "        scheduledExecutorService.scheduleAtFixedRate(this::runJob, 0, 10, TimeUnit.SECONDS);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_ASYNC_EXECUTION".equals(issue.getRuleId())
                        && issue.getDescription().contains("scheduleAtFixedRate")));
    }

    @Test
    void shouldScanEntireProject(@TempDir Path tempDir) throws Exception {
        Path sub = tempDir.resolve("com/example");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("HttpClientAdapter.java"), String.join(System.lineSeparator(),
                "class HttpClientAdapter {",
                "    void call() throws Exception {",
                "        httpClient.send(request, handler);",
                "    }",
                "}"
        ));
        Files.writeString(sub.resolve("Worker.java"), String.join(System.lineSeparator(),
                "class Worker {",
                "    void run() {",
                "        new Thread(() -> work()).start();",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_HTTP_CLIENT".equals(issue.getRuleId())));
        assertTrue(issues.stream().anyMatch(issue -> "COMM_PATTERN_ASYNC_EXECUTION".equals(issue.getRuleId())));
    }

    @Test
    void issuesShouldHaveInfoSeverityAndCommunicationCategory(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Client.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class Client {",
                "    void call() {",
                "        restTemplate.exchange(\"http://example\", HttpMethod.GET, null, String.class);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertFalse(issues.isEmpty());
        issues.forEach(issue -> {
            assertEquals("Info", issue.getSeverity());
            assertEquals("communication-pattern", issue.getCategory());
            assertEquals("Communication Pattern Analyzer", issue.getTool());
            assertFalse(issue.getTags().isEmpty());
        });
    }

    @Test
    void shouldPreserveAnnotationBufferAcrossComments(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("AsyncWorker.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "class AsyncWorker {",
                "    @Async",
                "    // dispatched on a task executor",
                "    void runTask() {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_ASYNC_ANNOTATION".equals(issue.getRuleId())
                        && issue.getDescription().contains("runTask")));
    }

    @Test
    void shouldIncludeRecordNameInDetectedLocation(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("RecordClient.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "record RecordClient(String baseUrl) {",
                "    void fetch() {",
                "        restTemplate.exchange(\"http://example\", HttpMethod.GET, null, String.class);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(issue ->
                "COMM_PATTERN_HTTP_REST_TEMPLATE".equals(issue.getRuleId())
                        && issue.getDescription().contains("RecordClient.fetch()")));
    }
}
