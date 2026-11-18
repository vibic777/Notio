# Notio — единый проект Android и Windows

Этот репозиторий объединяет Android и Windows варианты Notio в одном Git/Gradle-проекте. Общую логику переносим постепенно; платформенные интерфейсы и системные функции сохраняем в Android и Windows слоях.

## Сборка

Из корня репозитория:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease
.\gradlew.bat :desktopApp:release :desktopApp:packageExe
.\gradlew.bat :desktopApp:syncAndroidStrings
```

Целевая общая поставка всех пяти форматов запускается одной командой:

```powershell
.\gradlew.bat :packageDelivery
```

Нужен `local.properties` с Android SDK; он игнорируется Git. Ключ подписи Android задаётся в `android/keystore.properties`, ключ и пароли не добавлять в репозиторий.

## Цель поставки

Одна версия исходного кода должна собирать пять файлов:

| Платформа | Формат | Назначение |
| --- | --- | --- |
| Android | Debug APK | Установка и проверка разработческой сборки |
| Android | Release APK | Пользовательский APK, подписанный настроенным ключом выпуска |
| Windows | EXE | Установщик Windows |
| Windows | MSI | Установщик Windows для MSI-сценариев |
| Windows | Portable ZIP | Приложение со встроенным runtime, запускаемое после распаковки |

Команда общей поставки: `gradlew.bat :packageDelivery`, файлы появляются в `dist/`. У платформ свои линии версий (`version.properties`): Android `android.version`, Windows `windows.version`.

## Документы

- [Список изменений](CHANGELOG.md)
- [Android: сборка и стек](android/docs/DEV_SETUP.md), [описание приложения](android/README_RU.md)
- [Windows: сборка и стек](windows/DEV_SETUP.md), [описание приложения](windows/README.md)
- [Правила вклада](CONTRIBUTING.md)
- [Лицензия PolyForm Noncommercial 1.0.0](LICENSE), [сторонние компоненты](NOTICE.md), [безопасность](SECURITY.md)

## Статус

Android 0.9.122 и Windows 0.9.67 (2026-11-13) собираются из одного Gradle-корня одной командой; у платформ свои линии версий. Windows повторяет функции Android. Общие Kotlin-файлы постепенно выделяются в `shared/src`. Лицензия — [PolyForm Noncommercial 1.0.0](LICENSE): некоммерческое использование свободно, коммерческое — по отдельному соглашению с vibic777.
