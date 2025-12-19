package com.kct.accountant.charts;

import com.kct.accountant.model.Expense;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChartService {

    public File createExpensePieChart(List<Expense> expenses) throws IOException {
        DefaultPieDataset<String> dataset = new DefaultPieDataset<>();

        Map<String, Double> categoryTotals = new HashMap<>();
        for (Expense e : expenses) {
            if (!e.getNote().toLowerCase().startsWith("доход")) {
                String category = e.getNote().replace("расход: ", "").replace("расходы: ", "");
                categoryTotals.merge(category, e.getAmount(), Double::sum);
            }
        }

        for (Map.Entry<String, Double> entry : categoryTotals.entrySet()) {
            dataset.setValue(entry.getKey(), entry.getValue());
        }
        
        JFreeChart chart = ChartFactory.createPieChart(
                "Расходы по категориям",
                dataset,
                true,
                true,
                false
        );

        chart.setBackgroundPaint(Color.WHITE);
        @SuppressWarnings("unchecked")
        PiePlot<String> plot = (PiePlot<String>) chart.getPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlineStroke(new BasicStroke(1.0f));
        plot.setSectionOutlinesVisible(true);
        plot.setLabelFont(new Font("Arial", Font.PLAIN, 12));

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir", "."));
        String fileName = "chart_expenses_" + UUID.randomUUID().toString() + ".png";
        File chartFile = tempDir.resolve(fileName).toFile();
        ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600);
        
        return chartFile;
    }

    public File createIncomeExpenseBarChart(List<Expense> expenses) throws IOException {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        
        double totalIncome = 0.0;
        double totalExpense = 0.0;
        
        for (Expense e : expenses) {
            if (e.getNote().toLowerCase().startsWith("доход")) {
                totalIncome += e.getAmount();
            } else {
                totalExpense += e.getAmount();
            }
        }
        
        dataset.addValue(totalIncome, "Доходы", "");
        dataset.addValue(totalExpense, "Расходы", "");
        
        JFreeChart chart = ChartFactory.createBarChart(
                "Доходы и Расходы",
                "",
                "Сумма (₽)",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false
        );

        chart.setBackgroundPaint(Color.WHITE);
        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setOutlineStroke(new BasicStroke(1.0f));
        
        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setSeriesPaint(0, new Color(76, 175, 80));
        renderer.setSeriesPaint(1, new Color(244, 67, 54));

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir", "."));
        String fileName = "chart_income_expense_" + UUID.randomUUID().toString() + ".png";
        File chartFile = tempDir.resolve(fileName).toFile();
        ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600);
        
        return chartFile;
    }

    public File createCombinedChart(List<Expense> expenses) throws IOException {
        if (expenses.isEmpty()) {
            return null;
        }

        return createIncomeExpenseBarChart(expenses);
    }
}

