package com.kct.accountant.storage;

import com.kct.accountant.model.Expense;

import java.util.List;

public interface StorageService {
    void addExpense(Expense expense);
    List<Expense> getExpenses(long chatId);
}

