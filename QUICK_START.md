# Быстрый старт - Accountant Bot

## Текущее состояние

✅ **Код уже адаптирован под Render:**
- Health check endpoint (`/health`)
- Keep-alive каждые 5 минут
- Поддержка переменных окружения
- Поддержка Render Disk для данных

## Если Render не подошел - что делать?

### Вариант 1: Исправить проблемы на Render

1. Откройте `RENDER_SETUP.md` - там подробная инструкция
2. Проверьте основные моменты:
   - ✅ Используете **Starter план** (не Free) для 24/7 работы
   - ✅ Настроен **Render Disk** для сохранения CSV файлов
   - ✅ Все переменные окружения установлены
   - ✅ Health check работает на `/health`

### Вариант 2: Перейти на Railway (рекомендуется)

**Почему Railway:**
- Проще настройка
- Лучшая цена ($5-20/мес)
- Меньше проблем с cold starts
- Бесплатный пробный период

**Шаги:**
1. Создайте аккаунт на [railway.app](https://railway.app)
2. Создайте новый проект
3. Подключите GitHub репозиторий
4. Railway автоматически определит Dockerfile
5. Настройте переменные окружения (те же, что для Render)
6. Добавьте Persistent Volume для директории `data/`
7. Деплой!

**Переменные окружения для Railway:**
```
BOT_TOKEN=ваш_токен
BOT_USERNAME=accountant_bot
PORT=10000
DATA_DIR=/data
KEEP_ALIVE_MODE=always
GOOGLE_CREDENTIALS=... (JSON строка, опционально)
GOOGLE_SPREADSHEET_ID=... (опционально)
```

### Вариант 3: Fly.io (для производительности)

1. Установите Fly CLI: `curl -L https://fly.io/install.sh | sh`
2. Создайте приложение: `fly launch`
3. Настройте volume: `fly volumes create data --size 1`
4. Настройте переменные окружения в `fly.toml`
5. Деплой: `fly deploy`

## Общие переменные окружения

Для любого хоста нужны:

**Обязательные:**
- `BOT_TOKEN` - токен Telegram бота
- `BOT_USERNAME` - username бота
- `PORT` - порт для health check (обычно устанавливается хостом)
- `DATA_DIR` - путь к директории данных (зависит от хоста)
- `KEEP_ALIVE_MODE=always` - для 24/7 работы

**Опционально (для Google Sheets):**
- `GOOGLE_CREDENTIALS` - JSON содержимое credentials (или)
- `GOOGLE_CREDENTIALS_PATH` - путь к файлу credentials.json
- `GOOGLE_SPREADSHEET_ID` - ID таблицы
- `GOOGLE_SHEET_NAME=Sheet1` - имя листа

## Сравнение хостов

| Хост | Цена | Сложность | Рекомендация |
|------|------|-----------|--------------|
| **Railway** | $5-20 | ⭐ Легко | ✅ Лучший выбор |
| **Render** | $7-25 | ⭐ Легко | ✅ Если уже настроен |
| **Fly.io** | $5-15 | ⭐⭐ Средне | ✅ Для производительности |
| **VPS** | $5-10 | ⭐⭐⭐ Сложно | ✅ Для полного контроля |

## Документация

- `HOSTING_RECOMMENDATIONS.md` - детальное сравнение всех хостов
- `RENDER_SETUP.md` - подробная инструкция по Render
- `render.yaml` - конфигурация для Render (если используете)

## Вопросы?

Если возникли проблемы:
1. Проверьте логи на хосте
2. Убедитесь, что все переменные окружения установлены
3. Проверьте, что persistent storage настроен (для CSV файлов)
4. Убедитесь, что health check работает (`/health` endpoint)

