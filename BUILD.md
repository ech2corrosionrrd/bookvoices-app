# Збірка BookVoices

Локальний модуль `:app` (`app.bookvoices`). Тести — [`TESTING.md`](TESTING.md),
індекс — [`docs/README.md`](docs/README.md).

## З вихідників

```bat
set JAVA_HOME=<шлях до JDK 17>
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat :app:assembleDebug
```

На Linux/macOS — `./gradlew :app:assembleDebug`.

Демон Gradle — **JDK 17**. Готовий файл:
`app/build/outputs/apk/debug/app-debug.apk`.

### Реліз

```bat
gradlew.bat :app:assembleRelease -PreleaseTag=vX.Y.Z
gradlew.bat :app:bundleRelease -PreleaseTag=vX.Y.Z
```

У PowerShell беріть `-PreleaseTag=...` у лапки: `'-PreleaseTag=v1.12.0'`.

1. Скопіюйте `keystore.properties.example` → `keystore.properties`.
2. Покладіть `.jks` за шляхом у `storeFile` (типово `app/keystore/`).
3. Зберігайте `app/build/outputs/mapping/release/mapping.txt` для кожного тега.

Без `keystore.properties` release збереться непідписаним.

### GitHub Release

Тег `vX.Y.Z` → Actions збирає й (за наявності секретів) підписує
`bookvoices-X.Y.Z.apk`. Секрети репозиторію:

| Секрет | Призначення |
|---|---|
| `KEYSTORE_BASE64` | `.jks` у base64 |
| `KEYSTORE_PASSWORD` | пароль сховища |
| `KEY_ALIAS` | alias ключа |
| `KEY_PASSWORD` | пароль ключа |

### Play Console

Чекліст: [`docs/play-store/`](docs/play-store/). AAB з `bundleRelease` і **тим
самим** ключем, що GitHub Releases (Play App Signing — existing key).
