package com.kct.accountant.storage;

import com.kct.accountant.model.RecurringExpense;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class RecurringExpenseStorage {
    private final Path filePath;
    private final AtomicLong idCounter = new AtomicLong(1);

    public RecurringExpenseStorage(String path) {
        this.filePath = Paths.get(path);
        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
                System.out.println("Создан файл: " + filePath);
            }

            long maxId = getAllRecurringExpenses().stream()
                    .mapToLong(RecurringExpense::getId)
                    .max()
                    .orElse(0);
            idCounter.set(maxId + 1);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось инициализировать RecurringExpenseStorage", e);
        }
    }

    public RecurringExpense addRecurringExpense(long chatId, String type, String category, 
                                               double amount, String currency, int dayOfMonth) {
        long id = idCounter.getAndIncrement();
        RecurringExpense expense = new RecurringExpense(id, chatId, type, category, 
                                                       amount, currency, dayOfMonth, true);
        
        try (BufferedWriter writer = Files.newBufferedWriter(filePath, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(String.format("%d,%d,%s,%s,%.2f,%s,%d,%s%n",
                    id, chatId, type, category, amount, currency, dayOfMonth, "true"));
            System.out.println("✓ Добавлен фиксированный платеж: " + category + " " + amount);
        } catch (IOException e) {
            System.err.println("❌ Ошибка записи фиксированного платежа: " + e.getMessage());
        }
        
        return expense;
    }

    public List<RecurringExpense> getRecurringExpenses(long chatId) {
        return getAllRecurringExpenses().stream()
                .filter(e -> e.getChatId() == chatId && e.isActive())
                .collect(Collectors.toList());
    }

    public List<RecurringExpense> getAllRecurringExpenses() {
        List<RecurringExpense> expenses = new ArrayList<>();
        
        try {
            if (!Files.exists(filePath) || Files.size(filePath) == 0) {
                return expenses;
            }
            
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                try {
                    String[] parts = line.split(",", 8);
                    if (parts.length >= 8) {
                        long id = Long.parseLong(parts[0].trim());
                        long chatId = Long.parseLong(parts[1].trim());
                        String type = parts[2].trim();
                        String category = parts[3].trim();
                        double amount = Double.parseDouble(parts[4].trim());
                        String currency = parts[5].trim();
                        int dayOfMonth = Integer.parseInt(parts[6].trim());
                        boolean active = Boolean.parseBoolean(parts[7].trim());
                        
                        expenses.add(new RecurringExpense(id, chatId, type, category, 
                                                        amount, currency, dayOfMonth, active));
                    }
                } catch (Exception e) {
                    System.err.println("Ошибка парсинга строки: " + line + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("❌ Ошибка чтения фиксированных платежей: " + e.getMessage());
        }
        
        return expenses;
    }

    public boolean deleteRecurringExpense(long chatId, long id) {
        List<RecurringExpense> all = getAllRecurringExpenses();
        
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (RecurringExpense expense : all) {
                boolean active = expense.isActive();

                if (expense.getId() == id && expense.getChatId() == chatId) {
                    active = false;
                }
                
                writer.write(String.format("%d,%d,%s,%s,%.2f,%s,%d,%s%n",
                        expense.getId(), expense.getChatId(), expense.getType(), 
                        expense.getCategory(), expense.getAmount(), expense.getCurrency(),
                        expense.getDayOfMonth(), active));
            }
            return true;
        } catch (IOException e) {
            System.err.println("❌ Ошибка удаления фиксированного платежа: " + e.getMessage());
            return false;
        }
    }

    public List<RecurringExpense> getDueRecurringExpenses(int dayOfMonth) {
        return getAllRecurringExpenses().stream()
                .filter(e -> e.isActive() && e.getDayOfMonth() == dayOfMonth)
                .collect(Collectors.toList());
    }
}

