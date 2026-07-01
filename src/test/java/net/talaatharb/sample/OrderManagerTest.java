package net.talaatharb.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderManagerTest {

    @Test
    void shouldTrackOrdersUserAndTotalAmount() {
        OrderManager manager = new OrderManager();

        manager.addOrder("book");
        manager.addOrder("pen");
        manager.setCurrentUser("alice");
        manager.processPayment(25.50);
        manager.processPayment(4.50);

        assertEquals(2, manager.getOrders().size());
        assertEquals("book", manager.getOrders().get(0));
        assertEquals("pen", manager.getOrders().get(1));
        assertEquals("alice", manager.getCurrentUser());
        assertEquals(30.0, manager.getTotalAmount(), 0.0001);
    }

    @Test
    void shouldExportOrdersToFile(@TempDir Path tempDir) throws Exception {
        OrderManager manager = new OrderManager();
        manager.addOrder("book");
        manager.addOrder("pen");

        Path output = tempDir.resolve("orders.txt");
        manager.exportToFile(output.toString());

        String contents = Files.readString(output);
        assertTrue(contents.contains("book"));
        assertTrue(contents.contains("pen"));
    }
}
