package com.kct.accountant.model;

import java.time.Instant;

public class Expense {
    private final long chatId;
    private final double amount;
    private final String note;
    private final Instant createdAt;
    private final String currency;

    public Expense(long chatId, double amount, String note, Instant createdAt) {
        this(chatId, amount, note, createdAt, "RUB");
    }

    public Expense(long chatId, double amount, String note, Instant createdAt, String currency) {
        this.chatId = chatId;
        this.amount = amount;
        this.note = note;
        this.createdAt = createdAt;
        this.currency = currency != null ? currency : "RUB";
    }

    public long getChatId() {
        return chatId;
    }

    public double getAmount() {
        return amount;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCurrency() {
        return currency;
    }
}
