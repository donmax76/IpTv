# Как отправить обновления в репозиторий IpTv

Программа настроена на проверку обновлений из https://github.com/donmax76/IpTv

Чтобы обновить репозиторий IpTv:

1. Клонируйте IpTv (если ещё не клонирован):
   ```bash
   git clone https://github.com/donmax76/IpTv.git
   cd IpTv
   ```

2. Скопируйте содержимое TVViewer в IpTv:
   ```bash
   cp -r /path/to/TestApp/TVViewer/* .
   cp /path/to/TestApp/TVViewer/app/build/outputs/apk/debug/app-debug.apk .
   ```

3. Обновите version.json (в корне):
   ```json
   {"versionCode":20,"versionName":"4.4","downloadUrl":"https://raw.githubusercontent.com/donmax76/IpTv/main/app-debug.apk"}
   ```

4. Добавьте APK принудительно (он в .gitignore):
   ```bash
   git add -f app-debug.apk
   git add .
   git commit -m "TVViewer 4.4"
   git push origin main
   ```

Ссылка на скачивание APK после push:
https://raw.githubusercontent.com/donmax76/IpTv/main/app-debug.apk
