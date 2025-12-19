package com.kct.accountant.gsheets;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GoogleSheetsService {
    private static final String APPLICATION_NAME = "AccountantBot";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final Sheets sheets;
    private final String spreadsheetId;
    private final String sheetName;

    private boolean headersChecked = false;
    
    public GoogleSheetsService(String credentialsPath, String spreadsheetId, String sheetName) {
        try {
            this.sheets = buildSheets(credentialsPath);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось инициализировать Google Sheets client: " + e.getMessage(), e);
        }
        this.spreadsheetId = spreadsheetId;
        this.sheetName = sheetName;
    }
    
    // Конструктор для переиспользования существующего Sheets объекта
    public GoogleSheetsService(Sheets sheets, String spreadsheetId, String sheetName) {
        this.sheets = sheets;
        this.spreadsheetId = spreadsheetId;
        this.sheetName = sheetName;
    }

    private Sheets buildSheets(String credentialsPath) throws GeneralSecurityException, IOException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath))
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));
        return new Sheets.Builder(httpTransport, JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    public Sheets getSheets() {
        return sheets;
    }
    
    public String getSpreadsheetId() {
        return spreadsheetId;
    }

    private void ensureHeaders() {
        try {
            ValueRange response = sheets.spreadsheets().values()
                    .get(spreadsheetId, sheetName + "!A1:E1")
                    .execute();
            
            List<List<Object>> values = response.getValues();

            boolean hasHeaders = false;
            if (values != null && !values.isEmpty() && !values.get(0).isEmpty()) {
                String firstCell = values.get(0).get(0).toString();

                if ("Дата".equals(firstCell) || "Date".equals(firstCell)) {
                    hasHeaders = true;
                }
            }
            
            if (!hasHeaders) {
                System.out.println("📋 Создаю заголовки в таблице...");
                createHeaders();
            } else {
                System.out.println("✓ Заголовки найдены в таблице");
            }
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 400) {
                System.err.println("⚠️ Лист '" + sheetName + "' не найден. Убедитесь, что лист существует и переименован в 'Sheet1'");
                System.err.println("   Ошибка: " + e.getMessage());
            } else {
                System.err.println("❌ Ошибка доступа к Google Sheets: " + e.getStatusCode() + " - " + e.getMessage());
            }
            System.out.println("ℹ️ Попытка создать заголовки...");
            try {
                createHeaders();
            } catch (Exception ex) {
                System.err.println("❌ Не удалось создать заголовки: " + ex.getMessage());
                throw new RuntimeException("Не удалось создать заголовки в таблице. Проверьте права доступа и существование листа.", ex);
            }
        } catch (Exception e) {
            System.out.println("ℹ️ Не удалось проверить заголовки, создаю новые...");
            System.err.println("   Ошибка: " + e.getMessage());
            try {
                createHeaders();
            } catch (Exception ex) {
                System.err.println("❌ Не удалось создать заголовки: " + ex.getMessage());
                throw new RuntimeException("Не удалось создать заголовки в таблице. Проверьте права доступа и существование листа.", ex);
            }
        }
    }

    private void createHeaders() throws IOException {
        List<List<Object>> values = List.of(
                List.of("Дата", "Тип", "Категория", "Сумма", "Валюта")
        );
        
        ValueRange body = new ValueRange().setValues(values);
        
        sheets.spreadsheets().values()
                .update(spreadsheetId, sheetName + "!A1:E1", body)
                .setValueInputOption("USER_ENTERED")
                .execute();
        
        System.out.println("✅ Заголовки созданы: Дата | Тип | Категория | Сумма | Валюта");
    }
    
    public void appendExpenseRow(LocalDate date, String type, String category, double amount, String currency) throws IOException {
        try {
            if (date == null) {
                throw new IllegalArgumentException("Дата не может быть null");
            }
            
            if (Double.isNaN(amount) || Double.isInfinite(amount)) {
                throw new IllegalArgumentException("Некорректная сумма: " + amount);
            }
            
            if (!headersChecked) {
                ensureHeaders();
                headersChecked = true;
            }
            
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IOException("Spreadsheet ID не указан");
            }
            
            if (sheetName == null || sheetName.isBlank()) {
                throw new IOException("Имя листа не указано");
            }
            
            DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            String amountStr;
            if (type != null && type.toLowerCase().contains("доход")) {
                amountStr = String.valueOf(amount);
            } else {
                amountStr = "-" + amount;
            }
            
            String safeType = type != null ? type : "";
            String safeCategory = category != null ? (category.length() > 100 ? category.substring(0, 97) + "..." : category) : "";
            String safeCurrency = currency != null && !currency.isBlank() ? currency : "RUB";
            
            List<List<Object>> values = List.of(
                    List.of(
                            df.format(date),
                            safeType,
                            safeCategory,
                            amountStr,
                            safeCurrency
                    )
            );
            
            System.out.println("📊 Попытка записи в Google Sheets:");
            System.out.println("   Таблица ID: " + spreadsheetId);
            System.out.println("   Лист: " + sheetName);
            System.out.println("   Данные: " + df.format(date) + " | " + safeType + " | " + safeCategory + " | " + amountStr + " | " + safeCurrency);
            
            ValueRange body = new ValueRange().setValues(values);
            String range = sheetName + "!A:E";
            
            sheets.spreadsheets().values()
                    .append(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute();
                    
            System.out.println("✅ Успешно записано в Google Sheets: " + safeType + ", " + safeCategory + ", " + amountStr + " " + safeCurrency);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            String errorMsg = "Ошибка записи в Google Sheets: ";
            if (e.getStatusCode() == 403) {
                errorMsg += "Нет доступа к таблице. Проверьте права доступа (нужна роль Editor)";
            } else if (e.getStatusCode() == 400) {
                errorMsg += "Лист '" + sheetName + "' не найден. Убедитесь, что лист существует";
            } else if (e.getStatusCode() == 404) {
                errorMsg += "Таблица не найдена. Проверьте правильность Spreadsheet ID";
            } else {
                errorMsg += "HTTP " + e.getStatusCode() + ": " + e.getMessage();
            }
            System.err.println("❌ " + errorMsg);
            System.err.println("   Таблица ID: " + spreadsheetId);
            System.err.println("   Лист: " + sheetName);
            throw new IOException(errorMsg, e);
        } catch (IOException e) {
            System.err.println("❌ Ошибка записи в Google Sheets:");
            System.err.println("   Сообщение: " + e.getMessage());
            System.err.println("   Таблица ID: " + spreadsheetId);
            System.err.println("   Лист: " + sheetName);
            throw e;
        } catch (Exception e) {
            System.err.println("❌ Неожиданная ошибка при записи в Google Sheets:");
            System.err.println("   Сообщение: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Ошибка записи в Google Sheets: " + e.getMessage(), e);
        }
    }
    
    public List<List<Object>> readAllRows() throws IOException {
        try {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IOException("Spreadsheet ID не указан");
            }
            
            if (sheetName == null || sheetName.isBlank()) {
                throw new IOException("Имя листа не указано");
            }
            
            String range = sheetName + "!A:E";
            System.out.println("📖 Чтение данных из Google Sheets:");
            System.out.println("   Таблица ID: " + spreadsheetId);
            System.out.println("   Лист: " + sheetName);
            System.out.println("   Диапазон: " + range);
            
            ValueRange response = sheets.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();
            
            List<List<Object>> values = response.getValues();
            
            if (values == null || values.isEmpty()) {
                System.out.println("ℹ️ Таблица пуста");
                return new ArrayList<>();
            }
            
            System.out.println("✅ Прочитано строк: " + values.size());
            return values;
        } catch (IOException e) {
            System.err.println("❌ Ошибка чтения из Google Sheets:");
            System.err.println("   Сообщение: " + e.getMessage());
            System.err.println("   Таблица ID: " + spreadsheetId);
            System.err.println("   Лист: " + sheetName);
            throw e;
        } catch (Exception e) {
            System.err.println("❌ Неожиданная ошибка при чтении из Google Sheets:");
            System.err.println("   Сообщение: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Ошибка чтения из Google Sheets: " + e.getMessage(), e);
        }
    }
    
    public List<List<Object>> readRows(int startRow, int endRow) throws IOException {
        try {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IOException("Spreadsheet ID не указан");
            }
            
            if (sheetName == null || sheetName.isBlank()) {
                throw new IOException("Имя листа не указано");
            }
            
            if (startRow < 1) {
                startRow = 1;
            }
            if (endRow < startRow) {
                endRow = startRow;
            }
            
            String range = sheetName + "!A" + startRow + ":E" + endRow;
            System.out.println("📖 Чтение данных из Google Sheets:");
            System.out.println("   Таблица ID: " + spreadsheetId);
            System.out.println("   Лист: " + sheetName);
            System.out.println("   Диапазон: " + range);
            
            ValueRange response = sheets.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();
            
            List<List<Object>> values = response.getValues();
            
            if (values == null || values.isEmpty()) {
                System.out.println("ℹ️ Указанный диапазон пуст");
                return new ArrayList<>();
            }
            
            System.out.println("✅ Прочитано строк: " + values.size());
            return values;
        } catch (IOException e) {
            System.err.println("❌ Ошибка чтения из Google Sheets:");
            System.err.println("   Сообщение: " + e.getMessage());
            System.err.println("   Таблица ID: " + spreadsheetId);
            System.err.println("   Лист: " + sheetName);
            throw e;
        } catch (Exception e) {
            System.err.println("❌ Неожиданная ошибка при чтении из Google Sheets:");
            System.err.println("   Сообщение: " + e.getMessage());
            e.printStackTrace();
            throw new IOException("Ошибка чтения из Google Sheets: " + e.getMessage(), e);
        }
    }
    
    public boolean testConnection() {
        try {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                System.err.println("❌ Spreadsheet ID не указан");
                return false;
            }
            
            if (sheetName == null || sheetName.isBlank()) {
                System.err.println("❌ Имя листа не указано");
                return false;
            }
            
            String range = sheetName + "!A1";
            sheets.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();
            
            System.out.println("✅ Соединение с Google Sheets успешно");
            return true;
        } catch (Exception e) {
            System.err.println("❌ Ошибка соединения с Google Sheets: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Читает данные из указанного листа
     */
    public List<List<Object>> readSheet(String targetSheetName) throws IOException {
        try {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IOException("Spreadsheet ID не указан");
            }
            
            if (targetSheetName == null || targetSheetName.isBlank()) {
                throw new IOException("Имя листа не указано");
            }
            
            String range = targetSheetName + "!A:Z";
            ValueRange response = sheets.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();
            
            List<List<Object>> values = response.getValues();
            return values != null ? values : new ArrayList<>();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            // Если лист не существует (ошибка 400), возвращаем пустой список
            if (e.getStatusCode() == 400) {
                String errorMessage = e.getMessage();
                if (errorMessage != null && (errorMessage.contains("Unable to parse range") || 
                                             errorMessage.contains("не найден"))) {
                    System.out.println("ℹ️ Лист '" + targetSheetName + "' не существует, возвращаю пустой список");
                    return new ArrayList<>();
                }
            }
            throw new IOException("Ошибка чтения листа '" + targetSheetName + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Ошибка чтения листа '" + targetSheetName + "': " + e.getMessage(), e);
        }
    }
    
    /**
     * Записывает данные в указанный лист и диапазон
     */
    public void writeSheet(String targetSheetName, String range, List<List<Object>> values) throws IOException {
        try {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IOException("Spreadsheet ID не указан");
            }
            
            if (targetSheetName == null || targetSheetName.isBlank()) {
                throw new IOException("Имя листа не указано");
            }
            
            String fullRange = targetSheetName + "!" + range;
            ValueRange body = new ValueRange().setValues(values);
            
            sheets.spreadsheets().values()
                    .update(spreadsheetId, fullRange, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            // Если лист не существует (ошибка 400), пробуем создать его через append
            if (e.getStatusCode() == 400) {
                String errorMessage = e.getMessage();
                if (errorMessage != null && errorMessage.contains("Unable to parse range")) {
                    System.out.println("ℹ️ Лист '" + targetSheetName + "' не существует, создаю через append");
                    // Пробуем создать лист через append (он создаст лист автоматически)
                    appendToSheet(targetSheetName, values);
                    return;
                }
            }
            throw new IOException("Ошибка записи в лист '" + targetSheetName + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Ошибка записи в лист '" + targetSheetName + "': " + e.getMessage(), e);
        }
    }
    
    /**
     * Добавляет данные в конец указанного листа
     */
    public void appendToSheet(String targetSheetName, List<List<Object>> values) throws IOException {
        try {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IOException("Spreadsheet ID не указан");
            }
            
            if (targetSheetName == null || targetSheetName.isBlank()) {
                throw new IOException("Имя листа не указано");
            }
            
            String range = targetSheetName + "!A:B";
            ValueRange body = new ValueRange().setValues(values);
            
            sheets.spreadsheets().values()
                    .append(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Ошибка добавления в лист '" + targetSheetName + "': " + e.getMessage(), e);
        }
    }
}
