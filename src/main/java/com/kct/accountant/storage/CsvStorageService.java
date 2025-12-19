package com.kct.accountant.storage;

import com.kct.accountant.model.Expense;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CsvStorageService implements StorageService {
    private final Path csvFile;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public CsvStorageService(String csvFilePath) {
        this.csvFile = Paths.get(csvFilePath);
        initializeCsvFile();
    }

    private void initializeCsvFile() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(csvFile)) {
                Files.createDirectories(csvFile.getParent());
                try (PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(Files.newOutputStream(csvFile, StandardOpenOption.CREATE), 
                                StandardCharsets.UTF_8))) {
                    writer.println("ChatID,Date,Amount,Note");
                }
                System.out.println("Создан новый CSV файл: " + csvFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при создании CSV файла: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void addExpense(Expense expense) {
        lock.writeLock().lock();
        try {
            try (PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(Files.newOutputStream(csvFile, StandardOpenOption.APPEND), 
                            StandardCharsets.UTF_8))) {
                String dateStr = DATE_FORMATTER.format(expense.getCreatedAt());
                String note = escapeCsv(expense.getNote());
                writer.printf("%d,%s,%.2f,%s%n", 
                        expense.getChatId(), dateStr, expense.getAmount(), note);
            }
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при записи в CSV: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Expense> getExpenses(long chatId) {
        lock.readLock().lock();
        try {
            List<Expense> expenses = new ArrayList<>();
            if (!Files.exists(csvFile)) {
                return expenses;
            }

            try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
                String header = reader.readLine();
                if (header == null) {
                    return expenses;
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    Expense expense = parseLine(line, chatId);
                    if (expense != null) {
                        expenses.add(expense);
                    }
                }
            }
            return expenses;
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при чтении CSV: " + e.getMessage(), e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private Expense parseLine(String line, long chatId) {
        try {

            String[] parts = line.split(",", 4);
            if (parts.length < 4) {
                return null;
            }

            long lineChatId = Long.parseLong(parts[0].trim());
            if (lineChatId != chatId) {
                return null;
            }

            String dateStr = parts[1].trim();
            Instant createdAt = Instant.from(DATE_FORMATTER.parse(dateStr));

            double amount = Double.parseDouble(parts[2].trim());
            String note = unescapeCsv(parts[3].trim());

            return new Expense(chatId, amount, note, createdAt);
        } catch (Exception e) {

            return null;
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        return value.replace(",", ";").replace("\n", " ").replace("\r", " ");
    }

    private String unescapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(";", ",");
    }

    public String getFilePath() {
        return csvFile.toString();
    }
}

