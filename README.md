# Heat Network Service

Сервис автоматического построения вариантов подключения перспективных объектов капитального строительства (ОКС) к существующей тепловой сети.

**Важно:** реконструкция существующей тепловой сети **не выполняется** (отменена в актуальном ТЗ).

## 🎯 Назначение

Сервис принимает на вход один GeoJSON-файл с данными о существующей теплосети, камерах, точках подключения ОКС и пространственных ограничениях. На выходе — GeoJSON с готовыми маршрутами новых тепловых сетей, тепловыми камерами, техническими узлами и сводкой по вариантам.

## 🛠 Стек технологий

| Компонент | Технология |
|---|---|
| **Язык** | Java 11 |
| **Фреймворк** | Spring Boot 2.6.3 |
| **База данных** | PostgreSQL 15 + PostGIS 3.3 (в Docker) |
| **ORM** | Hibernate Spatial 5.6.4 |
| **Геометрия** | JTS (Java Topology Suite) 1.18.2 |
| **Проекции** | proj4j 1.2.2 + proj4j-epsg 1.2.2 |
| **Сборка** | Maven |
| **Документация API** | Springdoc OpenAPI UI 1.7.0 |
| **Фронтенд** | Vanilla JS + OpenLayers 10.x (для карты) |
| **Контейнеризация** | Docker + Docker Compose |

## 🏗 Архитектура проекта

```
heat-network-service/
├── src/main/java/ru/hackathon/heatnetworkservice/
│   ├── HeatNetworkServiceApplication.java   ← Точка входа Spring Boot
│   │
│   ├── controller/                          ← API-слой
│   │   ├── FileController.java              ← Управление файлами ✅
│   │   ├── TaskController.java              ← POST /solve, GET /status, GET /result ✅
│   │   └── dto/
│   │       ├── FileInfo.java                ✅
│   │       └── TaskStatus.java              ✅
│   │
│   ├── service/                             ← Бизнес-логика
│   │   ├── GeoJsonReaderService.java        ← Чтение GeoJSON ✅
│   │   ├── GeoJsonWriterService.java        ← Запись GeoJSON ✅
│   │   ├── TaskService.java                 ← Управление задачами ✅
│   │   ├── RoutingService.java              ← Построение маршрутов ✅
│   │   ├── FlowCalculationService.java      ← Расходы и диаметры ✅
│   │   ├── CostService.java                 ← Расчёт стоимости ✅
│   │   ├── TieInService.java                ← Определение врезок и камер ✅
│   │   └── VariantService.java              ← Формирование вариантов ✅
│   │
│   ├── geometry/                            ← Работа с JTS
│   │   ├── CoordinateTransformer.java       ← WGS84 → UTM37N ✅
│   │   ├── ObstacleChecker.java             ← Проверка препятствий ✅
│   │   ├── GraphBuilder.java                ← Построение графа ✅
│   │   └── OksPolygonIndex.java             ← Индекс полигонов ОКС ✅
│   │
│   ├── repository/                          ← Spring Data JPA
│   │   ├── GeoObjectRepository.java         ✅
│   │   ├── NewNetworkRepository.java        ✅
│   │   ├── TieInRepository.java             ✅
│   │   └── VariantRepository.java           ✅
│   │
│   ├── model/                               ← JPA-сущности
│   │   ├── GeoObject.java                   ✅
│   │   ├── NewNetwork.java                  ✅
│   │   ├── TieIn.java                       ✅
│   │   └── Variant.java                     ✅
│   │
│   └── config/                              ← Конфигурация
│       ├── OpenApiConfig.java               ✅
│       ├── RoutingConfig.java               ✅
│       ├── SwaggerUiConfig.java             ✅
│       └── WebConfig.java                   ✅
│
├── src/main/resources/
│   ├── application.yml                      ← Настройки приложения ✅
│   └── static/                              ← Статические ресурсы (фронтенд) ✅
│       ├── dashboard.html                   ← Интерактивный дашборд ✅
│       ├── swagger-custom.css               ← Кастомные стили Swagger ✅
│       └── lib/
│           └── openlayers/
│               └── ol.js                    ← Библиотека OpenLayers (карта) ✅
│
├── pom.xml                                  ← Зависимости Maven ✅
├── docker-compose.yml                       ← Запуск БД ✅
├── README.md                                ← Этот файл ✅
└── .gitignore                               ← Что не коммитить ✅
```

