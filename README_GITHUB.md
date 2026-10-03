RostikAI v31.1.3 — GitHub build

Самый простой вариант

Распакуй RostikAI-v31.1.3-GITHUB-READY-V3.zip в локальную папку, создай/открой GitHub repository и загрузи содержимое папки в корень репозитория.

Внутри уже есть:

Android source code;

app/;

39 Kotlin-файлов;

108 brawler icons;

88 Brawl Ball maps;

.github/workflows/build.yml.

После git push GitHub Actions автоматически проверит и соберёт APK.

Если нужен веб-загрузчик GitHub

Если исходники загружаются частями из-за ограничения интерфейса, используй четыре ZIP из комплекта RostikAI-MERGED-GITHUB-25MB-V3 и отдельный build.yml.

ZIP-файлы не распаковываются в GitHub вручную. Workflow сам проверяет SHA256, проверяет архивы и собирает исходник.

Что проверяет CI

наличие Android-проекта;

108 бойцов в каталоге;

108 PNG-иконок;

88 карт Brawl Ball;

отсутствие TODO/FIXME/NotImplementedException/UnsupportedOperationException;

source SHA256 manifest;

Java 17;

Android SDK 35;

Gradle 8.10.2;

compileDebugKotlin;

lintDebug;

assembleDebug;

assembleRelease;

целостность обоих APK;

наличие карт и brawler assets внутри release APK.

Артефакты

После успешного workflow в GitHub Actions появится:

RostikAI-v31.1.3-APKs/app-debug.apk

RostikAI-v31.1.3-APKs/app-release.apk

Важное различие

SOURCE_PREFLIGHT_PASS доказывает целостность исходников и ресурсов. Реальный Android compile подтверждается только зелёным GitHub Actions. Работа на настоящем устройстве дополнительно требует установки APK и проверки Brawl Stars.

Deep UI/control audit

Before Android compilation, CI runs both tools/verify_source.py and
tools/deep_audit.py. The deep audit checks the critical overlay controls,
true multi-digit trophy editing, 0..10000 trophy range, START/PAUSE/STOP
contracts, the match movement heartbeat, gesture completion/timeout release,
and MediaProjection no-redelivery behavior.

The trophy editor accepts either direct multi-digit keyboard input (including
paste) or the built-in keypad. Built-in controls provide quick values
0 / 100 / 500 / 1000 / 5000 / MAX and step controls -100 / -10 / -1 /
+1 / +10 / +100 without rebuilding the overlay after every digit.
