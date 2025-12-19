# Быстрая настройка Railway - Краткая версия

## Обязательные переменные (минимум):

```
BOT_TOKEN=ваш_токен_от_BotFather  ⭐ ОБЯЗАТЕЛЬНА
DATA_DIR=/data                     ⭐ ОБЯЗАТЕЛЬНА для Railway
```

## Рекомендуемые переменные (для production):

```
BOT_USERNAME=accountant_bot        (опциональна, дефолт: accountant_bot)
KEEP_ALIVE_MODE=always             (опциональна, дефолт: always)
PORT=10000                         (опциональна, Railway установит автоматически)
```

## Для Google Sheets (опционально):

```
GOOGLE_CREDENTIALS={"type":"service_account",...}  # JSON в одной строке ⭐ Рекомендуется
GOOGLE_SHEET_NAME=Sheet1                            # Опциональна (дефолт: Sheet1, обычно не требуется)
```

**⚠️ ВАЖНО:** 
- `GOOGLE_SPREADSHEET_ID` **НЕ обязателен**!
- Бот поддерживает **несколько таблиц** - каждый пользователь указывает свою через `/setid`
- `GOOGLE_SPREADSHEET_ID` используется только как fallback (если пользователь не указал свою)

## Как пользователи устанавливают свои таблицы:

1. Пользователь создает Google Таблицу
2. Дает доступ Service Account (email можно узнать через `/email` в боте)
3. Отправляет боту: `/setid <ID_таблицы>` или просто ID таблицы
4. Готово! Бот запоминает ID для этого пользователя

## Где хранятся ID таблиц:

- Файл: `/data/user_spreadsheet_ids.txt` (формат: `chatId=spreadsheetId`)
- Также синхронизируется с Google Sheets (лист "Users")

---

**Подробная инструкция:** См. `RAILWAY_ENV_VARIABLES.md`

