# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Item Flow Monitor** — Fabric мод для Minecraft 1.21.11 (Java 21). Отслеживает поток предметов в контейнерах (сундуки, воронки, бочки, печки и т.д.) через UI-оверлей. Никаких новых блоков — только Mixin + GUI поверх стандартных контейнеров.

## Build & Deploy

```bash
# Сборка
./gradlew build
# Результат: build/libs/itemflowmonitor-0.1.0.jar

# Копирование в тестовый инстанс (PrismLauncher, Windows path через WSL)
cp build/libs/itemflowmonitor-0.1.0.jar "/mnt/c/Users/fy82/AppData/Roaming/PrismLauncher/instances/ModDevelop/minecraft/mods/"
```

**Тестовый инстанс:** `C:\Users\fy82\AppData\Roaming\PrismLauncher\instances\ModDevelop\minecraft` (PrismLauncher)

## Tech Stack

- **Minecraft:** 1.21.11, **Fabric Loader:** 0.18.4, **Fabric API:** 0.141.2, **Loom:** 1.14
- **Java:** 21, **Mappings:** Mojang official
- **Access Widener:** `itemflowmonitor.accesswidener` (leftPos, topPos, imageWidth, imageHeight, minecraft, container1/container2)

## Architecture

Клиент-серверная архитектура с custom networking:

### Server (`src/main/java/com/itemflowmonitor/`)
- **`ItemFlowMonitor.java`** — entrypoint (`ModInitializer`): networking init, tick events, block break handler, debug-команды `/ifm`
- **`tracker/ContainerObserver.java`** — observer-подход: сравнение содержимого контейнеров каждый тик, запись положительных дельт. Заменил HopperBlockEntityMixin для Lithium-совместимости
- **`tracker/ContainerTracker.java`** — кольцевой буфер событий, расчёт rate (ACTUAL/PREDICTED/AVERAGE), EMA-сглаживание
- **`tracker/TrackerManager.java`** — синглтон-реестр трекеров (`BlockPos → ContainerTracker`), отслеживание viewer'ов
- **`tracker/TrackerSavedData.java`** — персистенция через Mojang Codec (сохраняются настройки, не буферы)
- **`network/TrackerNetworking.java`** — обработка C2S пакетов, отправка S2C каждый тик активным viewer'ам
- **`network/TrackerConfigC2SPacket.java`** — пакет настроек (спец. сигналы: -1=subscribe, -2=reset)
- **`network/TrackerUpdateS2CPacket.java`** — пакет обновления rate

### Client (`src/client/java/com/itemflowmonitor/`)
- **`ItemFlowMonitorClient.java`** — entrypoint (`ClientModInitializer`): кнопка IFM через `ScreenEvents.AFTER_INIT`, rate overlay, обработка S2C
- **`client/SettingsPanel.java`** — виджет панели настроек (toggle, period, rateMode, trackingMode, item selector, reset)
- **`client/TrackerClientState.java`** — клиентский кеш данных по BlockPos

### Shared
- **`util/ChestUtil.java`** — нормализация позиции двойных сундуков к каноническому блоку
- **Enums:** `TrackingMode` (ALL/AUTO/MANUAL), `TrackingPeriod` (SECOND/MINUTE/HOUR), `RateMode` (ACTUAL/PREDICTED/AVERAGE)

## Key Patterns

- **Whitelist экранов:** `isStorageScreen()` в `ItemFlowMonitorClient` — определяет, на каких экранах показывать кнопку IFM
- **Двойные сундуки:** Обе половины нормализуются к одной каноничной позиции (меньшие координаты)
- **Networking flow:** Client opens container → captures BlockPos → sends C2S config → server creates tracker → server sends S2C rate updates every tick → client renders overlay
- **Callback loop prevention:** `SettingsPanel.syncFromServer()` обновляет UI без вызова `onSettingsChanged`
- **Persistence:** Настройки трекеров сохраняются в `world/data/`, буферы событий — нет (rate сбрасывается при перезагрузке)

## Mixins

- **Server:** `itemflowmonitor.mixins.json` → (пусто, HopperBlockEntityMixin удалён — заменён на ContainerObserver)
- **Client:** `itemflowmonitor.client.mixins.json` → (зарезервировано, пока пусто)

## Development Philosophy

