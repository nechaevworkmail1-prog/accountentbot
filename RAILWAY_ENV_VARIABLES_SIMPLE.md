# Настройка переменных окружения в Railway - Упрощенная версия

## ✅ Обязательные переменные (минимум):

```
BOT_TOKEN=ваш_токен_от_BotFather
DATA_DIR=/data
```

## ⚙️ Рекомендуемые переменные (для production):

```
BOT_USERNAME=accountant_bot        # Опциональна (дефолт: accountant_bot)
KEEP_ALIVE_MODE=always              # Опциональна (дефолт: always)
PORT=10000                          # Опциональна (Railway установит автоматически)
```

## ☁️ Для Google Sheets (опционально):

```
GOOGLE_CREDENTIALS={"type":"service_account",...}  # JSON в одной строке ⭐ Рекомендуется
GOOGLE_SHEET_NAME=Sheet1                            # Опциональна (дефолт: Sheet1, обычно не требуется)
```

**⚠️ ВАЖНО:** 
- Бот поддерживает **несколько таблиц** - каждый пользователь указывает свою через `/setid`
- `GOOGLE_SPREADSHEET_ID` **НЕ нужна** - пользователи сами устанавливают свои таблицы

---

## ❌ Переменные, которые НЕ нужны:

- `GOOGLE_CREDENTIALS_PATH` - используйте `GOOGLE_CREDENTIALS` вместо этого
- `GOOGLE_SPREADSHEET_ID` - не нужна, пользователи сами устанавливают таблицы
- `STORAGE_CSV_PATH` - избыточна, формируется автоматически из `DATA_DIR`

---

**Подробная инструкция:** См. `RAILWAY_ENV_VARIABLES.md`

