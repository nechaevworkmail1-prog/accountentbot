package com.kct.accountant.model;

public class RecurringExpense {
    private final long id;
    private final long chatId;
    private final String type;
    private final String category;
    private final double amount;
    private final String currency;
    private final int dayOfMonth;
    private final boolean active;

    public RecurringExpense(long id, long chatId, String type, String category, 
                          double amount, String currency, int dayOfMonth, boolean active) {
        this.id = id;
        this.chatId = chatId;
        this.type = type;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
        this.dayOfMonth = dayOfMonth;
        this.active = active;
    }

    public long getId() {
        return id;
    }

    public long getChatId() {
        return chatId;
    }

    public String getType() {
        return type;
    }

    public String getCategory() {
        return category;
    }

    public double getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public int getDayOfMonth() {
        return dayOfMonth;
    }

    public boolean isActive() {
        return active;
    }
}

