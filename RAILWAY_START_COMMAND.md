# Настройка Custom Start Command в Railway

## Текущая конфигурация

У вас уже настроена команда запуска в двух местах:

1. **Dockerfile** (строка 25):
   ```dockerfile
   CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]
   ```

2. **railway.json** (строка 8):
   ```json
   "startCommand": "java -Xmx512m -Xms256m -jar app.jar"
   ```

## Что делать в Railway Dashboard

### Вариант 1: Оставить пустым (рекомендуется) ✅

**Если Custom Start Command пустое:**
- Railway автоматически использует команду из `railway.json` или `Dockerfile`
- Это **рекомендуемый вариант** - команда уже настроена в коде

**Где проверить:**
1. Railway Dashboard → Ваш проект → Service → Settings
2. Найдите раздел **"Deploy"** или **"Start Command"**
3. Если поле пустое - это нормально, Railway использует команду из `railway.json`

---

### Вариант 2: Указать явно (если нужно переопределить)

**Если нужно указать Custom Start Command явно:**

В Railway Dashboard → Settings → Deploy → Custom Start Command:

```
java -Xmx512m -Xms256m -jar app.jar
```

**Или с дополнительными параметрами (если нужно):**
```
java -Xmx512m -Xms256m -XX:+UseG1GC -jar app.jar
```

---

## Параметры команды

### `-Xmx512m`
- Максимальная память: 512 MB
- Подходит для Telegram бота
- Можно увеличить до `-Xmx1024m` если нужно

### `-Xms256m`
- Начальная память: 256 MB
- Помогает избежать частых выделений памяти

### `-jar app.jar`
- Запускает JAR файл
- Main class: `com.kct.accountant.Main` (указан в pom.xml)

---

## Приоритет команд в Railway

Railway использует команды в следующем порядке:

1. **Custom Start Command** (из UI) - самый высокий приоритет
2. **railway.json** → `deploy.startCommand`
3. **Dockerfile** → `CMD`

**Рекомендация:** Оставьте Custom Start Command пустым, чтобы использовать команду из `railway.json` или `Dockerfile`.

---

## Проверка работы

После деплоя проверьте логи:

1. Railway Dashboard → Ваш проект → Service → Logs
2. Должны увидеть:
   ```
   ✓ Health check server started on port 10000
   📁 Базовая директория данных: /data
   CSV хранилище инициализировано: /data/expenses.csv
   Accountant Bot started
   ```

Если видите эти сообщения - команда запуска работает правильно!

---

## Если возникли проблемы

### Проблема: "Unable to access jarfile app.jar"

**Решение:**
1. Проверьте, что сборка прошла успешно
2. Убедитесь, что JAR файл создан: `target/accountant-bot-1.0-SNAPSHOT-jar-with-dependencies.jar`
3. Проверьте, что в Dockerfile путь правильный

### Проблема: "OutOfMemoryError"

**Решение:**
Увеличьте память в Custom Start Command:
```
java -Xmx1024m -Xms512m -jar app.jar
```

### Проблема: Бот не запускается

**Решение:**
1. Проверьте логи на ошибки
2. Убедитесь, что все переменные окружения установлены
3. Проверьте, что Volume создан и смонтирован в `/data`

---

## Итоговая рекомендация

✅ **Оставьте Custom Start Command пустым** - Railway автоматически использует команду из `railway.json`:
```json
"startCommand": "java -Xmx512m -Xms256m -jar app.jar"
```

Это самый простой и правильный вариант!

