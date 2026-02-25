package com.kct.accountant;

import com.kct.accountant.model.Expense;
import com.kct.accountant.model.RecurringExpense;
import com.kct.accountant.model.LockedAmount;
import com.kct.accountant.service.ExpenseService;
import com.kct.accountant.parser.RuCommandParser;
import com.kct.accountant.gsheets.GoogleSheetsService;
import com.kct.accountant.storage.CsvStorageService;
import com.kct.accountant.storage.RecurringExpenseStorage;
import com.kct.accountant.storage.LockedAmountStorage;
import com.kct.accountant.charts.ChartService;
import com.kct.accountant.scheduler.RecurringExpenseScheduler;
import com.kct.accountant.scheduler.MonthlyReportScheduler;
import com.kct.accountant.util.CategoryCorrector;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class AccountantBot extends TelegramLongPollingBot {
    private final String botToken;
    private final String botUsername;
    private final ExpenseService expenseService;
    private final RuCommandParser ruParser = new RuCommandParser();
    private GoogleSheetsService sheetsService;
    private final ChartService chartService = new ChartService();
    private final DecimalFormat moneyFormat = new DecimalFormat("#,##0.00");

    private final RecurringExpenseStorage recurringStorage;
    private final LockedAmountStorage lockedAmountStorage;

    private final RecurringExpenseScheduler recurringScheduler;
    private final MonthlyReportScheduler reportScheduler;
    private final ScheduledExecutorService keepAliveScheduler;

    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {

            return String.format("%.0f", amount);
        } else {

            return moneyFormat.format(amount);
        }
    }

    private String formatAmountWithCurrency(double amount, String currency) {
        String formatted = formatAmount(amount);
        String symbol = RuCommandParser.getCurrencySymbol(currency);
        return formatted + " " + symbol;
    }
    
    private String serviceAccountEmail;
    private final Map<Long, String> userSpreadsheetIds = new HashMap<>();
    private final Path userIdsFile;
    private final java.util.Set<Long> activeUsers = new java.util.HashSet<>();
    private final java.util.Set<Long> hiddenKeyboardUsers = new java.util.HashSet<>();

    public AccountantBot() {
        Properties properties = new Properties();
        try (InputStream inputStream = AccountantBot.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException ignored) {
        }
        this.botToken = System.getenv("BOT_TOKEN") != null ? System.getenv("BOT_TOKEN") : properties.getProperty("bot.token", "REPLACE_WITH_TOKEN");
        this.botUsername = System.getenv("BOT_USERNAME") != null ? System.getenv("BOT_USERNAME") : properties.getProperty("bot.username", "accountant_bot");

        // Базовая директория для данных (настраивается через переменную окружения DATA_DIR)
        String dataDir = System.getenv("DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "data";
        }
        System.out.println("📁 Базовая директория данных: " + dataDir);
        
        String csvPath = System.getenv("STORAGE_CSV_PATH");
        if (csvPath == null || csvPath.isBlank()) {
            csvPath = properties.getProperty("storage.csv.path", dataDir + "/expenses.csv");
        }
        CsvStorageService csvStorage = new CsvStorageService(csvPath);
        this.expenseService = new ExpenseService(csvStorage);
        System.out.println("CSV хранилище инициализировано: " + csvStorage.getFilePath());

        this.recurringStorage = new RecurringExpenseStorage(dataDir + "/recurring_expenses.csv");
        System.out.println("✓ Хранилище фиксированных платежей инициализировано");
        
        this.lockedAmountStorage = new LockedAmountStorage(dataDir + "/locked_amounts.csv");
        System.out.println("✓ Хранилище фиксированных сумм инициализировано");
        
        this.userIdsFile = Paths.get(dataDir + "/user_spreadsheet_ids.txt");
        
        String creds = System.getenv("GOOGLE_CREDENTIALS_PATH");
        String credsJson = System.getenv("GOOGLE_CREDENTIALS");
        System.out.println("🔍 Поиск credentials.json...");
        System.out.println("   GOOGLE_CREDENTIALS_PATH: " + (creds != null ? creds : "не установлена"));
        System.out.println("   GOOGLE_CREDENTIALS: " + (credsJson != null ? "установлена (JSON)" : "не установлена"));
        
        // Сначала проверяем переменную GOOGLE_CREDENTIALS 
        if (credsJson != null && !credsJson.isBlank()) {
            try {
                // Создаем временный файл из JSON
                Path tempFile = Files.createTempFile("google-credentials-", ".json");
                Files.writeString(tempFile, credsJson);
                tempFile.toFile().deleteOnExit();
                creds = tempFile.toString();
                System.out.println("✓ Используется GOOGLE_CREDENTIALS (временный файл): " + creds);
            } catch (Exception e) {
                System.err.println("❌ Ошибка создания временного файла из GOOGLE_CREDENTIALS: " + e.getMessage());
                creds = null;
            }
        } else if (creds == null || creds.isBlank()) {
            // Если GOOGLE_CREDENTIALS не установлена ищем файлы
            Path secretsPath = Paths.get("/etc/secrets/credentials.json");
            Path localPath = Paths.get("credentials.json");
            System.out.println("   Проверка /etc/secrets/credentials.json: " + (Files.exists(secretsPath) ? "найден" : "не найден"));
            System.out.println("   Проверка credentials.json в корне: " + (Files.exists(localPath) ? "найден" : "не найден"));
            
            if (Files.exists(secretsPath)) {
                creds = secretsPath.toString();
                System.out.println("✓ Используется Secret File: " + creds);
            } else if (Files.exists(localPath)) {
                creds = localPath.toString();
                System.out.println("✓ Используется локальный файл: " + creds);
            } else {
                System.out.println("⚠️ credentials.json не найден ни в одном из мест");
                System.out.println("   💡 Варианты решения:");
                System.out.println("      1. Установите переменную GOOGLE_CREDENTIALS с JSON содержимым (рекомендуется для Railway)");
                System.out.println("      2. Установите GOOGLE_CREDENTIALS_PATH с путем к файлу (например, /data/credentials.json)");
                System.out.println("      3. Поместите credentials.json в директорию данных (DATA_DIR)");
                System.out.println("      4. Поместите credentials.json в корень проекта");
            }
        } else {
            System.out.println("✓ Используется GOOGLE_CREDENTIALS_PATH: " + creds);
        }
        
        String spreadsheetId = System.getenv("GOOGLE_SPREADSHEET_ID") != null ? System.getenv("GOOGLE_SPREADSHEET_ID") : properties.getProperty("google.spreadsheet.id", "");
        String sheetName = System.getenv("GOOGLE_SHEET_NAME") != null ? System.getenv("GOOGLE_SHEET_NAME") : properties.getProperty("google.sheet.name", "Sheet1");

        if (creds != null && !creds.isBlank()) {
            try {
                Path credsPath = Paths.get(creds);
                if (!Files.exists(credsPath)) {
                    System.err.println("⚠️ Файл credentials.json не найден по пути: " + creds);
                    System.err.println("   Проверьте:");
                    System.err.println("   • Переменную GOOGLE_CREDENTIALS (JSON содержимое в одной строке, рекомендуется для Railway)");
                    System.err.println("   • Переменную GOOGLE_CREDENTIALS_PATH (путь к файлу, например /data/credentials.json)");
                    System.err.println("   • Что файл существует по указанному пути");
                    creds = "";
                    this.serviceAccountEmail = null;
                } else {
                    System.out.println("📖 Чтение credentials.json из: " + creds);
                    JsonObject json = JsonParser.parseReader(new FileReader(creds)).getAsJsonObject();
                    if (json.has("client_email")) {
                        this.serviceAccountEmail = json.get("client_email").getAsString();
                        System.out.println("✓ Email сервисного аккаунта: " + this.serviceAccountEmail);
                    } else {
                        System.err.println("❌ В credentials.json отсутствует поле 'client_email'");
                        this.serviceAccountEmail = null;
                        creds = "";
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ Не удалось прочитать email из credentials.json: " + e.getMessage());
                e.printStackTrace();
                creds = "";
                this.serviceAccountEmail = null;
            }
        } else {
            System.out.println("ℹ️ Google Sheets не настроен. Бот будет работать только с CSV.");
            this.serviceAccountEmail = null;
        }
        
        if (creds != null && !creds.isBlank() && spreadsheetId != null && !spreadsheetId.isBlank()) {
            try {
                this.sheetsService = new GoogleSheetsService(creds, spreadsheetId, sheetName);
                System.out.println("✓ Google Sheets подключен успешно");
            } catch (RuntimeException ex) {
                System.err.println("❌ Google Sheets init error: " + ex.getMessage());
            }
        } else if (spreadsheetId != null && !spreadsheetId.isBlank()) {
            System.err.println("⚠️ GOOGLE_SPREADSHEET_ID указан, но credentials.json не найден. Google Sheets отключен.");
            System.err.println("   💡 Настройте credentials одним из способов:");
            System.err.println("      1. Установите переменную GOOGLE_CREDENTIALS (JSON содержимое в одной строке, рекомендуется для Railway)");
            System.err.println("      2. Установите переменную GOOGLE_CREDENTIALS_PATH (путь к файлу, например /data/credentials.json)");
            System.err.println("      3. Поместите credentials.json в директорию данных (DATA_DIR)");
            System.err.println("      4. Поместите credentials.json в корень проекта");
        }

        loadUserSpreadsheetIds();

        this.recurringScheduler = new RecurringExpenseScheduler(recurringStorage, expenseService);
        this.reportScheduler = new MonthlyReportScheduler(expenseService, chartService, this);

        this.recurringScheduler.start();
        this.reportScheduler.start();
        System.out.println("✓ Планировщики запущены");

        this.keepAliveScheduler = Executors.newSingleThreadScheduledExecutor();
        startKeepAlive();
        
        Locale.setDefault(Locale.US);
    }

    private void startKeepAlive() {
        // Проверяем нужно ли ограничение по времени (для Railway/Render лучше без ограничений)
        String keepAliveMode = System.getenv("KEEP_ALIVE_MODE");
        boolean alwaysOn = "always".equalsIgnoreCase(keepAliveMode) || keepAliveMode == null;
        
        if (alwaysOn) {
            System.out.println("🔄 Запуск keep-alive планировщика (работает 24/7, запрос каждые 5 минут)...");
        } else {
            System.out.println("🔄 Запуск keep-alive планировщика (запрос каждые 5 минут, с 6:00 до 1:00 МСК)...");
        }
        
        // Первый ping через 1 минуту
        keepAliveScheduler.schedule(() -> pingTelegram(alwaysOn), 1, TimeUnit.MINUTES);
        
        // Затем каждые 5 минут для поддержания активности на Railway/Render/Fly.io
        keepAliveScheduler.scheduleAtFixedRate(() -> pingTelegram(alwaysOn), 5, 5, TimeUnit.MINUTES);
        
        System.out.println("✓ Keep-alive планировщик запущен");
    }

    private boolean isWorkingHours() {
        ZonedDateTime nowMoscow = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
        int hour = nowMoscow.getHour();
        return hour >= 6 || hour < 1;
    }

    private void pingTelegram(boolean alwaysOn) {
        // Если не alwaysOn, проверяем рабочие часы
        if (!alwaysOn && !isWorkingHours()) {
            return;
        }
        
        try {
            GetMe getMe = new GetMe();
            execute(getMe);
            ZonedDateTime nowMoscow = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
            System.out.println("✓ Keep-alive ping выполнен: " + nowMoscow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss МСК")));
        } catch (TelegramApiException e) {
            System.err.println("❌ Ошибка keep-alive ping: " + e.getMessage());
        }
    }
    
    private void loadUserSpreadsheetIds() {
        // Сначала пытаемся загрузить из Google Sheets 
        if (sheetsService != null) {
            try {
                loadUserSpreadsheetIdsFromSheets();
                if (!userSpreadsheetIds.isEmpty()) {
                    System.out.println("✓ Загружено " + userSpreadsheetIds.size() + " ID таблиц пользователей из Google Sheets");
                    return;
                }
            } catch (Exception e) {
                System.err.println("⚠️ Не удалось загрузить ID таблиц из Google Sheets: " + e.getMessage());
                System.out.println("   Пробую загрузить из файла...");
            }
        }
        
        // Fallback: загружаем из файла
        try {
            if (Files.exists(userIdsFile)) {
                List<String> lines = Files.readAllLines(userIdsFile);
                for (String line : lines) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        try {
                            long chatId = Long.parseLong(parts[0].trim());
                            String spreadsheetId = parts[1].trim();
                            userSpreadsheetIds.put(chatId, spreadsheetId);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                System.out.println("✓ Загружено " + userSpreadsheetIds.size() + " ID таблиц пользователей из файла");
                
                // Если есть данные в файле и Google Sheets настроен, синхронизируем
                if (!userSpreadsheetIds.isEmpty() && sheetsService != null) {
                    try {
                        saveUserSpreadsheetIdsToSheets();
                        System.out.println("✓ Синхронизировано с Google Sheets");
                    } catch (Exception e) {
                        System.err.println("⚠️ Не удалось синхронизировать с Google Sheets: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка загрузки ID таблиц из файла: " + e.getMessage());
        }
    }
    
    private void loadUserSpreadsheetIdsFromSheets() throws IOException {
        if (sheetsService == null) {
            return;
        }
        
        try {
            // Читаем из служебного листа "Users"
            List<List<Object>> rows = sheetsService.readSheet("Users");
            
            for (List<Object> row : rows) {
                if (row.size() >= 2) {
                    try {
                        long chatId = Long.parseLong(row.get(0).toString().trim());
                        String spreadsheetId = row.get(1).toString().trim();
                        if (!spreadsheetId.isEmpty()) {
                            userSpreadsheetIds.put(chatId, spreadsheetId);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            // Если лист "Users" не существует - создадим его при первом сохранении
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("Unable to parse range") || 
                                     errorMsg.contains("не найден") ||
                                     errorMsg.contains("400"))) {
                System.out.println("ℹ️ Лист 'Users' не найден, будет создан при первом сохранении");
                // Возвращаем пустой список
                return;
            }
            throw e;
        }
    }
    
    private void saveUserSpreadsheetIdsToSheets() throws IOException {
        if (sheetsService == null) {
            return;
        }
        
        try {
            // Читаем существующие данные
            List<List<Object>> existingRows = new ArrayList<>();
            boolean sheetExists = true;
            try {
                existingRows = sheetsService.readSheet("Users");
            } catch (Exception e) {
                // Лист не существует, создадим его
                sheetExists = false;
            }
            
            // Создаем заголовки если лист не существует
            if (!sheetExists || existingRows.isEmpty()) {
                List<List<Object>> header = List.of(List.of("ChatID", "SpreadsheetID"));
                sheetsService.writeSheet("Users", "A1:B1", header);
                existingRows = new ArrayList<>();
            }
            
            // Обновляем существующие строки и добавляем новые
            Map<Long, Integer> rowIndexMap = new HashMap<>();
            for (int i = 1; i < existingRows.size(); i++) {
                List<Object> row = existingRows.get(i);
                if (row.size() >= 1) {
                    try {
                        long chatId = Long.parseLong(row.get(0).toString().trim());
                        rowIndexMap.put(chatId, i + 1); // +1 потому что строки начинаются с 1, а индекс с 0
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            
            // Обновляем или добавляем записи
            for (Map.Entry<Long, String> entry : userSpreadsheetIds.entrySet()) {
                long chatId = entry.getKey();
                String spreadsheetId = entry.getValue();
                Integer rowIndex = rowIndexMap.get(chatId);
                
                if (rowIndex != null) {
                    // Обновляем существующую строку
                    List<List<Object>> updateRow = List.of(List.of(chatId, spreadsheetId));
                    String range = "A" + rowIndex + ":B" + rowIndex;
                    sheetsService.writeSheet("Users", range, updateRow);
                } else {
                    // Добавляем новую строку
                    List<List<Object>> newRow = List.of(List.of(chatId, spreadsheetId));
                    sheetsService.appendToSheet("Users", newRow);
                }
            }
        } catch (Exception e) {
            throw new IOException("Ошибка сохранения в Google Sheets: " + e.getMessage(), e);
        }
    }
    
    private void saveUserSpreadsheetId(long chatId, String spreadsheetId) {
        userSpreadsheetIds.put(chatId, spreadsheetId);
        
        // Сохраняем в Google Sheets
        if (sheetsService != null) {
            try {
                saveUserSpreadsheetIdsToSheets();
                System.out.println("✓ Сохранен ID таблицы для пользователя " + chatId + " в Google Sheets");
            } catch (Exception e) {
                System.err.println("⚠️ Не удалось сохранить в Google Sheets: " + e.getMessage());
                System.out.println("   Сохраняю в файл...");
            }
        }
        
        // Также сохраняем в файл (как backup)
        try {
            Files.createDirectories(userIdsFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, String> entry : userSpreadsheetIds.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
            Files.writeString(userIdsFile, sb.toString());
            System.out.println("✓ Сохранен ID таблицы для пользователя " + chatId + " в файл");
        } catch (IOException e) {
            System.err.println("Ошибка сохранения ID таблицы в файл: " + e.getMessage());
        }
    }
    
    private GoogleSheetsService getUserSheetsService(long chatId) {
        String userSpreadsheetId = userSpreadsheetIds.get(chatId);
        if (userSpreadsheetId == null || userSpreadsheetId.isEmpty()) {
            System.out.println("❌ Для пользователя " + chatId + " не найден ID таблицы");
            return null;
        }
        
        String creds = System.getenv("GOOGLE_CREDENTIALS_PATH");
        String credsJson = System.getenv("GOOGLE_CREDENTIALS");
        
        // Сначала проверяем переменную GOOGLE_CREDENTIALS (JSON напрямую)
        if (credsJson != null && !credsJson.isBlank()) {
            try {
                // Создаем временный файл из JSON
                Path tempFile = Files.createTempFile("google-credentials-", ".json");
                Files.writeString(tempFile, credsJson);
                tempFile.toFile().deleteOnExit();
                creds = tempFile.toString();
                System.out.println("✓ Используется GOOGLE_CREDENTIALS (временный файл): " + creds);
            } catch (Exception e) {
                System.err.println("❌ Ошибка создания временного файла из GOOGLE_CREDENTIALS: " + e.getMessage());
                creds = null;
            }
        } else if (creds == null || creds.isBlank()) {
            Path secretsPath = Paths.get("/etc/secrets/credentials.json");
            Path localPath = Paths.get("credentials.json");
            if (Files.exists(secretsPath)) {
                creds = secretsPath.toString();
                System.out.println("✓ Найден credentials.json в Secret Files: " + creds);
            } else if (Files.exists(localPath)) {
                creds = localPath.toString();
                System.out.println("✓ Найден credentials.json в корне: " + creds);
            } else {
                System.out.println("ℹ️ credentials.json не найден. Проверьте:");
                System.out.println("   • Переменную GOOGLE_CREDENTIALS (JSON в одной строке, рекомендуется для Railway)");
                System.out.println("   • Переменную GOOGLE_CREDENTIALS_PATH (путь к файлу, например /data/credentials.json)");
                System.out.println("   • Что файл находится в директории данных (DATA_DIR)");
            }
        } else {
            System.out.println("✓ Используется GOOGLE_CREDENTIALS_PATH: " + creds);
        }
        String sheetName = System.getenv("GOOGLE_SHEET_NAME") != null ? System.getenv("GOOGLE_SHEET_NAME") : "Sheet1";
        
        if (creds == null || creds.isBlank()) {
            System.err.println("❌ Путь к credentials.json не найден. Проверьте:");
            System.err.println("   • Переменную GOOGLE_CREDENTIALS (JSON содержимое в одной строке, рекомендуется для Railway)");
            System.err.println("   • Переменную GOOGLE_CREDENTIALS_PATH (путь к файлу, например /data/credentials.json)");
            System.err.println("   • Что файл находится в директории данных (DATA_DIR)");
            return null;
        }
        
        try {
            System.out.println("🔧 Создаю GoogleSheetsService для пользователя " + chatId);
            System.out.println("   Таблица ID: " + userSpreadsheetId);
            System.out.println("   Credentials: " + creds);
            System.out.println("   Лист: " + sheetName);
            
            GoogleSheetsService service = new GoogleSheetsService(creds, userSpreadsheetId, sheetName);
            System.out.println("✓ GoogleSheetsService создан успешно для пользователя " + chatId);
            return service;
        } catch (RuntimeException e) {
            System.err.println("❌ Ошибка создания GoogleSheetsService для пользователя " + chatId + ": " + e.getMessage());
            System.err.println("   Возможные причины:");
            System.err.println("   1. Файл credentials не найден по пути: " + creds);
            System.err.println("   2. Нет доступа к таблице (проверьте /email и права доступа)");
            System.err.println("   3. Неверный ID таблицы");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Неожиданная ошибка для пользователя " + chatId + ": " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            long chatIdLong = update.getMessage().getChatId();
            String chatId = String.valueOf(chatIdLong);

            if (update.getMessage().hasAnimation() || update.getMessage().hasPhoto() || 
                update.getMessage().hasSticker() || update.getMessage().hasVideo() ||
                update.getMessage().hasDocument() || update.getMessage().hasVoice()) {
                
                System.out.println("📎 Получено медиа от пользователя " + chatIdLong + " (игнорируется)");
                return;
            }

            if (!update.getMessage().hasText()) {
                return;
            }
            
            String text = update.getMessage().getText().trim();

            boolean isFirstTime = !activeUsers.contains(chatIdLong);
            
            String reply = handleCommand(chatIdLong, text);

            if (reply == null) {
                return;
            }
            
            if (reply.length() > 4096) {
                sendLongMessage(chatId, reply, chatIdLong, text, isFirstTime);
            } else {
                SendMessage message = new SendMessage(chatId, reply);

                if (isFirstTime && !text.equalsIgnoreCase("/start") && !text.equals("🚀 Старт")) {
                    message.setReplyMarkup(getWelcomeKeyboard());
                } else if ("/menu".equalsIgnoreCase(text)) {
                    if (hiddenKeyboardUsers.contains(chatIdLong)) {
                        message.setReplyMarkup(getReplyKeyboard());
                    } else {
                        message.setReplyMarkup(getHiddenKeyboard());
                    }
                } else if ("📋 Показать меню".equals(text)) {
                    hiddenKeyboardUsers.remove(chatIdLong);
                    message.setReplyMarkup(getReplyKeyboard());
                } else if (hiddenKeyboardUsers.contains(chatIdLong)) {
                    message.setReplyMarkup(getHiddenKeyboard());
                } else if ("/start".equalsIgnoreCase(text) || "🚀 Старт".equals(text)) {
                    message.setReplyMarkup(getReplyKeyboard());
                } else if (text.startsWith("/") || text.contains("📋") || text.contains("📊") || text.contains("📈") || 
                           text.contains("ℹ️") || text.contains("❓") || text.contains("☁️") || text.contains("🔒") || 
                           text.contains("📅") || text.contains("⚙️")) {
                    message.setReplyMarkup(getReplyKeyboard());
                }
                
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    System.err.println("❌ Ошибка отправки сообщения пользователю " + chatIdLong + ": " + e.getMessage());
                    try {
                        SendMessage errorMsg = new SendMessage(chatId, "❌ Произошла ошибка при отправке сообщения. Попробуйте позже.");
                        execute(errorMsg);
                    } catch (TelegramApiException ex) {
                        System.err.println("❌ Критическая ошибка отправки сообщения об ошибке: " + ex.getMessage());
                    }
                }
            }
        } else if (update.hasCallbackQuery()) {

            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            String callbackId = update.getCallbackQuery().getId();

            try {
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(callbackId);
                execute(answer);
            } catch (TelegramApiException e) {
                System.err.println("Failed to answer callback: " + e.getMessage());
            }
            
            String reply = handleCallback(chatId, callbackData);

            if (reply != null) {
                SendMessage message = new SendMessage(String.valueOf(chatId), reply);
                message.setReplyMarkup(getReplyKeyboard());
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    System.err.println("Failed to send callback reply: " + e.getMessage());
                }
            }
        }
    }
    
    private void sendLongMessage(String chatId, String longText, long chatIdLong, String originalText, boolean isFirstTime) {
        int maxLength = 4096;
        int start = 0;
        int partNumber = 1;
        
        while (start < longText.length()) {
            int end = Math.min(start + maxLength, longText.length());
            
            if (end < longText.length()) {
                int lastNewline = longText.lastIndexOf('\n', end - 1);
                if (lastNewline > start) {
                    end = lastNewline + 1;
                }
            }
            
            String part = longText.substring(start, end);
            if (longText.length() > maxLength) {
                part = "📄 Часть " + partNumber + "\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + part;
            }
            
            SendMessage message = new SendMessage(chatId, part);
            
            if (partNumber == 1 && isFirstTime && !originalText.equalsIgnoreCase("/start") && !originalText.equals("🚀 Старт")) {
                message.setReplyMarkup(getWelcomeKeyboard());
            } else if (partNumber == 1 && "/menu".equalsIgnoreCase(originalText)) {
                if (hiddenKeyboardUsers.contains(chatIdLong)) {
                    message.setReplyMarkup(getReplyKeyboard());
                } else {
                    message.setReplyMarkup(getHiddenKeyboard());
                }
            } else if (partNumber == 1 && !hiddenKeyboardUsers.contains(chatIdLong) && 
                      (originalText.startsWith("/") || originalText.contains("📋") || originalText.contains("📊"))) {
                message.setReplyMarkup(getReplyKeyboard());
            }
            
            try {
                execute(message);
                if (end < longText.length()) {
                    Thread.sleep(100);
                }
            } catch (TelegramApiException e) {
                System.err.println("❌ Ошибка отправки части сообщения " + partNumber + " пользователю " + chatIdLong + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("❌ Прервана отправка длинного сообщения");
                break;
            }
            
            start = end;
            partNumber++;
        }
    }

    private String handleCommand(long chatId, String text) {
        System.out.println("Обработка команды: " + text + " (chatId: " + chatId + ")");
        activeUsers.add(chatId);

        if ("🚀 Старт".equals(text)) {
            text = "/start";
        }

        if ("📋 Показать меню".equals(text)) {
            text = "/menu";
        }

        if ("📋 Список".equals(text)) {
            text = "/list";
        } else if ("📊 Статистика".equals(text)) {
            text = "/stats";
        } else if ("📈 График".equals(text)) {
            text = "/chart";
        } else if ("ℹ️ Статус".equals(text)) {
            text = "/status";
        } else if ("❓ Помощь".equals(text)) {
            text = "/help";
        } else if ("☁️ Настройка Sheets".equals(text)) {
            text = "/sheets";
        } else if ("🔒 Мои суммы".equals(text)) {
            text = "/ll";
        } else if ("📅 Фикс. платежи".equals(text)) {
            text = "/lr";
        } else if ("📈 Отчет".equals(text)) {
            text = "/r";
        } else if ("⚙️ Настройки".equals(text)) {

            return getSettingsMenu();
        } else if ("📊 Моя таблица".equals(text)) {

            String userSpreadsheetId = userSpreadsheetIds.get(chatId);
            if (userSpreadsheetId != null && !userSpreadsheetId.isEmpty()) {
                return "📊 ВАША GOOGLE ТАБЛИЦА\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Откройте таблицу:\n" +
                       "https://docs.google.com/spreadsheets/d/" + userSpreadsheetId + "/edit";
            } else {
                return "❌ ТАБЛИЦА НЕ НАСТРОЕНА\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Используйте /sheets для настройки.";
            }
        }
        
        if ("/start".equalsIgnoreCase(text)) {
            StringBuilder response = new StringBuilder();
            response.append("💼 Я помогу вам вести учёт финансов:\n");
            response.append("   • Записывать доходы и расходы\n");
            response.append("   • Сохранять в Google Sheets\n");
            response.append("   • Показывать статистику и графики\n");
            response.append("   • Автоматические отчеты\n\n");
            
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            response.append("🚀 БЫСТРЫЙ СТАРТ:\n\n");
            response.append("Просто напишите боту:\n");
            response.append("💸 \"расход кофе 150\"\n");
            response.append("💰 \"доход зарплата 50000\"\n\n");
            
            response.append("Или используйте короткие команды:\n");
            response.append("📝 /ls кофе 150 — зафиксировать сумму\n");
            response.append("📋 /ll — мои суммы\n");
            response.append("📅 /ar — фикс. платежи\n");
            response.append("❓ /help — все команды\n\n");
            
            response.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            String userSpreadsheetId = userSpreadsheetIds.get(chatId);
            if (userSpreadsheetId != null && !userSpreadsheetId.isEmpty()) {
                response.append("✅ Google Sheets подключен!\n");
                response.append("📊 Ваша таблица: https://docs.google.com/spreadsheets/d/")
                        .append(userSpreadsheetId).append("/edit");
            } else if (sheetsService != null) {
                response.append("☁️ Хранилище: CSV + Google Sheets\n");
                response.append("💡 Используйте /setid чтобы указать вашу таблицу");
            } else {
                response.append("📁 Хранилище: CSV файл\n");
                response.append("💡 Хотите сохранять в облако? → /sheets");
            }
            
            return response.toString();
        }
        if ("/menu".equalsIgnoreCase(text)) {

            if (hiddenKeyboardUsers.contains(chatId)) {

                hiddenKeyboardUsers.remove(chatId);
                return "✅ МЕНЮ ПОКАЗАНО\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Кнопки меню теперь видны внизу экрана.\n\n" +
                       "💡 Чтобы скрыть меню:\n" +
                       "Отправьте /menu ещё раз";
            } else {

                hiddenKeyboardUsers.add(chatId);
                return "👌 МЕНЮ СКРЫТО\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Кнопки скрыты для экономии места.\n\n" +
                       "💡 Чтобы показать меню:\n" +
                       "Нажмите кнопку '📋 Показать меню'\n" +
                       "или отправьте /menu";
            }
        }
        if ("/help".equalsIgnoreCase(text)) {
            StringBuilder help = new StringBuilder();
            help.append("📚 СПРАВКА ПО КОМАНДАМ\n");
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            
            help.append("✍️ ЗАПИСЬ ФИНАНСОВ:\n\n");
            help.append("📝 Команды:\n");
            help.append("   /add 150 кофе — добавить расход\n");
            help.append("   /add 50000 зарплата — добавить доход\n\n");
            help.append("💬 Текстом (проще!):\n");
            help.append("   расходы кофе 150\n");
            help.append("   доходы зарплата 50000 usd\n");
            help.append("   расход аренда 20000 руб\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("🔒 ФИКСИРОВАННЫЕ СУММЫ:\n\n");
            help.append("   /ls кофе 150 — зафиксировать\n");
            help.append("   /ll — список (с кнопками!)\n");
            help.append("   /dl кофе — удалить\n\n");
            help.append("💡 После этого пишите просто:\n");
            help.append("   'расход кофе' — сумма подставится!\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("📅 ФИКСИРОВАННЫЕ ПЛАТЕЖИ:\n\n");
            help.append("   /ar 21 расход аренда 70000\n");
            help.append("   /lr — список (с кнопками!)\n");
            help.append("   /dr <ID> — удалить\n\n");
            help.append("💡 Добавляются автоматически каждый месяц!\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("📊 ПРОСМОТР ДАННЫХ:\n\n");
            help.append("   /list — 📋 все записи\n");
            help.append("   /stats — 📈 статистика\n");
            help.append("   /chart — 📊 график (картинка)\n");
            help.append("   /status — ℹ️ статус бота\n");
            help.append("   /menu — 🔄 скрыть/показать кнопки\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("📈 ОТЧЕТНОСТЬ:\n\n");
            help.append("   /sr 1 — настроить на 1-е число\n");
            help.append("   /r — получить отчет сейчас\n");
            help.append("   /noreport — отключить\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("☁️ GOOGLE SHEETS:\n\n");
            help.append("   /sheets — 📘 инструкция\n");
            help.append("   /email — 📧 email бота\n");
            help.append("   /setid <ID> — 🔗 указать таблицу\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("💰 ВАЛЮТЫ:\n\n");
            help.append("   Поддержка: RUB, USD, EUR, GBP, CNY\n");
            help.append("   Пример: 'расход кофе 5 usd'\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("⚡ КОРОТКИЕ КОМАНДЫ:\n\n");
            help.append("Для удобства на телефоне:\n\n");
            help.append("🔒 Фиксированные суммы:\n");
            help.append("   /ls — зафиксировать сумму\n");
            help.append("   /ll — мои суммы (список)\n");
            help.append("   /dl — удалить сумму\n\n");
            help.append("📅 Фиксированные платежи:\n");
            help.append("   /ar — добавить платеж\n");
            help.append("   /lr — мои платежи (список)\n");
            help.append("   /dr — удалить платеж\n\n");
            help.append("📊 Отчетность:\n");
            help.append("   /sr — настроить отчет\n");
            help.append("   /r — отчет сейчас\n\n");
            
            help.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            help.append("💡 СОВЕТ:\n");
            help.append("• Используйте кнопки ниже!\n");
            help.append("• В списках есть кнопки удаления\n");
            help.append("• Короткие команды быстрее набирать");
            
            return help.toString();
        }
        String trimmedText = text.trim();
        if (trimmedText.equalsIgnoreCase("/sheets") || trimmedText.equalsIgnoreCase("/setup") || trimmedText.equalsIgnoreCase("/google")) {
            System.out.println("✓ Обработка команды /sheets");
            String instruction = getGoogleSheetsInstruction();
            System.out.println("✓ Команда /sheets обработана, длина ответа: " + (instruction != null ? instruction.length() : 0));
            return instruction;
        }
        if (trimmedText.equalsIgnoreCase("/email") || trimmedText.equalsIgnoreCase("/mail")) {
            System.out.println("✓ Обработка команды /email, serviceAccountEmail: " + (serviceAccountEmail != null ? serviceAccountEmail : "null"));
            if (serviceAccountEmail == null || serviceAccountEmail.isEmpty()) {
                System.out.println("⚠️ serviceAccountEmail пуст, возвращаю сообщение об ошибке");
                return "❌ Google Sheets не настроен.\n\n" +
                        "Обратитесь к администратору бота для настройки.";
            }
            String response = "📧 EMAIL ДЛЯ ДОСТУПА К ТАБЛИЦЕ:\n\n" +
                    "`" + serviceAccountEmail + "`\n\n" +
                    "📋 Что делать:\n" +
                    "1. Откройте вашу Google Таблицу\n" +
                    "2. Нажмите \"Share\" (Настроить доступ)\n" +
                    "3. Скопируйте email выше и вставьте\n" +
                    "4. Роль: **Editor** (Редактор)\n" +
                    "5. Отправьте\n\n" +
                    "✅ После этого отправьте боту ID таблицы (команда /setid или просто ID)";
            System.out.println("✓ Команда /email обработана, email: " + serviceAccountEmail);
            return response;
        }
        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/setid")) {
            String[] parts = text.split(" ", 2);
            if (parts.length < 2) {
                return "📝 КАК УКАЗАТЬ ID ТАБЛИЦЫ\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "Использование:\n" +
                        "/setid <ID_таблицы>\n\n" +
                        "📖 Где найти ID?\n" +
                        "Откройте таблицу в браузере и скопируйте ID из URL:\n\n" +
                        "https://docs.google.com/spreadsheets/d/1ABC123.../edit\n" +
                        "                                          ^^^^^^^\n" +
                        "                                        ЭТО ID\n\n" +
                        "💡 Пример:\n" +
                        "/setid 1ABC123def456GHI789";
            }
            String spreadsheetId = parts[1].trim();
            if (spreadsheetId.length() < 10) {
                return "❌ НЕВЕРНЫЙ ФОРМАТ\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "ID должен быть длинной строкой\n" +
                        "(обычно 40-50 символов)\n\n" +
                        "Пример правильного ID:\n" +
                        "1kXWvmu-T4kVgZUknANTUi61s7VAj4UasQbdVBKdc5nA";
            }
            
            saveUserSpreadsheetId(chatId, spreadsheetId);
            
            return "✅ ТАБЛИЦА ПОДКЛЮЧЕНА!\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "ID сохранен успешно.\n" +
                    "Теперь все записи автоматически\n" +
                    "сохраняются в Google Sheets!\n\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "🧪 ПРОВЕРКА:\n\n" +
                    "Напишите боту:\n" +
                    "расходы тест 100\n\n" +
                    "Бот должен записать в таблицу.\n\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━\n" +
                    "⚠️ Если не работает:\n\n" +
                    "1. Дайте доступ боту (см. /email)\n" +
                    "2. Роль: Editor (Редактор)\n" +
                    "3. Переименуйте лист на 'Sheet1'";
        }

        if (text.length() > 20 && text.length() < 100 && !text.contains(" ") && !text.startsWith("/")) {
            String possibleId = text.trim();

            if (possibleId.matches("^[a-zA-Z0-9_-]+$")) {
                saveUserSpreadsheetId(chatId, possibleId);
                return "✅ ID таблицы сохранен: `" + possibleId + "`\n\n" +
                        "Теперь ваши данные будут автоматически записываться в Google Sheets.\n\n" +
                        "📝 Проверьте: напишите боту 'расходы тест 100 руб'";
            }
        }
        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/add")) {
            String[] parts = text.split(" ", 3);
            if (parts.length < 3) {
                return "📝 Использование: /add <сумма> <комментарий>\n\n" +
                        "Примеры:\n" +
                        "• /add 150 кофе\n" +
                        "• /add 199.99 обед\n" +
                        "• /add 5000 аренда";
            }
            try {

                String amountStr = parts[1].replace(",", ".").replace(" ", "");
                double amount = Double.parseDouble(amountStr);
                String note = parts[2];

                Expense e = expenseService.addExpense(chatId, amount, note);
                String formattedAmount = formatAmount(e.getAmount());

                GoogleSheetsService userSheetsService = getUserSheetsService(chatId);
                boolean writtenToSheets = false;
                String sheetsError = null;
                
                if (userSheetsService != null) {
                    try {
                        System.out.println("Попытка записи в Google Sheets для пользователя " + chatId + " (команда /add)");
                        userSheetsService.appendExpenseRow(java.time.LocalDate.now(), "расход", note, amount, "RUB");
                        writtenToSheets = true;
                        System.out.println("✓ Успешно записано в Google Sheets");
                    } catch (Exception ex) {
                        sheetsError = ex.getMessage();
                        System.err.println("❌ Ошибка записи в Google Sheets: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                if (!writtenToSheets && sheetsService != null) {
                    try {
                        // Используем отдельный лист для каждого пользователя
                        String userSheetName = "User" + chatId;
                        System.out.println("Попытка записи в общую Google Sheets, лист: " + userSheetName + " (команда /add)");
                        
                        GoogleSheetsService userSheetService = new GoogleSheetsService(
                            sheetsService.getSheets(), 
                            sheetsService.getSpreadsheetId(), 
                            userSheetName
                        );
                        userSheetService.appendExpenseRow(java.time.LocalDate.now(), "расход", note, amount, "RUB");
                        writtenToSheets = true;
                        System.out.println("✓ Успешно записано в общую Google Sheets, лист: " + userSheetName);
                    } catch (Exception ex) {
                        sheetsError = ex.getMessage();
                        System.err.println("❌ Ошибка записи в общую Google Sheets: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                String response = "✅ Добавлено: " + formattedAmount + " ₽ — " + e.getNote();
                if (writtenToSheets) {
                    String tableId = userSpreadsheetIds.get(chatId);
                    if (tableId == null && sheetsService != null) {
                        tableId = sheetsService.getSpreadsheetId();
                    }
                    response = "✅ Записал в CSV и Google Sheets: " + formattedAmount + " ₽ — " + e.getNote();
                    if (tableId != null) {
                        response += "\n\n📊 Таблица: https://docs.google.com/spreadsheets/d/" + tableId + "/edit";
                    }
                } else if (sheetsError != null) {
                    response += "\n\n⚠️ Google Sheets: записано только в CSV";
                }
                
                return response;
            } catch (NumberFormatException nfe) {
                return "❌ Некорректная сумма.\n\nПримеры:\n" +
                        "• /add 150 кофе\n" +
                        "• /add 199.99 обед\n" +
                        "• /add 5000 аренда";
            }
        }
        if ("/list".equalsIgnoreCase(text)) {
            List<Expense> list = expenseService.listExpenses(chatId);
            if (list.isEmpty()) {
                return "📭 СПИСОК ПУСТ\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "У вас пока нет записей.\n\n" +
                       "💡 Добавьте первую запись:\n" +
                       "   • расходы кофе 150\n" +
                       "   • /add 150 кофе";
            }
            
            int maxItems = 200;
            boolean truncated = list.size() > maxItems;
            if (truncated) {
                list = list.subList(0, maxItems);
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("📋 ВСЕ ЗАПИСИ");
            if (truncated) {
                sb.append(" (показано ").append(maxItems).append(" из ").append(expenseService.listExpenses(chatId).size()).append(")");
            }
            sb.append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
            
            double total = 0.0;
            double income = 0.0;
            double expense = 0.0;
            
            for (int i = 0; i < list.size(); i++) {
                Expense e = list.get(i);
                if (e == null) continue;
                
                String note = e.getNote() != null ? e.getNote() : "";
                boolean isIncome = note.toLowerCase().startsWith("доход");
                String emoji = isIncome ? "💰" : "💸";
                String sign = isIncome ? "+" : "-";
                
                String cleanNote = note.replace("расход: ", "").replace("доход: ", "");
                if (cleanNote.length() > 50) {
                    cleanNote = cleanNote.substring(0, 47) + "...";
                }
                
                sb.append(emoji).append(" ")
                        .append(i + 1).append(". ")
                        .append(sign).append(formatAmount(e.getAmount()))
                        .append(" ₽ — ")
                        .append(cleanNote)
                        .append('\n');
                
                if (isIncome) {
                    income += e.getAmount();
                } else {
                    expense += e.getAmount();
                }
                total += isIncome ? e.getAmount() : -e.getAmount();
            }
            
            if (truncated) {
                sb.append("\n... (показаны только последние ").append(maxItems).append(" записей)\n");
            }
            
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("💰 Доходы: +").append(formatAmount(income)).append(" ₽\n");
            sb.append("💸 Расходы: -").append(formatAmount(expense)).append(" ₽\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("💵 Баланс: ").append(formatAmount(total)).append(" ₽");
            return sb.toString();
        }
        if ("/stats".equalsIgnoreCase(text)) {
            List<Expense> list = expenseService.listExpenses(chatId);
            if (list.isEmpty()) {
                return "📊 НЕТ ДАННЫХ\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Добавьте записи для просмотра статистики.\n\n" +
                       "💡 Начните с:\n" +
                       "   • расходы кофе 150\n" +
                       "   • доходы зарплата 50000";
            }
            
            double totalIncome = 0.0;
            double totalExpense = 0.0;
            int incomeCount = 0;
            int expenseCount = 0;
            double maxExpense = 0.0;
            double minExpense = Double.MAX_VALUE;
            
            for (Expense e : list) {
                boolean isIncome = e.getNote().toLowerCase().startsWith("доход");
                double amount = e.getAmount();
                
                if (isIncome) {
                    totalIncome += amount;
                    incomeCount++;
                } else {
                    totalExpense += amount;
                    expenseCount++;
                    if (amount > maxExpense) maxExpense = amount;
                    if (amount < minExpense) minExpense = amount;
                }
            }
            
            double balance = totalIncome - totalExpense;
            String balanceEmoji = balance >= 0 ? "✅" : "⚠️";
            
            StringBuilder stats = new StringBuilder();
            stats.append("📊 СТАТИСТИКА\n");
            stats.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
            stats.append("📝 Записей: ").append(incomeCount + expenseCount).append("\n");
            stats.append("   💰 Доходов: ").append(incomeCount).append("\n");
            stats.append("   💸 Расходов: ").append(expenseCount).append("\n\n");
            stats.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            stats.append("💰 Доходы: +").append(formatAmount(totalIncome)).append(" ₽\n");
            stats.append("💸 Расходы: -").append(formatAmount(totalExpense)).append(" ₽\n");
            stats.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            stats.append(balanceEmoji).append(" Баланс: ").append(formatAmount(balance)).append(" ₽\n\n");
            
            if (expenseCount > 0) {
                double avgExpense = totalExpense / expenseCount;
                stats.append("📉 Средний расход: ").append(formatAmount(avgExpense)).append(" ₽\n");
                stats.append("⬆️ Максимум: ").append(formatAmount(maxExpense)).append(" ₽\n");
                if (minExpense != Double.MAX_VALUE) {
                    stats.append("⬇️ Минимум: ").append(formatAmount(minExpense)).append(" ₽");
                }
            }
            
            return stats.toString();
        }
        if ("/chart".equalsIgnoreCase(text) || "/graph".equalsIgnoreCase(text)) {
            List<Expense> list = expenseService.listExpenses(chatId);
            if (list.isEmpty()) {
                return "📊 НЕТ ДАННЫХ ДЛЯ ГРАФИКА\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Добавьте записи чтобы увидеть график.\n\n" +
                       "💡 Начните с:\n" +
                       "   • расходы кофе 150\n" +
                       "   • доходы зарплата 50000";
            }
            
            try {
                System.out.println("Создаю график для пользователя " + chatId);
                java.io.File chartFile = chartService.createIncomeExpenseBarChart(list);

                SendPhoto photo = new SendPhoto();
                photo.setChatId(String.valueOf(chatId));
                photo.setPhoto(new InputFile(chartFile));
                photo.setCaption("📊 ГРАФИК ДОХОДОВ И РАСХОДОВ\n\n" +
                                "💰 Зеленый — доходы\n" +
                                "💸 Красный — расходы");
                photo.setReplyMarkup(getReplyKeyboard());
                
                execute(photo);

                chartFile.delete();
                
                System.out.println("✓ График отправлен пользователю " + chatId);
                return null;
                
            } catch (Exception e) {
                System.err.println("❌ Ошибка создания графика: " + e.getMessage());
                e.printStackTrace();
                return "❌ Не удалось создать график.\n\n" +
                       "Попробуйте позже или обратитесь к администратору.";
            }
        }
        if ("/status".equalsIgnoreCase(text)) {
            StringBuilder sb = new StringBuilder();
            sb.append("ℹ️ СТАТУС СИСТЕМЫ\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

            List<Expense> expenses = expenseService.listExpenses(chatId);
            int incomeCount = 0;
            int expenseCount = 0;
            for (Expense e : expenses) {
                if (e.getNote().toLowerCase().startsWith("доход")) {
                    incomeCount++;
                } else {
                    expenseCount++;
                }
            }
            
            sb.append("📝 Ваших записей: ").append(expenses.size()).append("\n");
            if (expenses.size() > 0) {
                sb.append("   💰 Доходов: ").append(incomeCount).append("\n");
                sb.append("   💸 Расходов: ").append(expenseCount).append("\n");
            }
            
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("💾 ХРАНИЛИЩА:\n\n");
            sb.append("📁 CSV файл: ✅ Активен\n");
            sb.append("   Путь: data/expenses.csv\n\n");

            String userSpreadsheetId = userSpreadsheetIds.get(chatId);
            if (userSpreadsheetId != null && !userSpreadsheetId.isEmpty()) {
                sb.append("☁️ Google Sheets: ✅ Подключен\n");
                sb.append("   ID: ").append(userSpreadsheetId.substring(0, Math.min(20, userSpreadsheetId.length()))).append("...\n");
                sb.append("\n📊 Открыть таблицу:\n");
                sb.append("https://docs.google.com/spreadsheets/d/").append(userSpreadsheetId).append("/edit");
            } else {
                sb.append("☁️ Google Sheets: ❌ Не настроен\n\n");
                sb.append("💡 Настройте за 2 шага:\n");
                sb.append("   1. /sheets — инструкция\n");
                sb.append("   2. /setid <ID> — укажите таблицу");
            }
            
            return sb.toString();
        }
        if ("/export".equalsIgnoreCase(text)) {
            return "📤 ЭКСПОРТ ДАННЫХ:\n\n" +
                    "📁 CSV файл:\n" +
                    "• Путь: data/expenses.csv\n" +
                    "• Формат: ChatID,Date,Amount,Note\n" +
                    "• Можно открыть в Excel, Google Sheets, LibreOffice\n\n" +
                    "☁️ Google Sheets:\n" +
                    (sheetsService != null ? 
                        "• ✅ Подключено - данные дублируются автоматически" : 
                        "• ❌ Не подключено - используйте /sheets для настройки");
        }
        if ("/clear".equalsIgnoreCase(text)) {

            return "⚠️ Функция очистки пока не реализована.\n\n" +
                    "Для очистки данных удалите файл: data/expenses.csv\n" +
                    "⚠️ Внимание: это удалит ВСЕ ваши данные!";
        }

        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/addrecurring") || 
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/ar") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/fixadd")) {
            String[] parts = text.split("\\s+", 5);
            if (parts.length < 5) {
                return "📅 ДОБАВИТЬ ФИКСИРОВАННЫЙ ПЛАТЕЖ\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Использование:\n" +
                       "/addrecurring <день> <тип> <категория> <сумма>\n\n" +
                       "📖 Примеры:\n" +
                       "• /addrecurring 21 расход аренда 70000\n" +
                       "• /addrecurring 1 доход зарплата 150000\n" +
                       "• /addrecurring 15 расход интернет 500\n\n" +
                       "💡 День месяца: от 1 до 31\n" +
                       "💡 Тип: расход или доход\n" +
                       "💡 Платеж будет автоматически добавляться каждый месяц!";
            }
            
            try {
                int dayOfMonth = Integer.parseInt(parts[1]);
                if (dayOfMonth < 1 || dayOfMonth > 31) {
                    return "❌ НЕВЕРНЫЙ ДЕНЬ\n\nДень месяца должен быть от 1 до 31";
                }
                
                String type = parts[2].toLowerCase();
                if (!type.equals("расход") && !type.equals("доход")) {
                    return "❌ НЕВЕРНЫЙ ТИП\n\nТип должен быть 'расход' или 'доход'";
                }
                
                String category = parts[3];
                double amount = Double.parseDouble(parts[4]);
                
                if (amount <= 0) {
                    return "❌ Сумма должна быть больше нуля";
                }
                
                RecurringExpense recurring = recurringStorage.addRecurringExpense(
                    chatId, type, category, amount, "RUB", dayOfMonth);
                
                return "✅ ФИКСИРОВАННЫЙ ПЛАТЕЖ ДОБАВЛЕН!\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "📅 День: " + dayOfMonth + " число каждого месяца\n" +
                       "📌 Тип: " + type + "\n" +
                       "📝 Категория: " + category + "\n" +
                       "💵 Сумма: " + formatAmount(amount) + " ₽\n\n" +
                       "🤖 Бот будет автоматически добавлять этот платеж каждый месяц!\n\n" +
                       "💡 Управление:\n" +
                       "   /listrecurring — список\n" +
                       "   /deleterecurring — удалить";
                
            } catch (NumberFormatException e) {
                return "❌ ОШИБКА ФОРМАТА\n\n" +
                       "День и сумма должны быть числами.\n\n" +
                       "Пример: /addrecurring 21 расход аренда 70000";
            }
        }

        if ("/listrecurring".equalsIgnoreCase(text) || "/lr".equalsIgnoreCase(text) || 
            "/fixlist".equalsIgnoreCase(text) || "/recurring".equalsIgnoreCase(text)) {
            List<RecurringExpense> recurring = recurringStorage.getRecurringExpenses(chatId);
            
            if (recurring.isEmpty()) {
                SendMessage message = new SendMessage(String.valueOf(chatId), 
                    "📭 НЕТ ФИКСИРОВАННЫХ ПЛАТЕЖЕЙ\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "У вас пока нет настроенных фиксированных платежей.\n\n" +
                    "💡 Добавьте первый:\n" +
                    "/ar 21 расход аренда 70000");

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> keyboard = new java.util.ArrayList<>();
                
                List<InlineKeyboardButton> row = new java.util.ArrayList<>();
                InlineKeyboardButton btn = new InlineKeyboardButton("➕ Добавить платеж");
                btn.setCallbackData("help_addrecurring");
                row.add(btn);
                
                keyboard.add(row);
                markup.setKeyboard(keyboard);
                message.setReplyMarkup(markup);
                
                try {
                    execute(message);
                    return null;
                } catch (TelegramApiException e) {
                    System.err.println("Failed to send message: " + e.getMessage());
                }
                return null;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("📅 ФИКСИРОВАННЫЕ ПЛАТЕЖИ\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append("Эти платежи добавляются автоматически:\n\n");
            
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> keyboard = new java.util.ArrayList<>();
            
            for (RecurringExpense r : recurring) {
                String emoji = r.getType().equals("доход") ? "💰" : "💸";
                String sign = r.getType().equals("доход") ? "+" : "-";
                
                sb.append(emoji).append(" ID: ").append(r.getId()).append("\n");
                sb.append("   📅 День: ").append(r.getDayOfMonth()).append(" число\n");
                sb.append("   📝 ").append(r.getCategory()).append("\n");
                sb.append("   💵 ").append(sign).append(formatAmount(r.getAmount())).append(" ₽\n\n");

                List<InlineKeyboardButton> row = new java.util.ArrayList<>();
                InlineKeyboardButton btnDel = new InlineKeyboardButton("🗑️ Удалить " + r.getId());
                btnDel.setCallbackData("del_recurring_" + r.getId());
                row.add(btnDel);
                keyboard.add(row);
            }
            
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("💡 Нажмите кнопку ниже или используйте: /dr <ID>");

            List<InlineKeyboardButton> addRow = new java.util.ArrayList<>();
            InlineKeyboardButton btnAdd = new InlineKeyboardButton("➕ Добавить новый");
            btnAdd.setCallbackData("help_addrecurring");
            addRow.add(btnAdd);
            keyboard.add(addRow);
            
            markup.setKeyboard(keyboard);
            
            SendMessage message = new SendMessage(String.valueOf(chatId), sb.toString());
            message.setReplyMarkup(markup);
            
            try {
                execute(message);
                return null;
            } catch (TelegramApiException e) {
                System.err.println("Failed to send message: " + e.getMessage());
            }
            return sb.toString();
        }

        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/deleterecurring") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/dr") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/fixdel")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2) {
                return "❌ УДАЛЕНИЕ ФИКСИРОВАННОГО ПЛАТЕЖА\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Использование:\n" +
                       "/deleterecurring <ID>\n\n" +
                       "📋 Узнать ID: /listrecurring";
            }
            
            try {
                long id = Long.parseLong(parts[1]);
                boolean deleted = recurringStorage.deleteRecurringExpense(chatId, id);
                
                if (deleted) {
                    return "✅ ФИКСИРОВАННЫЙ ПЛАТЕЖ УДАЛЕН\n" +
                           "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                           "Платеж ID " + id + " больше не будет добавляться автоматически.\n\n" +
                           "📋 Посмотреть оставшиеся: /listrecurring";
                } else {
                    return "❌ Платеж не найден или уже удален";
                }
            } catch (NumberFormatException e) {
                return "❌ ID должен быть числом\n\nПример: /deleterecurring 1";
            }
        }

        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/locksum") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/ls") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/lock")) {
            String[] parts = text.split("\\s+", 3);
            if (parts.length < 3) {
                return "🔒 ЗАФИКСИРОВАТЬ СУММУ КАТЕГОРИИ\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Использование:\n" +
                       "/locksum <категория> <сумма>\n\n" +
                       "📖 Примеры:\n" +
                       "• /locksum кофе 150\n" +
                       "• /locksum обед 350\n" +
                       "• /locksum метро 70\n\n" +
                       "💡 После этого достаточно написать:\n" +
                       "   'расход кофе' — и сумма 150 подставится автоматически!\n\n" +
                       "📋 Управление:\n" +
                       "   /listlocked — список\n" +
                       "   /deletelocked — удалить";
            }
            
            try {
                String category = parts[1];
                double amount = Double.parseDouble(parts[2]);
                
                if (amount <= 0) {
                    return "❌ Сумма должна быть больше нуля";
                }
                
                lockedAmountStorage.setLockedAmount(chatId, category, amount, "RUB");
                
                return "✅ СУММА ЗАФИКСИРОВАНА!\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "📝 Категория: " + category + "\n" +
                       "💵 Сумма: " + formatAmount(amount) + " ₽\n\n" +
                       "🚀 Теперь просто пишите:\n" +
                       "   'расход " + category + "'\n\n" +
                       "И сумма " + formatAmount(amount) + " ₽ добавится автоматически!\n\n" +
                       "📋 Посмотреть все: /listlocked";
                
            } catch (NumberFormatException e) {
                return "❌ Сумма должна быть числом\n\nПример: /locksum кофе 150";
            }
        }

        if ("/listlocked".equalsIgnoreCase(text) || "/ll".equalsIgnoreCase(text) ||
            "/locks".equalsIgnoreCase(text) || "/locked".equalsIgnoreCase(text)) {
            List<LockedAmount> locked = lockedAmountStorage.getLockedAmounts(chatId);
            
            if (locked.isEmpty()) {
                SendMessage message = new SendMessage(String.valueOf(chatId),
                    "📭 НЕТ ФИКСИРОВАННЫХ СУММ\n" +
                    "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                    "У вас пока нет зафиксированных сумм.\n\n" +
                    "💡 Добавьте первую:\n" +
                    "/ls кофе 150");

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> keyboard = new java.util.ArrayList<>();
                
                List<InlineKeyboardButton> row = new java.util.ArrayList<>();
                InlineKeyboardButton btn = new InlineKeyboardButton("➕ Добавить сумму");
                btn.setCallbackData("help_locksum");
                row.add(btn);
                
                keyboard.add(row);
                markup.setKeyboard(keyboard);
                message.setReplyMarkup(markup);
                
                try {
                    execute(message);
                    return null;
                } catch (TelegramApiException e) {
                    System.err.println("Failed to send message: " + e.getMessage());
                }
                return null;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("🔒 ФИКСИРОВАННЫЕ СУММЫ\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append("Просто напишите название — сумма подставится автоматически:\n\n");
            
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> keyboard = new java.util.ArrayList<>();
            
            for (LockedAmount la : locked) {
                sb.append("💵 ").append(la.getCategory()).append(" = ")
                  .append(formatAmountWithCurrency(la.getAmount(), la.getCurrency()))
                  .append("\n");

                List<InlineKeyboardButton> row = new java.util.ArrayList<>();
                InlineKeyboardButton btnDel = new InlineKeyboardButton("🗑️ " + la.getCategory());
                btnDel.setCallbackData("del_locked_" + la.getCategory());
                row.add(btnDel);
                keyboard.add(row);
            }
            
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("💡 Использование: 'расход кофе'\n");
            sb.append("   И сумма подставится автоматически!\n\n");
            sb.append("🗑️ Нажмите кнопку или используйте: /dl <категория>");

            List<InlineKeyboardButton> addRow = new java.util.ArrayList<>();
            InlineKeyboardButton btnAdd = new InlineKeyboardButton("➕ Добавить новую");
            btnAdd.setCallbackData("help_locksum");
            addRow.add(btnAdd);
            keyboard.add(addRow);
            
            markup.setKeyboard(keyboard);
            
            SendMessage message = new SendMessage(String.valueOf(chatId), sb.toString());
            message.setReplyMarkup(markup);
            
            try {
                execute(message);
                return null;
            } catch (TelegramApiException e) {
                System.err.println("Failed to send message: " + e.getMessage());
            }
            return sb.toString();
        }

        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/deletelocked") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/dl") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/unlock")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2) {
                return "❌ УДАЛЕНИЕ ФИКСИРОВАННОЙ СУММЫ\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Использование:\n" +
                       "/deletelocked <категория>\n\n" +
                       "📋 Узнать категории: /listlocked";
            }
            
            String category = parts[1];
            boolean deleted = lockedAmountStorage.deleteLockedAmount(chatId, category);
            
            if (deleted) {
                return "✅ ФИКСИРОВАННАЯ СУММА УДАЛЕНА\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Категория '" + category + "' больше не имеет фиксированной суммы.\n\n" +
                       "📋 Посмотреть оставшиеся: /listlocked";
            } else {
                return "❌ Категория '" + category + "' не найдена";
            }
        }

        if (trimmedText.toLowerCase(Locale.ROOT).startsWith("/setreport") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/sr") ||
            trimmedText.toLowerCase(Locale.ROOT).startsWith("/report_on")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2) {
                return "📊 НАСТРОЙКА ЕЖЕМЕСЯЧНОЙ ОТЧЕТНОСТИ\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "Использование:\n" +
                       "/setreport <день_месяца>\n\n" +
                       "📖 Примеры:\n" +
                       "• /setreport 1 — отчет 1-го числа\n" +
                       "• /setreport 15 — отчет 15-го числа\n" +
                       "• /setreport 30 — отчет 30-го числа\n\n" +
                       "📈 Бот будет автоматически присылать отчет с графиком!\n\n" +
                       "💡 Отключить: /disablereport";
            }
            
            try {
                int day = Integer.parseInt(parts[1]);
                if (day < 1 || day > 31) {
                    return "❌ День должен быть от 1 до 31";
                }
                
                reportScheduler.setReportDay(chatId, day);
                
                return "✅ ОТЧЕТНОСТЬ НАСТРОЕНА!\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                       "📅 День отчета: " + day + " число каждого месяца\n\n" +
                       "📊 Бот будет автоматически присылать:\n" +
                       "   • Статистику за месяц\n" +
                       "   • График доходов и расходов\n" +
                       "   • Баланс и аналитику\n\n" +
                       "💡 Изменить: /setreport <день>\n" +
                       "🔕 Отключить: /disablereport";
                
            } catch (NumberFormatException e) {
                return "❌ День должен быть числом\n\nПример: /setreport 1";
            }
        }

        if ("/disablereport".equalsIgnoreCase(text) || "/noreport".equalsIgnoreCase(text) ||
            "/report_off".equalsIgnoreCase(text)) {
            reportScheduler.disableReports(chatId);
            return "🔕 ОТЧЕТНОСТЬ ОТКЛЮЧЕНА\n" +
                   "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                   "Автоматические отчеты больше не будут отправляться.\n\n" +
                   "💡 Включить снова: /setreport <день>";
        }

        if ("/report".equalsIgnoreCase(text) || "/r".equalsIgnoreCase(text)) {
            reportScheduler.sendMonthlyReport(chatId);
            return null;
        }

        if (trimmedText.startsWith("/")) {
            return "Неизвестная команда. Наберите /help\n\n💡 Используйте /sheets для инструкции по подключению Google Sheets";
        }

        var parsedOpt = ruParser.parse(text);
        if (parsedOpt.isPresent()) {
            var p = parsedOpt.get();

            double finalAmount = p.amount;
            String finalCurrency = p.currency;
            
            if (!p.amountSpecified) {

                Optional<LockedAmount> lockedOpt = lockedAmountStorage.getLockedAmount(chatId, p.category);
                if (lockedOpt.isPresent()) {
                    LockedAmount locked = lockedOpt.get();
                    finalAmount = locked.getAmount();
                    finalCurrency = locked.getCurrency();
                } else {

                    return "❌ СУММА НЕ УКАЗАНА\n" +
                           "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                           "Для категории '" + p.category + "' нет зафиксированной суммы.\n\n" +
                           "💡 Варианты:\n" +
                           "1. Укажите сумму: '" + p.type + " " + p.category + " 150'\n" +
                           "2. Зафиксируйте сумму: '/locksum " + p.category + " 150'\n\n" +
                           "📋 Посмотреть все фиксированные суммы: /listlocked";
                }
            }

            List<Expense> userExpenses = expenseService.listExpenses(chatId);
            List<String> categories = CategoryCorrector.extractCategories(
                userExpenses.stream().map(Expense::getNote).collect(Collectors.toList())
            );
            List<String> similarCategories = CategoryCorrector.findSimilarCategories(p.category, categories);
            String correctionSuggestion = null;
            if (!similarCategories.isEmpty() && !categories.contains(p.category.toLowerCase())) {
                correctionSuggestion = CategoryCorrector.createCorrectionSuggestion(p.category, similarCategories);
            }

            expenseService.addExpense(chatId, finalAmount, p.type + ": " + p.category);

            GoogleSheetsService userSheetsService = getUserSheetsService(chatId);
            boolean writtenToSheets = false;
            String sheetsError = null;
            
            if (userSheetsService != null) {
                try {
                    System.out.println("Попытка записи в Google Sheets для пользователя " + chatId + ", таблица: " + userSpreadsheetIds.get(chatId));
                    userSheetsService.appendExpenseRow(java.time.LocalDate.now(), p.type, p.category, finalAmount, finalCurrency);
                    writtenToSheets = true;
                    System.out.println("✓ Успешно записано в Google Sheets");
                } catch (Exception e) {
                    sheetsError = e.getMessage();
                    System.err.println("❌ Ошибка записи в Google Sheets для пользователя " + chatId + ": " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.out.println("Персональная таблица не настроена для пользователя " + chatId);
            }

            if (!writtenToSheets && sheetsService != null) {
                try {
                    // Используем отдельный лист для каждого пользователя в основной таблице
                    // Лист называется "User" + ChatID, чтобы данные не смешивались
                    String userSheetName = "User" + chatId;
                    System.out.println("Попытка записи в общую Google Sheets, лист: " + userSheetName);
                    
                    // Создаем временный сервис для этого листа
                    GoogleSheetsService userSheetService = new GoogleSheetsService(
                        sheetsService.getSheets(), 
                        sheetsService.getSpreadsheetId(), 
                        userSheetName
                    );
                    userSheetService.appendExpenseRow(java.time.LocalDate.now(), p.type, p.category, finalAmount, finalCurrency);
                    writtenToSheets = true;
                    System.out.println("✓ Успешно записано в общую Google Sheets, лист: " + userSheetName);
                } catch (Exception e) {
                    sheetsError = e.getMessage();
                    System.err.println("❌ Ошибка записи в общую Google Sheets: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            String formattedAmount = formatAmountWithCurrency(finalAmount, finalCurrency);
            StringBuilder response = new StringBuilder();

            String emoji = p.type.toLowerCase().contains("доход") ? "💰" : "💸";
            String sign = p.type.toLowerCase().contains("доход") ? "" : "-";
            
            if (writtenToSheets) {
                String tableId = userSpreadsheetIds.get(chatId);
                if (tableId == null && sheetsService != null) {
                    tableId = sheetsService.getSpreadsheetId();
                }
                
                response.append("✅ ЗАПИСАНО УСПЕШНО!\n");
                response.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
                response.append(emoji).append(" ").append(p.type.toUpperCase()).append("\n");
                response.append("📌 Категория: ").append(p.category).append("\n");
                response.append("💵 Сумма: ").append(sign).append(formattedAmount).append("\n\n");
                response.append("💾 Сохранено:\n");
                response.append("   ✓ CSV файл\n");
                
                String userTableId = userSpreadsheetIds.get(chatId);
                if (userTableId != null) {
                    response.append("   ✓ Google Sheets (ваша таблица)\n");
                } else if (sheetsService != null) {
                    response.append("   ✓ Google Sheets (общая таблица, лист: User").append(chatId).append(")\n");
                }
                
                if (correctionSuggestion != null) {
                    response.append("\n").append(correctionSuggestion);
                }
                
                if (tableId != null) {
                    response.append("\n📊 Открыть таблицу:\n");
                    response.append("https://docs.google.com/spreadsheets/d/").append(tableId).append("/edit");
                }
            } else if (sheetsError != null) {

                response.append("⚠️ ЧАСТИЧНО СОХРАНЕНО\n");
                response.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
                response.append(emoji).append(" ").append(p.type.toUpperCase()).append("\n");
                response.append("📌 Категория: ").append(p.category).append("\n");
                response.append("💵 Сумма: ").append(sign).append(formattedAmount).append("\n\n");
                response.append("💾 Сохранено в: CSV файл ✅\n");
                response.append("☁️ Google Sheets: ❌\n\n");
                response.append("❗ Проблема: ").append(sheetsError).append("\n\n");
                response.append("💡 Решение:\n");
                response.append("   1. Откройте таблицу\n");
                response.append("   2. Переименуйте лист на 'Sheet1'\n");
                response.append("   3. Дайте доступ боту (см. /email)");
                
                if (correctionSuggestion != null) {
                    response.append("\n\n").append(correctionSuggestion);
                }
            } else {

                response.append("✅ ЗАПИСАНО В CSV\n");
                response.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
                response.append(emoji).append(" ").append(p.type.toUpperCase()).append("\n");
                response.append("📌 Категория: ").append(p.category).append("\n");
                response.append("💵 Сумма: ").append(sign).append(formattedAmount).append("\n\n");
                response.append("💾 Сохранено в: CSV файл\n\n");
                
                if (correctionSuggestion != null) {
                    response.append(correctionSuggestion).append("\n");
                }
                
                response.append("💡 Хотите сохранять в облако?\n");
                response.append("   → Используйте /sheets для настройки!");
            }
            return response.toString();
        }
        return "Неизвестная команда. Наберите /help\n\n💡 Используйте /sheets для инструкции по подключению Google Sheets";
    }

    private String getSettingsMenu() {
        return "⚙️ НАСТРОЙКИ БОТА\n" +
               "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
               "🔧 Доступные настройки:\n\n" +
               "📅 Фиксированные платежи:\n" +
               "   • /ar — добавить платеж\n" +
               "   • /lr — список платежей\n" +
               "   • /dr <ID> — удалить\n\n" +
               "🔒 Фиксированные суммы:\n" +
               "   • /ls — зафиксировать\n" +
               "   • /ll — список сумм\n" +
               "   • /dl <название> — удалить\n\n" +
               "📊 Отчетность:\n" +
               "   • /sr <день> — настроить\n" +
               "   • /r — отчет сейчас\n" +
               "   • /noreport — отключить\n\n" +
               "☁️ Google Sheets:\n" +
               "   • /sheets — инструкция\n" +
               "   • /email — email бота\n" +
               "   • /setid — указать таблицу\n\n" +
               "💡 Используйте короткие команды для быстрого доступа!";
    }
    
    private String getGoogleSheetsInstruction() {
        StringBuilder instruction = new StringBuilder();
        instruction.append("📘 ПОДКЛЮЧЕНИЕ К GOOGLE SHEETS\n");
        instruction.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        instruction.append("🌐 Простая настройка (3 шага):\n\n");
        instruction.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        instruction.append("🔹 ШАГ 1: Создайте Google Таблицу\n\n");
        instruction.append("1. Откройте: https://sheets.google.com/\n");
        instruction.append("2. Создайте новую таблицу (пустую)\n");
        instruction.append("3. Переименуйте лист на 'Sheet1'\n");
        instruction.append("   (внизу страницы кликните на лист → Rename)\n\n");
        instruction.append("💡 Заголовки бот создаст автоматически!\n\n");
        instruction.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        instruction.append("🔹 ШАГ 2: Дайте доступ боту\n\n");
        
        if (serviceAccountEmail != null && !serviceAccountEmail.isEmpty()) {
            instruction.append("1. В таблице нажмите 'Share' (справа вверху)\n");
            instruction.append("2. Скопируйте этот email:\n\n");
            instruction.append("📧 `").append(serviceAccountEmail).append("`\n\n");
            instruction.append("3. Вставьте в поле 'Add people and groups'\n");
            instruction.append("4. Выберите роль: **Editor** (Редактор)\n");
            instruction.append("5. Нажмите 'Send'\n\n");
        } else {
            instruction.append("⚠️ Google Sheets не настроен на сервере.\n\n");
            instruction.append("Для получения email бота обратитесь к администратору.\n\n");
            instruction.append("После получения email:\n");
            instruction.append("1. В таблице нажмите 'Share' (справа вверху)\n");
            instruction.append("2. Вставьте email бота в поле 'Add people and groups'\n");
            instruction.append("3. Выберите роль: **Editor** (Редактор)\n");
            instruction.append("4. Нажмите 'Send'\n\n");
        }
        
        instruction.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        instruction.append("🔹 ШАГ 3: Укажите ID таблицы\n\n");
        instruction.append("1. Скопируйте ID из URL таблицы:\n\n");
        instruction.append("https://docs.google.com/spreadsheets/d/1ABC123.../edit\n");
        instruction.append("                                          ^^^^^^^\n");
        instruction.append("                                        ЭТО ID\n\n");
        instruction.append("2. Отправьте боту:\n");
        instruction.append("   /setid 1ABC123\n\n");
        instruction.append("   Или просто отправьте сам ID\n\n");
        instruction.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        instruction.append("✅ ГОТОВО!\n\n");
        instruction.append("Теперь все записи автоматически\n");
        instruction.append("сохраняются в Google Sheets!");
        
        return instruction.toString();
    }

    private ReplyKeyboardMarkup getWelcomeKeyboard() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(true);
        
        List<KeyboardRow> keyboard = new java.util.ArrayList<>();
        
        KeyboardRow row = new KeyboardRow();
        row.add("🚀 Старт");
        
        keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }

    private ReplyKeyboardMarkup getHiddenKeyboard() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(false);
        
        List<KeyboardRow> keyboard = new java.util.ArrayList<>();
        
        KeyboardRow row = new KeyboardRow();
        row.add("📋 Показать меню");
        
        keyboard.add(row);
        markup.setKeyboard(keyboard);
        return markup;
    }

    private ReplyKeyboardMarkup getReplyKeyboard() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(false);
        
        List<KeyboardRow> keyboard = new java.util.ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Список");
        row1.add("📊 Статистика");
        row1.add("📈 График");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔒 Мои суммы");
        row2.add("📅 Фикс. платежи");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📈 Отчет");
        row3.add("⚙️ Настройки");
        row3.add("❓ Помощь");
        
        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);
        
        markup.setKeyboard(keyboard);
        return markup;
    }
    
    private InlineKeyboardMarkup getMainKeyboard(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();

        InlineKeyboardButton btnList = new InlineKeyboardButton();
        btnList.setText("📋 Список расходов");
        btnList.setCallbackData("cmd_list");
        
        InlineKeyboardButton btnStats = new InlineKeyboardButton();
        btnStats.setText("📊 Статистика");
        btnStats.setCallbackData("cmd_stats");
        
        List<InlineKeyboardButton> row1 = new java.util.ArrayList<>();
        row1.add(btnList);
        row1.add(btnStats);

        InlineKeyboardButton btnChart = new InlineKeyboardButton();
        btnChart.setText("📊 График");
        btnChart.setCallbackData("cmd_chart");
        
        InlineKeyboardButton btnHelp = new InlineKeyboardButton();
        btnHelp.setText("❓ Помощь");
        btnHelp.setCallbackData("cmd_help");
        
        List<InlineKeyboardButton> row2 = new java.util.ArrayList<>();
        row2.add(btnChart);
        row2.add(btnHelp);

        InlineKeyboardButton btnStatus = new InlineKeyboardButton();
        btnStatus.setText("ℹ️ Статус");
        btnStatus.setCallbackData("cmd_status");
        
        InlineKeyboardButton btnExport = new InlineKeyboardButton();
        btnExport.setText("📤 Экспорт");
        btnExport.setCallbackData("cmd_export");
        
        List<InlineKeyboardButton> row3 = new java.util.ArrayList<>();
        row3.add(btnStatus);
        row3.add(btnExport);

        InlineKeyboardButton btnSheets = new InlineKeyboardButton();
        btnSheets.setText("☁️ Настройка");
        btnSheets.setCallbackData("cmd_sheets");
        
        InlineKeyboardButton btnTable = new InlineKeyboardButton();
        btnTable.setText("📊 Моя таблица");

        String userSpreadsheetId = userSpreadsheetIds.get(chatId);
        if (userSpreadsheetId != null && !userSpreadsheetId.isEmpty()) {
            btnTable.setUrl("https://docs.google.com/spreadsheets/d/" + userSpreadsheetId + "/edit");
        } else {
            btnTable.setCallbackData("cmd_table");
        }
        
        List<InlineKeyboardButton> row4 = new java.util.ArrayList<>();
        row4.add(btnSheets);
        row4.add(btnTable);
        
        markup.setKeyboard(java.util.Arrays.asList(row1, row2, row3, row4));
        return markup;
    }
    
    private String handleCallback(long chatId, String callbackData) {

        if (callbackData.startsWith("del_recurring_")) {
            String idStr = callbackData.substring("del_recurring_".length());
            try {
                long id = Long.parseLong(idStr);
                boolean deleted = recurringStorage.deleteRecurringExpense(chatId, id);
                if (deleted) {
                    return "✅ Фиксированный платеж ID " + id + " удален!\n\n" +
                           "📋 Обновленный список: /lr";
                } else {
                    return "❌ Платеж не найден или уже удален";
                }
            } catch (NumberFormatException e) {
                return "❌ Ошибка: неверный ID";
            }
        }

        if (callbackData.startsWith("del_locked_")) {
            String category = callbackData.substring("del_locked_".length());
            boolean deleted = lockedAmountStorage.deleteLockedAmount(chatId, category);
            if (deleted) {
                return "✅ Фиксированная сумма для '" + category + "' удалена!\n\n" +
                       "📋 Обновленный список: /ll";
            } else {
                return "❌ Категория не найдена";
            }
        }

        if ("help_addrecurring".equals(callbackData)) {
            return "📅 КАК ДОБАВИТЬ ФИКСИРОВАННЫЙ ПЛАТЕЖ\n" +
                   "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                   "Используйте короткую команду:\n" +
                   "/ar <день> <тип> <категория> <сумма>\n\n" +
                   "📖 Примеры:\n" +
                   "• /ar 21 расход аренда 70000\n" +
                   "• /ar 1 доход зарплата 150000\n" +
                   "• /ar 15 расход интернет 500\n\n" +
                   "💡 Платеж будет автоматически добавляться каждый месяц!";
        }
        
        if ("help_locksum".equals(callbackData)) {
            return "🔒 КАК ЗАФИКСИРОВАТЬ СУММУ\n" +
                   "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                   "Используйте короткую команду:\n" +
                   "/ls <категория> <сумма>\n\n" +
                   "📖 Примеры:\n" +
                   "• /ls кофе 150\n" +
                   "• /ls обед 350\n" +
                   "• /ls метро 70\n\n" +
                   "💡 После этого просто пишите: 'расход кофе'\n" +
                   "И сумма подставится автоматически!";
        }
        
        switch (callbackData) {
            case "cmd_list":
                List<Expense> list = expenseService.listExpenses(chatId);
                if (list.isEmpty()) {
                    return "📝 Пока нет записей.\n\nИспользуйте /add или напишите 'расходы категория сумма'";
                }
                StringBuilder sb = new StringBuilder("📋 Ваши расходы:\n\n");
                double total = 0.0;
                for (int i = 0; i < list.size(); i++) {
                    Expense e = list.get(i);
                    sb.append(i + 1)
                            .append(". ")
                            .append(formatAmount(e.getAmount()))
                            .append(" ₽ — ")
                            .append(e.getNote())
                            .append('\n');
                    total += e.getAmount();
                }
                sb.append("\n💵 Итого: ").append(formatAmount(total)).append(" ₽");
                return sb.toString();
                
            case "cmd_stats":
                List<Expense> expenses = expenseService.listExpenses(chatId);
                if (expenses.isEmpty()) {
                    return "📊 Нет данных для статистики";
                }
                double totalStats = 0.0;
                int count = expenses.size();
                double max = 0.0;
                double min = Double.MAX_VALUE;
                
                for (Expense e : expenses) {
                    double amount = e.getAmount();
                    totalStats += amount;
                    if (amount > max) max = amount;
                    if (amount < min) min = amount;
                }
                double avg = totalStats / count;
                
                return "📊 СТАТИСТИКА:\n\n" +
                        "📈 Всего записей: " + count + "\n" +
                    "💵 Общая сумма: " + formatAmount(totalStats) + " ₽\n" +
                    "📉 Средняя сумма: " + formatAmount(avg) + " ₽\n" +
                    "⬆️ Максимум: " + formatAmount(max) + " ₽\n" +
                    "⬇️ Минимум: " + formatAmount(min) + " ₽";
                        
            case "cmd_help":
                return "📋 ДОСТУПНЫЕ КОМАНДЫ:\n\n" +
                        "💰 Управление расходами:\n" +
                        "• /add <сумма> <комментарий> — добавить расход\n" +
                        "• /list — показать все расходы\n" +
                        "• /stats — статистика по расходам\n\n" +
                        "☁️ Google Sheets:\n" +
                        "• /sheets — простая инструкция\n" +
                        "• /email — получить email бота\n" +
                        "• /setid <ID> — указать ID таблицы\n" +
                        "• /status — статус подключения\n\n" +
                        "💬 Текстовые команды:\n" +
                        "• 'расходы аренда 20000' — целое число\n" +
                        "• 'расходы кофе 150.50' — дробное число\n" +
                        "• 'доход продажа 5000' — доход (целое)\n" +
                        "• 'доход зарплата 80000' — доход";
                        
            case "cmd_status": {
                StringBuilder statusSb = new StringBuilder("📊 СТАТУС БОТА:\n\n");
                statusSb.append("📁 CSV хранилище: ✅ Активно\n");

                String userSpreadsheetId = userSpreadsheetIds.get(chatId);
                if (userSpreadsheetId != null && !userSpreadsheetId.isEmpty()) {
                    statusSb.append("☁️ Ваша Google Таблица: ✅ Настроена\n");
                    statusSb.append("   ID: ").append(userSpreadsheetId).append("\n");
                } else {
                    statusSb.append("☁️ Ваша Google Таблица: ❌ Не настроена\n");
                    statusSb.append("   Используйте /setid <ID>\n");
                }

                if (sheetsService != null) {
                    statusSb.append("\n☁️ Общая Google Sheets: ✅ Подключено");
                }
                
                List<Expense> statusExpenses = expenseService.listExpenses(chatId);
                statusSb.append("\n\n📝 Ваших записей: ").append(statusExpenses.size());
                return statusSb.toString();
            }
                
            case "cmd_chart": {
                List<Expense> chartExpenses = expenseService.listExpenses(chatId);
                if (chartExpenses.isEmpty()) {
                    return "📊 НЕТ ДАННЫХ ДЛЯ ГРАФИКА\n" +
                           "━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                           "Добавьте записи чтобы увидеть график.\n\n" +
                           "💡 Начните с:\n" +
                           "   • расходы кофе 150\n" +
                           "   • доходы зарплата 50000";
                }
                
                try {
                    System.out.println("Создаю график для пользователя " + chatId + " (callback)");
                    java.io.File chartFile = chartService.createIncomeExpenseBarChart(chartExpenses);
                    
                    SendPhoto photo = new SendPhoto();
                    photo.setChatId(String.valueOf(chatId));
                    photo.setPhoto(new InputFile(chartFile));
                    photo.setCaption("📊 ГРАФИК ДОХОДОВ И РАСХОДОВ\n\n" +
                                    "💰 Зеленый — доходы\n" +
                                    "💸 Красный — расходы");
                    photo.setReplyMarkup(getReplyKeyboard());
                    
                    execute(photo);
                    chartFile.delete();
                    
                    System.out.println("✓ График отправлен пользователю " + chatId);
                    return null;
                } catch (Exception e) {
                    System.err.println("❌ Ошибка создания графика: " + e.getMessage());
                    e.printStackTrace();
                    return "❌ Не удалось создать график.\n\nПопробуйте позже.";
                }
            }
                
            case "cmd_sheets":
                return getGoogleSheetsInstruction();
                
            case "cmd_table": {
                String userSpreadsheetId = userSpreadsheetIds.get(chatId);
                if (userSpreadsheetId == null || userSpreadsheetId.isEmpty()) {
                    return "❌ Таблица не настроена\n\n" +
                            "📋 Для настройки:\n" +
                            "1. Используйте /email чтобы получить email бота\n" +
                            "2. Добавьте email в таблицу как Editor\n" +
                            "3. Используйте /setid <ID> чтобы указать ID таблицы\n\n" +
                            "💡 Или используйте /sheets для полной инструкции";
                }
                String tableUrl = "https://docs.google.com/spreadsheets/d/" + userSpreadsheetId + "/edit";
                return "📊 ВАША GOOGLE ТАБЛИЦА:\n\n" +
                        "🔗 Ссылка:\n" +
                        tableUrl + "\n\n" +
                        "💡 Нажмите на ссылку чтобы открыть таблицу в браузере";
            }
                
            case "cmd_export":
                return "📤 ЭКСПОРТ ДАННЫХ:\n\n" +
                        "📁 CSV файл:\n" +
                        "• Путь: data/expenses.csv\n" +
                        "• Формат: ChatID,Date,Amount,Note\n" +
                        "• Можно открыть в Excel, Google Sheets\n\n" +
                        "☁️ Google Sheets:\n" +
                        (sheetsService != null ? 
                            "• ✅ Подключено - данные дублируются" : 
                            "• ❌ Не подключено - используйте /sheets");
                            
            default:
                return "Неизвестная команда";
        }
    }
}
