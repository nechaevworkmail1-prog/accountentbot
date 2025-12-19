# Изменения для адаптации под Railway

## ✅ Выполненные изменения

### 1. Обновлены комментарии и сообщения
- Убраны специфичные упоминания только Render
- Добавлены универсальные инструкции для Railway/Render/Fly.io
- Обновлены сообщения об ошибках с рекомендациями для Railway

### 2. Обновлен config.properties
- Комментарии теперь универсальные (Railway, Render, Fly.io)
- Добавлена рекомендация использовать `GOOGLE_CREDENTIALS` (JSON в одной строке) для Railway

### 3. Создан railway.json
- Конфигурационный файл для Railway
- Настроен health check на `/health`
- Настроена политика перезапуска

### 4. Код уже готов к работе
- ✅ Health check endpoint (`/health`) - работает
- ✅ Поддержка переменной `PORT` - Railway устанавливает автоматически
- ✅ Поддержка `DATA_DIR` - установите `/data` для Railway Volume
- ✅ Keep-alive каждые 5 минут - работает
- ✅ Поддержка переменных окружения - все настроено

## 📋 Что нужно сделать на Railway

### Обязательные переменные окружения:
```
BOT_TOKEN=ваш_токен
BOT_USERNAME=accountant_bot
DATA_DIR=/data
KEEP_ALIVE_MODE=always
PORT=10000 (Railway установит автоматически, но можно указать явно)
```

### Опционально (для Google Sheets):
```
GOOGLE_CREDENTIALS={"type":"service_account",...}  # JSON в одной строке
GOOGLE_SPREADSHEET_ID=ваш_id
GOOGLE_SHEET_NAME=Sheet1
```

### Важно:
1. **Создайте Volume** с mount path `/data` (1 GB достаточно)
2. **Health check** должен быть настроен на `/health`
3. Railway автоматически определит Dockerfile

## 🚀 Готово к деплою!

Код полностью адаптирован под Railway. Следуйте инструкции в `RAILWAY_SETUP.md` для деплоя.

