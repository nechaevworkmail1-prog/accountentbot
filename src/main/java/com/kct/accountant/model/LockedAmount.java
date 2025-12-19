package com.kct.accountant.model;

public class LockedAmount {
    private final long chatId;
    private final String category;
    private final double amount;
    private final String currency;

    public LockedAmount(long chatId, String category, double amount, String currency) {
        this.chatId = chatId;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
    }

    public long getChatId() {
        return chatId;
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
}

