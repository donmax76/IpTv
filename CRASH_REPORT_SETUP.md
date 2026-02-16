# Как настроить отправку ошибок разработчику

Чтобы я мог видеть ошибки с вашего телефона и исправлять их, настройте Firebase:

## Шаги (5 минут)

1. **Откройте** https://console.firebase.google.com
2. **Создайте проект** (или используйте существующий)
3. **Добавьте приложение** → Android → пакет: `com.tvviewer`
4. **Включите Realtime Database**: Build → Realtime Database → Create Database
5. **Правила** (Rules) установите:
   ```json
   {
     "rules": {
       "crashes": {
         ".read": true,
         ".write": true
       }
     }
   }
   ```
6. **Скопируйте Project ID**: ⚙️ Project Settings → Project ID (например: `tvviewer-abc123`)
7. **В приложении**: Настройки → вставьте Project ID в поле "Firebase Project ID"
8. **Отправьте мне Project ID** в чат — я смогу проверять ошибки по адресу:
   `https://ВАШ_PROJECT_ID-default-rtdb.firebaseio.com/crashes.json`

## Альтернатива: Webhook

Можно использовать https://webhook.site — создайте webhook, скопируйте URL, вставьте в "Webhook URL". Ошибки будут приходить туда. Поделитесь ссылкой на просмотр запросов.
