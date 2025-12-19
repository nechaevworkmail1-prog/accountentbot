package com.kct.accountant.parser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuCommandParser {
    public static class ParsedRecord {
        public final String type;
        public final String category;
        public final double amount;
        public final String currency;
        public final boolean amountSpecified;

        public ParsedRecord(String type, String category, double amount, String currency, boolean amountSpecified) {
            this.type = type;
            this.category = category;
            this.amount = amount;
            this.currency = currency;
            this.amountSpecified = amountSpecified;
        }
    }

    private static final Pattern PATTERN_WITH_AMOUNT = Pattern.compile(
            "^\\n?\\s*(расходы?|доходы?)\\s+([\\p{L}\\s]+?)\\s+(\\d+[\\d\\s.,]*?)\\s*(?:(руб|р|₽|usd|\\$|eur|€|gbp|£|cny|¥))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern PATTERN_WITHOUT_AMOUNT = Pattern.compile(
            "^\\n?\\s*(расходы?|доходы?)\\s+([\\p{L}\\s]+?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Map<String, String> CURRENCY_MAP = new HashMap<>();
    static {
        CURRENCY_MAP.put("руб", "RUB");
        CURRENCY_MAP.put("р", "RUB");
        CURRENCY_MAP.put("₽", "RUB");
        CURRENCY_MAP.put("rub", "RUB");
        
        CURRENCY_MAP.put("usd", "USD");
        CURRENCY_MAP.put("$", "USD");
        CURRENCY_MAP.put("доллар", "USD");
        
        CURRENCY_MAP.put("eur", "EUR");
        CURRENCY_MAP.put("€", "EUR");
        CURRENCY_MAP.put("евро", "EUR");
        
        CURRENCY_MAP.put("gbp", "GBP");
        CURRENCY_MAP.put("£", "GBP");
        CURRENCY_MAP.put("фунт", "GBP");
        
        CURRENCY_MAP.put("cny", "CNY");
        CURRENCY_MAP.put("¥", "CNY");
        CURRENCY_MAP.put("юань", "CNY");
    }

    public Optional<ParsedRecord> parse(String text) {
        if (text == null) return Optional.empty();
        
        Matcher m = PATTERN_WITH_AMOUNT.matcher(text.trim());
        if (m.matches()) {
            String rawType = m.group(1).toLowerCase(Locale.ROOT);
            String type = rawType.startsWith("доход") ? "доход" : "расход";
            String category = m.group(2).trim();
            String amountStr = m.group(3).replace(" ", "").replace(",", ".");
            double amount;
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
            
            String currencyRaw = m.group(4);
            String currency = "RUB";
            if (currencyRaw != null && !currencyRaw.isEmpty()) {
                currency = CURRENCY_MAP.getOrDefault(currencyRaw.toLowerCase(), "RUB");
            }
            
            return Optional.of(new ParsedRecord(type, category, amount, currency, true));
        }
        
        m = PATTERN_WITHOUT_AMOUNT.matcher(text.trim());
        if (m.matches()) {
            String rawType = m.group(1).toLowerCase(Locale.ROOT);
            String type = rawType.startsWith("доход") ? "доход" : "расход";
            String category = m.group(2).trim();
            
            return Optional.of(new ParsedRecord(type, category, 0.0, "RUB", false));
        }
        
        return Optional.empty();
    }

    public static String normalizeCurrency(String currency) {
        if (currency == null) return "RUB";
        return CURRENCY_MAP.getOrDefault(currency.toLowerCase(), currency.toUpperCase());
    }

    public static String getCurrencySymbol(String currency) {
        switch (currency.toUpperCase()) {
            case "RUB": return "₽";
            case "USD": return "$";
            case "EUR": return "€";
            case "GBP": return "£";
            case "CNY": return "¥";
            default: return currency;
        }
    }
}
