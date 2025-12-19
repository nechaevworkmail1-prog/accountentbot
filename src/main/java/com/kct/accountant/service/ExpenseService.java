package com.kct.accountant.service;

import com.kct.accountant.model.Expense;
import com.kct.accountant.storage.StorageService;

import java.time.Instant;
import java.util.List;

public class ExpenseService {
    private final StorageService storageService;

    public ExpenseService(StorageService storageService) {
        this.storageService = storageService;
    }

    public Expense addExpense(long chatId, double amount, String note) {
        Expense expense = new Expense(chatId, amount, note, Instant.now());
        storageService.addExpense(expense);
        return expense;
    }

    public List<Expense> listExpenses(long chatId) {
        return storageService.getExpenses(chatId);
    }
}
