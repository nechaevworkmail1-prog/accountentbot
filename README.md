![Java](https://img.shields.io/badge/Java-17-orange)
![Telegram API](https://img.shields.io/badge/Telegram%20API-Bot-blue)
![Docker](https://img.shields.io/badge/Docker-Ready-2496ED)
![Maven](https://img.shields.io/badge/Maven-3.8-red)
[![Railway](https://img.shields.io/badge/Deployed%20on-Railway-purple)](https://railway.app)
# 🤖 AccountantBot / Telegram Bot для финансового учета


## 📌 Описание

**AccountantBot** — это Telegram-бот для учета личных финансов, полностью написанный на Java. Он позволяет пользователям отслеживать доходы и расходы, вести бюджет и получать статистику прямо в Telegram. Проект создан с нуля для демонстрации навыков backend-разработки на Java, работы с внешними API и контейнеризации.

## ✨ Функциональность

*   **Учет транзакций:** Добавление доходов/расходов по категориям.
*   **Запрос статистики:** Получение сводки за день/неделю/месяц.
*   **Управление бюджетом:** Установка лимитов по категориям.
*   **Интеграция с Google Sheets:** Выгрузка данных в таблицу для удобного анализа.
*   **Асинхронная обработка:**  Ответы бота не блокируют друг друга.

## 🛠 Стек технологий

*   **Язык:** Java 17
*   **Сборщик:** Maven
*   **Основные библиотеки:** 
    *   [TelegramBots](https://github.com/rubenlagus/TelegramBots)
    *   Google Sheets API 
    *   SLF4J + Logback для логирования
*   **Инфраструктура:** Docker, переменные окружения для конфигурации
*   **Деплой:** Адаптирован для запуска на Railway/Render

## 🐳 Запуск с помощью Docker

Это самый простой способ запустить бота.

1.  **Клонируй репозиторий:**
    `git clone https://github.com/nechaevworkmail1-prog/accountentbot`

2.  **Создай файл `.env`** и укажи в нем переменные окружения (токен бота и т.д.):
    ```
    BOT_TOKEN=твой_токен_от_BotFather
    GOOGLE_SHEETS_CREDENTIALS=... (если есть)
    ```

3.  **Запусти через Docker Compose  или собери образ:**
    `docker build -t accountentbot .`
    `docker run --env-file .env accountentbot`

## 📦 Сборка и запуск без Docker (Maven)

```bash
mvn clean package
java -jar target/accountentbot-1.0.0-jar-with-dependencies.jar

🌍 Деплой в облаке
Проект полностью адаптирован для деплоя на платформы типа Railway и Render. В корне есть файлы railway.json и render.yaml, которые содержат необходимые настройки. Используются переменные окружения для хранения чувствительных данных (токенов).

📂 Структура проекта

src/main/java/...  # Исходный код бота
Dockerfile         # Инструкция для сборки образа
pom.xml            # Зависимости Maven
railway.json       # Конфиг для Railway


📞 Контакты
Твои контакты (HH: https://ekaterinburg.hh.ru/resume/9a143089ff0f6d6aea0039ed1f777663713056,
Telegram @TWIN_TURB0)
