package com.kct.accountant.storage;

import com.kct.accountant.model.LockedAmount;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class LockedAmountStorage {
    private final Path filePath;

    public LockedAmountStorage(String path) {
        this.filePath = Paths.get(path);
        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                Files.createFile(filePath);
                System.out.println("Создан файл: " + filePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Не удалось инициализировать LockedAmountStorage", e);
        }
    }

    public void setLockedAmount(long chatId, String category, double amount, String currency) {
        Map<String, LockedAmount> amounts = getLockedAmounts(chatId).stream()
                .collect(Collectors.toMap(
                        la -> la.getCategory().toLowerCase(),
                        la -> la,
                        (a, b) -> b
                ));

        amounts.put(category.toLowerCase(), new LockedAmount(chatId, category, amount, currency));

        saveAllLockedAmounts(getAllLockedAmounts().stream()
                .filter(la -> la.getChatId() != chatId)
                .collect(Collectors.toList()),
                new ArrayList<>(amounts.values()));
    }

    public Optional<LockedAmount> getLockedAmount(long chatId, String category) {
        return getLockedAmounts(chatId).stream()
                .filter(la -> la.getCategory().equalsIgnoreCase(category))
                .findFirst();
    }

    public List<LockedAmount> getLockedAmounts(long chatId) {
        return getAllLockedAmounts().stream()
                .filter(la -> la.getChatId() == chatId)
                .collect(Collectors.toList());
    }

    private List<LockedAmount> getAllLockedAmounts() {
        List<LockedAmount> amounts = new ArrayList<>();
        
        try {
            if (!Files.exists(filePath) || Files.size(filePath) == 0) {
                return amounts;
            }
            
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                try {
                    String[] parts = line.split(",", 4);
                    if (parts.length >= 4) {
                        long chatId = Long.parseLong(parts[0].trim());
                        String category = parts[1].trim();
                        double amount = Double.parseDouble(parts[2].trim());
                        String currency = parts[3].trim();
                        
                        amounts.add(new LockedAmount(chatId, category, amount, currency));
                    }
                } catch (Exception e) {
                    System.err.println("Ошибка парсинга строки: " + line + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("❌ Ошибка чтения фиксированных сумм: " + e.getMessage());
        }
        
        return amounts;
    }

    private void saveAllLockedAmounts(List<LockedAmount> existing, List<LockedAmount> newOnes) {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (LockedAmount la : existing) {
                writer.write(String.format("%d,%s,%.2f,%s%n",
                        la.getChatId(), la.getCategory(), la.getAmount(), la.getCurrency()));
            }
            for (LockedAmount la : newOnes) {
                writer.write(String.format("%d,%s,%.2f,%s%n",
                        la.getChatId(), la.getCategory(), la.getAmount(), la.getCurrency()));
            }
        } catch (IOException e) {
            System.err.println("❌ Ошибка сохранения фиксированных сумм: " + e.getMessage());
        }
    }

    public boolean deleteLockedAmount(long chatId, String category) {
        List<LockedAmount> all = getAllLockedAmounts().stream()
                .filter(la -> !(la.getChatId() == chatId && la.getCategory().equalsIgnoreCase(category)))
                .collect(Collectors.toList());
        
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (LockedAmount la : all) {
                writer.write(String.format("%d,%s,%.2f,%s%n",
                        la.getChatId(), la.getCategory(), la.getAmount(), la.getCurrency()));
            }
            return true;
        } catch (IOException e) {
            System.err.println("❌ Ошибка удаления фиксированной суммы: " + e.getMessage());
            return false;
        }
    }
}