**Обозначения:**
- ✅ — реализовано
- ❌ — ещё не реализовано

## 🚀 Быстрый старт

### Предварительные требования

- **Java 11 (JDK)** — [скачать Temurin 11](https://adoptium.net/temurin/releases/?version=11)
- **IntelliJ IDEA** (Community Edition достаточно)
- **Docker Desktop** — [скачать](https://www.docker.com/products/docker-desktop/)

### Установка и запуск

**1. Клонируйте репозиторий:**

```bash
git clone https://github.com/Bob-05/heat-network-service.git
cd heat-network-service
```

**2. Откройте проект в IntelliJ IDEA:**

- `File → Open` → выберите папку `heat-network-service` (там, где `pom.xml`).
- IDEA автоматически подтянет зависимости Maven.

**3. Запустите базу данных:**

```bash
docker compose up -d
```

Проверьте, что контейнер запущен:

```bash
docker ps
```

Должен быть контейнер `heat-network-db` со статусом `Up`.

**4. Запустите приложение:**

- В IDEA найдите `HeatNetworkServiceApplication.java`.
- Нажмите зелёный треугольник → **Run**.

Приложение запустится на `http://localhost:8080`.

**5. Проверьте API:**

- **Дашборд:** `http://localhost:8080/dashboard.html`
- **Swagger UI:** `http://localhost:8080/swagger-ui.html` (или `http://localhost:8080/docs-ui/` в зависимости от конфигурации)
- **Загрузка файла:** `POST /api/v1/solve` (multipart/form-data)
- **Тестовый эндпоинт:** `http://localhost:8080/api/v1/hello`

## 📖 Документация API

После запуска приложения документация доступна по адресу:

```
http://localhost:8080/swagger-ui.html
```

### Основные эндпоинты

| Метод | URL | Что делает |
|---|---|---|
| `POST` | `/api/v1/solve` | Загрузить GeoJSON-файл, запустить обработку |
| `GET` | `/api/v1/status/{taskId}` | Проверить статус задачи |
| `GET` | `/api/v1/result/{taskId}` | Скачать результат обработки |
| `GET` | `/api/v1/files` | Список всех загруженных и результирующих файлов |
| `GET` | `/api/v1/files/download/{type}/{fileName}` | Скачать файл (type: uploads / results) |
| `DELETE` | `/api/v1/files/{type}/{fileName}` | Удалить файл |
| `DELETE` | `/api/v1/files/{type}` | Очистить все файлы в каталоге |

## 🖥 Интерактивный дашборд

Встроенный дашборд (`/dashboard.html`) предоставляет следующие возможности:

- **Обработка** — загрузка GeoJSON-файла и запуск обработки с отслеживанием статуса задачи.
- **Файлы** — просмотр, скачивание, фильтрация и удаление входных и выходных файлов.
- **Карта** — визуализация входных данных и результатов на карте (OpenLayers + OSM):
    - отображение тепловых сетей, ОКС, камер и технических узлов;
    - раздельная подсветка обычной и специальной прокладки;
    - всплывающие подсказки со свойствами объектов.
- **API Docs** — встроенный рендерер OpenAPI-спецификации с возможностью выполнить запрос прямо из браузера.

## 📐 Ключевые правила ТЗ (актуальная версия)

### Входные данные

- **Источник теплоснабжения** (`source`) — Point.
- **Существующая тепловая сеть** (`heat_network`) — LineString, с `diameter`.
- **Существующая тепловая камера** (`heat_chamber`) — Point.
- **Точка подключения ОКС** (`oks_connection_point`) — Point, с `flow_tph`.
- **Пространственное ограничение** (`restriction`) — LineString / MultiLineString / Polygon / MultiPolygon, с `restriction_type`.

**Типы ограничений:**

| Тип | Правило | Мин. гориз. расстояние | Угол | Kспец |
|---|---|---|---|---|
| `oks` | Пересечение запрещено | 5/7/9 м (по ДУ) | — | — |
| `park` | Пересечение запрещено | 1,0 м | — | — |
| `social_area` | Пересечение запрещено | 1,0 м | — | — |
| `prohibited_site` | Пересечение запрещено | 1,0 м | — | — |
| `water` | Пересечение запрещено | 1,0 м | — | — |
| `railway` | Пересечение запрещено | 1,0 м | — | — |
| `road` | Спецпроход | 1,5 м | ≥45° | 1,60 |
| `tram_tracks` | Спецпроход | 1,5 м | ≥45° | 1,75 |
| `gas_pipeline` | Спецпроход | 2,0 м | — | 1,25 |
| `power_cable` | Спецпроход | 2,0 м | — | 1,15 |
| `heat_network` | Спецпроход | 1,0 м | — | 1,05 |

### Расчётные параметры (Таблица 1)

| ДУ, мм | Пропускная способность, т/ч | Предельная длина, м | Новое строительство, руб./м |
|---|---|---|---|
| 50 | 3,5 | 181 | 74 023 |
| 65 | 8,3 | 245 | 78 631 |
| 80 | 13,2 | 327 | 83 530 |
| 100 | 22,3 | 419 | 89 748 |
| 125 | 40,2 | 554 | 97 275 |
| 150 | 65,1 | 696 | 105 507 |
| 200 | 152,3 | 1 042 | 120 275 |
| 250 | 274,9 | 1 379 | 135 323 |
| 300 | 437,4 | 1 718 | 150 022 |
| 400 | 943,1 | 2 477 | 190 299 |
| 500 | 1 663,4 | 3 245 | 224 137 |
| 600 | 2 627,7 | 4 037 | 264 790 |
| 700 | 3 735,1 | 4 775 | 324 298 |
| 800 | 5 296,8 | 5 644 | 325 996 |
| 900 | 7 165,0 | 6 518 | 327 693 |
| 1000 | 9 391,8 | 7 419 | 418 777 |
| 1200 | 15 012,8 | 9 288 | 428 074 |
| 1400 | 22 501,9 | 11 276 | 683 417 |

### Стоимость камер и врезок

| Наибольший ДУ примыкающих участков | Стоимость новой камеры, руб. |
|---|---|
| 50–200 | 3 000 000 |
| 250–500 | 5 000 000 |
| 600–1000 | 8 000 000 |
| 1200–1400 | 12 000 000 |

**Врезка в существующую камеру** — 5 000 000 руб. за каждый новый линейный участок, заканчивающийся в ней.

### Формула ранжирования

```
S = 0,7 · (C / 25 000 000) + 0,3 · (L / 100)
```

где:
- `C` — итоговая стоимость варианта (`calculated_cost`), руб.
- `L` — суммарная длина новых участков (`new_network_length`), м.

### Штраф за неподключённые точки

```
Ш = 100 000 000 + 500 000 · G
```

где `G` — расчётный расход точки подключения (`flow_tph`), т/ч.

**Важно:** неподключение допускается **только если маршрут не найден**. Намеренный отказ запрещён.

## 🔄 Git Workflow

Мы используем **feature branch workflow**.

### Основные правила

1.  **Никогда не пушьте напрямую в `master`** — только через Pull Request.
2.  **Создавайте отдельную ветку для каждой задачи:**

    ```bash
    git checkout master
    git pull origin master
    git checkout -b feature/краткое-описание
    ```

3.  **Пушьте ветку на GitHub:**

    ```bash
    git push origin feature/краткое-описание
    ```

4.  **Создавайте Pull Request** на GitHub: из вашей ветки в `master`.
5.  **После мержа** — удалите ветку.

### Соглашение об именовании веток

| Тип | Назначение | Пример |
|---|---|---|
| `feature/` | Новая функциональность | `feature/geojson-reader` |
| `bugfix/` | Исправление бага | `bugfix/routing-error` |
| `docs/` | Только документация | `docs/readme-update` |
| `refactor/` | Рефакторинг без изменения поведения | `refactor/service-layer` |

## 🤝 Как внести вклад

1.  Создайте ветку от `master`.
2.  Внесите изменения.
3.  Пушьте ветку.
4.  Создайте Pull Request.
5.  Дождитесь ревью от напарника.

---

## 📊 Статус разработки

> Проект находится на стадии активной разработки. Ниже — список того, что уже готово, и что предстоит сделать.

### ✅ Что уже сделано

**Инфраструктура**
- [x] Настроен проект Spring Boot 2.6.3 на Java 11
- [x] Настроен Maven с зависимостями (Web, JPA, PostgreSQL, Hibernate Spatial, JTS, Lombok, Swagger, proj4j)
- [x] Поднят PostgreSQL 15 + PostGIS 3.3 в Docker
- [x] Настроено подключение к БД через `application.yml`
- [x] Подключён Swagger UI (Springdoc OpenAPI 1.7.0)
- [x] Настроен `.gitignore`
- [x] Создан репозиторий на GitHub
- [x] Настроен Docker Compose для запуска БД

**Модель данных**
- [x] 4 JPA-сущности: `GeoObject`, `NewNetwork`, `TieIn`, `Variant` (обновлены под новое ТЗ)
- [x] 4 репозитория для работы с БД
- [x] Hibernate автоматически создаёт таблицы

**API-слой**
- [x] `HelloController` — тестовый эндпоинт
- [x] `GeoJsonReaderController` — тестовое чтение
- [x] `TaskController` — `POST /solve`, `GET /status/{taskId}`, `GET /result/{taskId}`
- [x] `FileController` — управление загруженными и результирующими файлами
- [x] DTO для запросов и ответов (`TaskStatus`, `FileInfo`)
- [x] Асинхронная обработка файлов (`@Async`)

**Потоковая обработка**
- [x] `GeoJsonReaderService` — чтение GeoJSON (все типы геометрии)
- [x] `GeoJsonWriterService` — запись GeoJSON (обновлён под новое ТЗ)
- [x] `TaskService` — управление задачами

**Геометрия**
- [x] `CoordinateTransformer` — WGS84 → UTM37N
- [x] `ObstacleChecker` — проверка препятствий
- [x] `GraphBuilder` — построение графа
- [x] `OksPolygonIndex` — индекс полигонов ОКС

**Бизнес-логика**
- [x] `RoutingService` — построение маршрутов
- [x] `FlowCalculationService` — расходы и диаметры
- [x] `CostService` — расчёт стоимости
- [x] `TieInService` — определение врезок и камер
- [x] `VariantService` — формирование и ранжирование вариантов

**Конфигурация и UI**
- [x] `OpenApiConfig` — настройка OpenAPI
- [x] `RoutingConfig` — параметры маршрутизации
- [x] `SwaggerUiConfig` — группировка API
- [x] `WebConfig` — редирект на дашборд
- [x] `dashboard.html` — интерактивный дашборд с картой и документацией
- [x] `ol.js` (OpenLayers) — библиотека для карты

### ❌ Что ещё не сделано

**Docker**
- [ ] `Dockerfile` для приложения
- [ ] Объединение app и db в одном `docker-compose.yml`

**Документация**
- [ ] Описание алгоритма трассировки
- [ ] Описание обработки ОКС без маршрута
- [ ] Описание выходных данных
- [ ] Описание границ применения
---

## 📅 План разработки (ориентировочно)

### Неделя 1 (15–21 сентября)
- [x] Инфраструктура (Java 11, Spring Boot, PostgreSQL, Docker)
- [x] JPA-сущности и репозитории
- [x] `GeoJsonReaderService`
- [x] `GeoJsonWriterService`
- [x] `CoordinateTransformer`
- [x] `TaskController` (API)
- [x] Обновление под новое ТЗ (реконструкция отменена)

### Неделя 2 (22–29 сентября)
- [x] `ObstacleChecker`
- [x] `GraphBuilder`
- [x] `RoutingService`
- [x] `FlowCalculationService`
- [x] `CostService`
- [x] `VariantService`
- [ ] `Dockerfile` + объединение docker-compose
- [ ] Презентация и документация
- [ ] **Дедлайн сдачи:** 29 сентября 2026, 23:59 МСК

## 📄 Лицензия

Проект создан в рамках хакатона «Лидеры цифровой трансформации 2026».