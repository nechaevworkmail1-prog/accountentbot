package com.kct.accountant.scheduler;

import com.kct.accountant.model.RecurringExpense;
import com.kct.accountant.service.ExpenseService;
import com.kct.accountant.storage.RecurringExpenseStorage;
import com.kct.accountant.gsheets.GoogleSheetsService;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RecurringExpenseScheduler {
    private final RecurringExpenseStorage recurringStorage;
    private final ExpenseService expenseService;
    private final ScheduledExecutorService scheduler;
    private final Path processedFilePath;
    private Map<Long, GoogleSheetsService> userSheetsServices;

    public RecurringExpenseScheduler(RecurringExpenseStorage recurringStorage, 
                                    ExpenseService expenseService) {
        this.recurringStorage = recurringStorage;
        this.expenseService = expenseService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        String dataDir = System.getenv("DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "data";
        }
        this.processedFilePath = Paths.get(dataDir + "/processed_recurring.txt");
        this.userSheetsServices = new HashMap<>();
        
        try {
            Files.createDirectories(processedFilePath.getParent());
            if (!Files.exists(processedFilePath)) {
                Files.createFile(processedFilePath);
            }
        } catch (IOException e) {
            System.err.println("Ошибка инициализации файла обработанных платежей: " + e.getMessage());
        }
    }

    public void setUserSheetsServices(Map<Long, GoogleSheetsService> services) {
        this.userSheetsServices = services;
    }

    public void start() {
        System.out.println("🕐 Запуск планировщика фиксированных платежей...");

        scheduler.schedule(this::processRecurringExpenses, 10, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(this::processRecurringExpenses, 
                1, 1, TimeUnit.HOURS);
        
        System.out.println("✓ Планировщик запущен. Проверка каждый час.");
    }

    public void stop() {
        System.out.println("🛑 Остановка планировщика...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    private void processRecurringExpenses() {
        try {
            LocalDate today = LocalDate.now();
            int dayOfMonth = today.getDayOfMonth();
            String dateKey = today.toString();
            
            System.out.println("🔍 Проверка фиксированных платежей на " + dateKey + " (день " + dayOfMonth + ")");

            if (wasProcessedToday(dateKey)) {
                System.out.println("✓ Платежи на " + dateKey + " уже обработаны");
                return;
            }

            List<RecurringExpense> dueExpenses = recurringStorage.getDueRecurringExpenses(dayOfMonth);
            
            if (dueExpenses.isEmpty()) {
                System.out.println("📭 Нет фиксированных платежей на день " + dayOfMonth);
                markAsProcessed(dateKey);
                return;
            }
            
            System.out.println("📋 Найдено " + dueExpenses.size() + " фиксированных платежей для обработки");
            
            int processed = 0;
            for (RecurringExpense recurring : dueExpenses) {
                try {

                    expenseService.addExpense(recurring.getChatId(), recurring.getAmount(), 
                                            recurring.getType() + ": " + recurring.getCategory());

                    GoogleSheetsService sheetsService = userSheetsServices.get(recurring.getChatId());
                    if (sheetsService != null) {
                        try {
                            sheetsService.appendExpenseRow(today, recurring.getType(), 
                                    recurring.getCategory(), recurring.getAmount(), recurring.getCurrency());
                            System.out.println("✓ Добавлен фиксированный платеж для пользователя " 
                                    + recurring.getChatId() + ": " + recurring.getCategory() 
                                    + " " + recurring.getAmount() + " " + recurring.getCurrency());
                        } catch (Exception e) {
                            System.err.println("❌ Ошибка записи в Google Sheets для пользователя " 
                                    + recurring.getChatId() + ": " + e.getMessage());
                        }
                    }
                    
                    processed++;
                } catch (Exception e) {
                    System.err.println("❌ Ошибка обработки фиксированного платежа ID " 
                            + recurring.getId() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            System.out.println("✅ Обработано " + processed + " из " + dueExpenses.size() + " платежей");
            markAsProcessed(dateKey);
            
        } catch (Exception e) {
            System.err.println("❌ Критическая ошибка в планировщике: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean wasProcessedToday(String dateKey) {
        try {
            if (!Files.exists(processedFilePath)) {
                return false;
            }
            
            List<String> lines = Files.readAllLines(processedFilePath);
            return lines.contains(dateKey);
        } catch (IOException e) {
            System.err.println("Ошибка чтения файла обработанных дат: " + e.getMessage());
            return false;
        }
    }

    private void markAsProcessed(String dateKey) {
        try {

            List<String> lines = new ArrayList<>();
            if (Files.exists(processedFilePath)) {
                lines = new ArrayList<>(Files.readAllLines(processedFilePath));
            }

            if (!lines.contains(dateKey)) {
                lines.add(dateKey);
            }

            if (lines.size() > 90) {
                lines = lines.subList(lines.size() - 90, lines.size());
            }

            Files.write(processedFilePath, lines, StandardOpenOption.CREATE, 
                       StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Ошибка записи файла обработанных дат: " + e.getMessage());
        }
    }

    public void forceProcess() {
        System.out.println("🔄 Принудительный запуск обработки фиксированных платежей...");
        processRecurringExpenses();
    }
}

