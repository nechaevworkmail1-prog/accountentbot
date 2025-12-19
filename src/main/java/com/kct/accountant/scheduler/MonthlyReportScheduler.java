package com.kct.accountant.scheduler;

import com.kct.accountant.model.Expense;
import com.kct.accountant.service.ExpenseService;
import com.kct.accountant.charts.ChartService;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;

import java.io.*;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MonthlyReportScheduler {
    private final ExpenseService expenseService;
    private final ChartService chartService;
    private final TelegramLongPollingBot bot;
    private final ScheduledExecutorService scheduler;
    private final Path settingsFilePath;
    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.00");

    private Map<Long, Integer> reportSettings;

    public MonthlyReportScheduler(ExpenseService expenseService, 
                                 ChartService chartService,
                                 TelegramLongPollingBot bot) {
        this.expenseService = expenseService;
        this.chartService = chartService;
        this.bot = bot;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        String dataDir = System.getenv("DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "data";
        }
        this.settingsFilePath = Paths.get(dataDir + "/monthly_report_settings.txt");
        this.reportSettings = new HashMap<>();
        
        loadSettings();
    }

    private void loadSettings() {
        try {
            Files.createDirectories(settingsFilePath.getParent());
            if (!Files.exists(settingsFilePath)) {
                Files.createFile(settingsFilePath);
                return;
            }
            
            List<String> lines = Files.readAllLines(settingsFilePath);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    try {
                        long chatId = Long.parseLong(parts[0].trim());
                        int dayOfMonth = Integer.parseInt(parts[1].trim());
                        reportSettings.put(chatId, dayOfMonth);
                    } catch (NumberFormatException e) {
                        System.err.println("Ошибка парсинга настроек отчета: " + line);
                    }
                }
            }
            
            System.out.println("✓ Загружено " + reportSettings.size() + " настроек отчетности");
        } catch (IOException e) {
            System.err.println("Ошибка загрузки настроек отчетности: " + e.getMessage());
        }
    }

    private void saveSettings() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, Integer> entry : reportSettings.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
            Files.writeString(settingsFilePath, sb.toString());
        } catch (IOException e) {
            System.err.println("Ошибка сохранения настроек отчетности: " + e.getMessage());
        }
    }

    public void setReportDay(long chatId, int dayOfMonth) {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("День месяца должен быть от 1 до 31");
        }
        reportSettings.put(chatId, dayOfMonth);
        saveSettings();
        System.out.println("✓ Установлен день отчета " + dayOfMonth + " для пользователя " + chatId);
    }

    public void disableReports(long chatId) {
        reportSettings.remove(chatId);
        saveSettings();
        System.out.println("✓ Отчеты отключены для пользователя " + chatId);
    }

    public Optional<Integer> getReportDay(long chatId) {
        return Optional.ofNullable(reportSettings.get(chatId));
    }

    public void start() {
        System.out.println("📊 Запуск планировщика ежемесячной отчетности...");

        scheduler.scheduleAtFixedRate(this::checkAndSendReports, 
                1, 1, TimeUnit.HOURS);
        
        System.out.println("✓ Планировщик отчетности запущен");
    }

    public void stop() {
        System.out.println("🛑 Остановка планировщика отчетности...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    private void checkAndSendReports() {
        try {
            LocalDate today = LocalDate.now();
            int dayOfMonth = today.getDayOfMonth();

            int hour = java.time.LocalTime.now().getHour();
            if (hour != 9) {
                return;
            }
            
            System.out.println("📊 Проверка отчетности на " + today + " (день " + dayOfMonth + ")");
            
            for (Map.Entry<Long, Integer> entry : reportSettings.entrySet()) {
                long chatId = entry.getKey();
                int reportDay = entry.getValue();
                
                if (dayOfMonth == reportDay) {
                    System.out.println("📨 Отправка отчета пользователю " + chatId);
                    sendMonthlyReport(chatId);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка в планировщике отчетности: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendMonthlyReport(long chatId) {
        try {
            YearMonth lastMonth = YearMonth.now().minusMonths(1);
            LocalDate startDate = lastMonth.atDay(1);
            LocalDate endDate = lastMonth.atEndOfMonth();
            
            List<Expense> allExpenses = expenseService.listExpenses(chatId);

            List<Expense> monthExpenses = allExpenses.stream()
                    .filter(e -> {
                        LocalDate expenseDate = LocalDate.ofInstant(e.getCreatedAt(), 
                                java.time.ZoneId.systemDefault());
                        return !expenseDate.isBefore(startDate) && !expenseDate.isAfter(endDate);
                    })
                    .collect(Collectors.toList());
            
            if (monthExpenses.isEmpty()) {
                SendMessage message = new SendMessage();
                message.setChatId(String.valueOf(chatId));
                message.setText("📊 ЕЖЕМЕСЯЧНЫЙ ОТЧЕТ\n" +
                              "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                              "📅 Период: " + lastMonth + "\n\n" +
                              "📭 Нет данных за этот период");
                bot.execute(message);
                return;
            }

            double totalIncome = 0.0;
            double totalExpense = 0.0;
            int incomeCount = 0;
            int expenseCount = 0;
            
            for (Expense e : monthExpenses) {
                boolean isIncome = e.getNote().toLowerCase().contains("доход");
                if (isIncome) {
                    totalIncome += e.getAmount();
                    incomeCount++;
                } else {
                    totalExpense += e.getAmount();
                    expenseCount++;
                }
            }
            
            double balance = totalIncome - totalExpense;
            String balanceEmoji = balance >= 0 ? "✅" : "⚠️";

            StringBuilder report = new StringBuilder();
            report.append("📊 ЕЖЕМЕСЯЧНЫЙ ОТЧЕТ\n");
            report.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
            report.append("📅 Период: ").append(lastMonth).append("\n\n");
            report.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            report.append("📝 Операций: ").append(monthExpenses.size()).append("\n");
            report.append("   💰 Доходов: ").append(incomeCount).append("\n");
            report.append("   💸 Расходов: ").append(expenseCount).append("\n\n");
            report.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            report.append("💰 Доходы: +").append(formatAmount(totalIncome)).append(" ₽\n");
            report.append("💸 Расходы: -").append(formatAmount(totalExpense)).append(" ₽\n");
            report.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            report.append(balanceEmoji).append(" Баланс: ").append(formatAmount(balance)).append(" ₽\n\n");
            
            if (expenseCount > 0) {
                double avgExpense = totalExpense / expenseCount;
                report.append("📉 Средний расход: ").append(formatAmount(avgExpense)).append(" ₽");
            }

            SendMessage textMessage = new SendMessage();
            textMessage.setChatId(String.valueOf(chatId));
            textMessage.setText(report.toString());
            bot.execute(textMessage);

            try {
                java.io.File chartFile = chartService.createIncomeExpenseBarChart(monthExpenses);
                
                SendPhoto photo = new SendPhoto();
                photo.setChatId(String.valueOf(chatId));
                photo.setPhoto(new InputFile(chartFile));
                photo.setCaption("📈 График за " + lastMonth + "\n\n" +
                               "💰 Зеленый — доходы\n" +
                               "💸 Красный — расходы");
                
                bot.execute(photo);
                chartFile.delete();
            } catch (Exception e) {
                System.err.println("❌ Ошибка создания графика для отчета: " + e.getMessage());
            }
            
            System.out.println("✅ Отчет отправлен пользователю " + chatId);
            
        } catch (Exception e) {
            System.err.println("❌ Ошибка отправки отчета пользователю " + chatId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.format("%.0f", amount);
        } else {
            return moneyFormat.format(amount);
        }
    }
}

