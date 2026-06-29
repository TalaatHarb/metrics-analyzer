package com.example.sample;

import java.util.ArrayList;
import java.util.List;
import java.io.FileWriter;

/**
 * Example: Poorly-designed God Class (LOW cohesion, HIGH coupling)
 */
public class OrderManager {
    private List<String> orders;
    private String currentUser;
    private double totalAmount;
    private String database;
    private int logLevel;
    private FileWriter fileWriter;

    public OrderManager() {
        this.orders = new ArrayList<>();
    }

    // Manages orders
    public void addOrder(String order) {
        orders.add(order);
        logOrder(order);
    }

    public List<String> getOrders() {
        return orders;
    }

    // Manages users
    public void setCurrentUser(String user) {
        this.currentUser = user;
        logUser(user);
    }

    public String getCurrentUser() {
        return currentUser;
    }

    // Manages payment
    public void processPayment(double amount) {
        this.totalAmount += amount;
        // Coupling: knows about database
        saveToDatabase("INSERT INTO payments VALUES (" + amount + ")");
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    // Manages logging
    private void logOrder(String order) {
        // Unrelated responsibility
        System.out.println("[LOG] Order: " + order);
    }

    private void logUser(String user) {
        System.out.println("[LOG] User: " + user);
    }

    // Manages database (coupling)
    private void saveToDatabase(String sql) {
        System.out.println("Executing: " + sql);
    }

    // Manages file I/O (coupling)
    public void exportToFile(String filename) throws Exception {
        fileWriter = new FileWriter(filename);
        fileWriter.write("Orders: " + orders.toString());
        fileWriter.close();
    }
}