### Best of the Best
- Ориентир — лучшие моды Minecraft (Sodium, Lithium, Create, AppleSkin). Каждый участок кода должен соответствовать уровню топ-модов: читаемость, оптимизация, UX.
- Всегда искать лучшие решения. Не брать первый попавшийся подход — гуглить, сравнивать, смотреть как делают авторы лучших модов. Время не ограничено, токенов не жалко.
- Код должен быть максимально user-friendly: UI интуитивный, поведение предсказуемое, ошибки обработаны gracefully.
- Даже самые простые участки кода и логики должны быть реализованы на высшем уровне. Нет понятия "и так сойдёт".

### Уверенность и обсуждение
- **Правило 99%:** Если агент не уверен в решении больше чем на 99% — ОБЯЗАТЕЛЬНО обсудить с пользователем. Гуглить, искать альтернативы, показывать варианты. Не угадывать.
- Перед реализацией нетривиальной логики — обсудить подход. Лучше потратить время на обсуждение, чем переделывать.
- Активно использовать веб-поиск для проверки API, паттернов, совместимости. Не полагаться на память — проверять.
- Если есть два варианта реализации — описать оба пользователю с плюсами/минусами, дать рекомендацию, дождаться решения.

### Контекст и память
- **Перед началом работы** — прочитать `ITEM_FLOW_MONITOR_PLAN.md` и этот файл. Понять, где мы в плане, что уже сделано, что следующее.
- **После завершения этапа** — обновить план: пометить `✅ DONE`, записать что сделано, какие файлы затронуты, какие решения приняты.
- **Фиксировать архитектурные решения.** Если выбрали подход X вместо Y — записать в план почему. Иначе следующая сессия может переделать.
- Между сессиями контекст теряется. Всё важное должно быть записано в план или CLAUDE.md.

### Работа с кодом
- **Читать код перед изменением.** Не предполагать структуру — всегда `Read` файл перед `Edit`. Особенно после перерыва между сессиями.
- **Один логический шаг за раз.** Не менять 5 файлов одним махом. Изменил → собрал → проверил → следующий шаг.
- **Не трогать то, что работает.** Если задача — добавить печки в whitelist, не рефакторить попутно соседний метод.
- **`./gradlew build` после КАЖДОГО изменения.** Без исключений. Сломанная компиляция — стоп, чиним сразу.
- **Показывать diff перед сложными изменениями.** "Я собираюсь изменить X на Y в файле Z, вот что поменяется" — перед тем как менять.

### Тестирование и отладка
- **Просить пользователя тестировать граничные случаи.** Формулировать конкретные тест-кейсы: что открыть, что нажать, что должно произойти.
- **Намеренно ломать систему:** думать как тестировщик — что будет если контейнер уничтожен во время просмотра? Если пакет пришёл после выхода из мира? Если два игрока смотрят один контейнер?
- Добавлять отладочный вывод при разработке. Проверять каждое изменение через `./gradlew build`.
- Даже простые изменения (добавление в whitelist, смена константы) — проверять компиляцию и просить пользователя протестировать в игре.
- **Проверять что старый функционал не сломан.** Добавил печки → проверить что сундуки всё ещё работают. Всегда думать о регрессиях.
- **Предлагать тест-план после каждого изменения.** Конкретно: "Открой печку, нажми IFM, проверь что панель появилась". Не абстрактно "протестируй".

### Диагностика ошибок
- **Если что-то сломалось — сначала диагноз, потом лечение.** Не "я поменял обратно". А "ошибка в строке N, причина — X, варианты исправления: A, B".
- Не пытаться чинить вслепую. Прочитать stacktrace, найти root cause, понять почему, только потом исправлять.
- Если ошибка непонятна — спросить пользователя за логами, описанием поведения. Не гадать.

### Fabric/Minecraft специфика
- **Проверять API через документацию, не по памяти.** API Minecraft и Fabric меняются между версиями. Гуглить / Context7 для актуальных сигнатур.
- **Mojang mappings.** В этом проекте — Mojang official mappings. Не путать с Yarn или Intermediary.
- **Mixin — хрупкая штука.** Любые изменения в mixin — тройная проверка: компиляция, запуск, тест в игре.
- **Думать о мультиплеере.** Два игрока, один контейнер — что будет? Десинхрон клиент/сервер — что будет? Всегда проверять серверную и клиентскую стороны.

## Implementation Plan

Полный план: `ITEM_FLOW_MONITOR_PLAN.md`. Этапы 0–12 выполнены. Следующие: 13 (Подготовка к публикации), 14 (Дополнительные фичи QoL).
