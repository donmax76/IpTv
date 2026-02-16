#!/bin/bash
# Скрипт сборки APK (требует Android SDK и Java 17)

# Если Java 17 не установлена, можно скачать:
# curl -sL "https://download.java.net/java/GA/jdk17.0.2/dfd4a8d0985749f896bed50d7138ee7f/8/GPL/openjdk-17.0.2_linux-x64_bin.tar.gz" -o /tmp/jdk17.tar.gz
# tar xzf /tmp/jdk17.tar.gz -C /tmp

if [ -d /tmp/jdk-17.0.2 ]; then
  export JAVA_HOME=/tmp/jdk-17.0.2
  export PATH=$JAVA_HOME/bin:$PATH
fi

# Укажите путь к Android SDK в local.properties
./gradlew assembleDebug
