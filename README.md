# LP2LN Android

Android-клиент децентрализованного хранилища LP2LN. Текущий этап запускает полноценный
Rust-узел внутри приложения и показывает состояние сети в Jetpack Compose.

## Уже работает

- JNI-мост Kotlin ↔ Rust;
- `lp2ln-core-v2`, закреплённый на конкретной ревизии репозитория LP2LN;
- постоянная локальная БД и Peer ID в приватной директории приложения;
- TCP/UDP-узел, LAN discovery и подключение к bootstrap `83.136.233.187:18080`;
- статус runtime/health, число пиров и сессий, входящий/исходящий трафик;
- список активных соединений с транспортом и временем последней активности.

## Бренд

Интерфейс использует официальный знак LP2LN, тёмную палитру `#0B0D10` / `#1F2328`,
акценты `#2563FF` / `#00D4FF` / `#7B61FF`, светлый текст `#F2F4F7` и фирменную
типографику Space Grotesk. Для Android 13+ также добавлена монохромная themed icon.

Space Grotesk распространяется по SIL Open Font License 1.1; текст лицензии находится
в `third_party/space-grotesk/OFL.txt`.

## Сборка

Требуются Android Studio/SDK/NDK, Rust 1.85+ и `cargo-ndk`:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
./gradlew assembleDebug
```

Gradle сам собирает `rust/` для `arm64-v8a`, `armeabi-v7a` и `x86_64` и добавляет `.so`
в APK. Для проверки только Kotlin-кода без нативной библиотеки можно использовать
`./gradlew assembleDebug -PskipRustBuild`.

## Следующий этап

Поверх работающего узла можно добавлять индекс файлов, импорт через Android Storage
Access Framework, шифрование чанков, DHT-анонс, синхронизацию папок и репликацию между
доверенными устройствами.
