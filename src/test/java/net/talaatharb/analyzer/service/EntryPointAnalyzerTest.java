package net.talaatharb.analyzer.service;

import net.talaatharb.analyzer.model.StaticIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntryPointAnalyzerTest {

    private final EntryPointAnalyzer analyzer = new EntryPointAnalyzer();

    // ── Basics ───────────────────────────────────────────────────────────────

    @Test
    void shouldHaveCorrectName() {
        assertEquals("Entry Point Analyzer", analyzer.getName());
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
    void shouldReturnEmptyForNonJavaFile(@TempDir Path tempDir) throws Exception {
        Path txt = tempDir.resolve("notes.txt");
        Files.writeString(txt, "public static void main(String[] args) {}");
        assertTrue(analyzer.analyzeFile(txt).isEmpty());
    }

    // ── Main method ──────────────────────────────────────────────────────────

    @Test
    void shouldDetectMainMethod(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("App.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class App {",
                "    public static void main(String[] args) {",
                "        System.out.println(\"hello\");",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_MAIN_METHOD".equals(i.getRuleId()) &&
                i.getDescription().contains("Main Method")));
    }

    // ── REST API ─────────────────────────────────────────────────────────────

    @Test
    void shouldDetectGetMappingEndpoint(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("UserController.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import org.springframework.web.bind.annotation.*;",
                "public class UserController {",
                "    @GetMapping(\"/users\")",
                "    public List<User> getUsers() { return List.of(); }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_REST_API".equals(i.getRuleId()) &&
                i.getDescription().contains("GET") &&
                i.getDescription().contains("/users")));
    }

    @Test
    void shouldDetectPostMappingEndpoint(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("OrderController.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class OrderController {",
                "    @PostMapping(\"/orders\")",
                "    public Order createOrder(Order o) { return o; }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_REST_API".equals(i.getRuleId()) &&
                i.getDescription().contains("POST")));
    }

    @Test
    void shouldDetectPutDeletePatchEndpoints(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ItemController.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class ItemController {",
                "    @PutMapping(\"/items/{id}\")",
                "    public Item update(Item i) { return i; }",
                "    @DeleteMapping(\"/items/{id}\")",
                "    public void delete(long id) {}",
                "    @PatchMapping(\"/items/{id}\")",
                "    public Item patch(Item i) { return i; }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_REST_API".equals(i.getRuleId())
                && i.getDescription().contains("PUT")));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_REST_API".equals(i.getRuleId())
                && i.getDescription().contains("DELETE")));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_REST_API".equals(i.getRuleId())
                && i.getDescription().contains("PATCH")));
    }

    @Test
    void shouldDetectRestController(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("HelloController.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "@RestController",
                "public class HelloController {}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_REST_CONTROLLER".equals(i.getRuleId())));
    }

    // ── CLI Runners ──────────────────────────────────────────────────────────

    @Test
    void shouldDetectCommandLineRunner(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("StartupRunner.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import org.springframework.boot.CommandLineRunner;",
                "public class StartupRunner implements CommandLineRunner {",
                "    @Override",
                "    public void run(String... args) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_CLI_RUNNER".equals(i.getRuleId())));
    }

    @Test
    void shouldDetectApplicationRunner(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("AppRunner.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "import org.springframework.boot.ApplicationRunner;",
                "public class AppRunner implements ApplicationRunner {",
                "    @Override",
                "    public void run(ApplicationArguments args) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_APP_RUNNER".equals(i.getRuleId())));
    }

    // ── Message-queue listeners ───────────────────────────────────────────────

    @Test
    void shouldDetectKafkaListener(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("OrderConsumer.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class OrderConsumer {",
                "    @KafkaListener(topics = \"orders\")",
                "    public void consume(String message) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_KAFKA_LISTENER".equals(i.getRuleId()) &&
                i.getDescription().contains("Kafka")));
    }

    @Test
    void shouldDetectRabbitListener(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("RabbitConsumer.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class RabbitConsumer {",
                "    @RabbitListener(queues = \"q1\")",
                "    public void onMessage(String msg) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_RABBIT_LISTENER".equals(i.getRuleId())));
    }

    @Test
    void shouldDetectSqsListener(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("SqsConsumer.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class SqsConsumer {",
                "    @SqsListener(\"my-queue\")",
                "    public void handle(String payload) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_SQS_LISTENER".equals(i.getRuleId())));
    }

    @Test
    void shouldDetectJmsListener(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("JmsConsumer.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class JmsConsumer {",
                "    @JmsListener(destination = \"inbox\")",
                "    public void receive(String msg) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_JMS_LISTENER".equals(i.getRuleId())));
    }

    // ── Scheduled jobs ────────────────────────────────────────────────────────

    @Test
    void shouldDetectScheduledJob(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("CleanupTask.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class CleanupTask {",
                "    @Scheduled(cron = \"0 0 * * * *\")",
                "    public void cleanup() {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_SCHEDULED".equals(i.getRuleId()) &&
                i.getDescription().contains("Scheduled Job")));
    }

    // ── gRPC endpoints ────────────────────────────────────────────────────────

    @Test
    void shouldDetectGrpcServiceAnnotation(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("GreeterService.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "@GrpcService",
                "public class GreeterService extends GreeterGrpc.GreeterImplBase {",
                "    public void sayHello(HelloRequest req, StreamObserver<HelloReply> obs) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_GRPC_SERVICE".equals(i.getRuleId())));
    }

    @Test
    void shouldDetectGrpcImplBaseExtension(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("PaymentService.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class PaymentService extends PaymentGrpc.PaymentImplBase {",
                "    public void charge(ChargeRequest req, StreamObserver<ChargeReply> obs) {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_GRPC_IMPL".equals(i.getRuleId())));
    }

    // ── GraphQL endpoints ─────────────────────────────────────────────────────

    @Test
    void shouldDetectGraphQlQueryMapping(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("ProductResolver.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class ProductResolver {",
                "    @QueryMapping",
                "    public Product productById(String id) { return null; }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_GRAPHQL".equals(i.getRuleId()) &&
                i.getDescription().contains("QueryMapping")));
    }

    @Test
    void shouldDetectGraphQlMutationMapping(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("CartMutation.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class CartMutation {",
                "    @MutationMapping",
                "    public Cart addItem(String productId) { return null; }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_GRAPHQL".equals(i.getRuleId()) &&
                i.getDescription().contains("MutationMapping")));
    }

    @Test
    void shouldDetectGraphQlSubscriptionMapping(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("PriceSubscription.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "public class PriceSubscription {",
                "    @SubscriptionMapping",
                "    public Flux<Price> priceUpdates(String symbol) { return Flux.empty(); }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_GRAPHQL".equals(i.getRuleId()) &&
                i.getDescription().contains("SubscriptionMapping")));
    }

    // ── Spring configuration ──────────────────────────────────────────────────

    @Test
    void shouldDetectSpringBootApplication(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("MyApplication.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "@SpringBootApplication",
                "public class MyApplication {",
                "    public static void main(String[] args) {",
                "        SpringApplication.run(MyApplication.class, args);",
                "    }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_SPRING_BOOT_APP".equals(i.getRuleId())));
    }

    @Test
    void shouldDetectConfigurationClass(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("AppConfig.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "@Configuration",
                "public class AppConfig {",
                "    public DataSource dataSource() { return null; }",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i ->
                "ENTRY_POINT_CONFIGURATION".equals(i.getRuleId())));
    }

    // ── Multiple entry points in one file ─────────────────────────────────────

    @Test
    void shouldDetectMultipleEntryPointsInOneFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Mixed.java");
        Files.writeString(file, String.join(System.lineSeparator(),
                "@SpringBootApplication",
                "public class Mixed implements CommandLineRunner {",
                "    public static void main(String[] args) {}",
                "    @Override",
                "    public void run(String... args) {}",
                "    @GetMapping(\"/ping\")",
                "    public String ping() { return \"pong\"; }",
                "    @Scheduled(fixedRate = 5000)",
                "    public void heartbeat() {}",
                "}"
        ));

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_SPRING_BOOT_APP".equals(i.getRuleId())));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_CLI_RUNNER".equals(i.getRuleId())));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_MAIN_METHOD".equals(i.getRuleId())));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_REST_API".equals(i.getRuleId())));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_SCHEDULED".equals(i.getRuleId())));
    }

    // ── Project-level scan ────────────────────────────────────────────────────

    @Test
    void shouldScanEntireProject(@TempDir Path tempDir) throws Exception {
        Path sub = tempDir.resolve("com/example");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("App.java"), String.join(System.lineSeparator(),
                "@SpringBootApplication",
                "public class App {",
                "    public static void main(String[] args) {}",
                "}"));
        Files.writeString(sub.resolve("Api.java"), String.join(System.lineSeparator(),
                "public class Api {",
                "    @GetMapping(\"/x\")",
                "    public void x() {}",
                "}"));

        List<StaticIssue> issues = analyzer.analyzeProject(tempDir);

        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_SPRING_BOOT_APP".equals(i.getRuleId())));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_MAIN_METHOD".equals(i.getRuleId())));
        assertTrue(issues.stream().anyMatch(i -> "ENTRY_POINT_REST_API".equals(i.getRuleId())));
    }

    // ── Severity and metadata ─────────────────────────────────────────────────

    @Test
    void issuesShouldHaveInfoSeverityAndEntryPointCategory(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Main.java");
        Files.writeString(file, "public class Main { public static void main(String[] args) {} }");

        List<StaticIssue> issues = analyzer.analyzeFile(file);

        assertFalse(issues.isEmpty());
        issues.forEach(i -> {
            assertEquals("Info", i.getSeverity());
            assertEquals("entry-point", i.getCategory());
            assertEquals("Entry Point Analyzer", i.getTool());
            assertFalse(i.getTags().isEmpty());
        });
    }
}
