⚠️ **СТАТУС ПРОЕКТА: В РАЗРАБОТКЕ** ⚠️

# Heat Network Service

Сервис автоматического построения вариантов подключения перспективных объектов капитального строительства (ОКС) к существующей тепловой сети.

## 🎯 Назначение

Сервис принимает на вход один GeoJSON-файл с данными о существующей теплосети, камерах, перспективных ОКС (точках подключения) и пространственных ограничениях. На выходе — GeoJSON с готовыми маршрутами новых тепловых сетей, точками врезки, рассчитанными диаметрами и стоимостью.

## 🛠 Стек технологий

| Компонент | Технология |
|---|---|
| **Язык** | Java 11 |
| **Фреймворк** | Spring Boot 2.6.3 |
| **База данных** | PostgreSQL 15 + PostGIS 3.3 (в Docker) |
| **ORM** | Hibernate Spatial 5.6.4 |
| **Геометрия** | JTS (Java Topology Suite) 1.18.2 |
| **Сборка** | Maven |
| **Документация API** | Springdoc OpenAPI UI 1.7.0 |
| **Контейнеризация** | Docker + Docker Compose |

## 🏗 Архитектура проекта

```
heat-network-service/
├── src/main/java/ru/hackathon/heatnetworkservice/
│   ├── HeatNetworkServiceApplication.java   ← Точка входа Spring Boot
│   │
│   ├── controller/                          ← API-слой (HTTP-запросы)
│   │   ├── HelloController.java             ← Тестовый эндпоинт
│   │   ├── GeoJsonReaderController.java     ← Тестовое чтение
│   │   ├── TaskController.java              ← POST /solve, GET /status, GET /result
│   │   └── dto/                             ← DTO для запросов/ответов
│   │       └── TaskStatus.java
│   │
│   ├── service/                             ← Бизнес-логика
│   │   ├── GeoJsonReaderService.java        ← Потоковое чтение GeoJSON
│   │   ├── GeoJsonWriterService.java        ← Потоковая запись GeoJSON
│   │   ├── TaskService.java                 ← Управление задачами
│   │   ├── RoutingService.java              ← Построение маршрутов (в разработке)
│   │   ├── FlowCalculationService.java      ← Расчёт расходов и диаметров (в разработке)
│   │   ├── ReconstructionService.java       ← Проверка реконструкции (в разработке)
│   │   ├── CostService.java                 ← Расчёт стоимости (в разработке)
│   │   └── VariantService.java              ← Формирование вариантов (в разработке)
│   │
│   ├── geometry/                            ← Работа с JTS
│   │   ├── CoordinateTransformer.java       ← WGS84 → UTM37N
│   │   ├── ObstacleChecker.java             ← Проверка препятствий (в разработке)
│   │   └── GraphBuilder.java                ← Построение графа (в разработке)
│   │
│   ├── repository/                          ← Работа с БД (Spring Data JPA)
│   │   ├── GeoObjectRepository.java
│   │   ├── NewNetworkRepository.java
│   │   ├── TieInRepository.java
│   │   ├── ReconstructionRepository.java
│   │   └── VariantRepository.java
│   │
│   ├── model/                               ← JPA-сущности (таблицы)
│   │   ├── GeoObject.java
│   │   ├── NewNetwork.java
│   │   ├── TieIn.java
│   │   ├── Reconstruction.java
│   │   └── Variant.java
│   │
│   └── config/                              ← Конфигурация (в разработке)
│       ├── SwaggerConfig.java
│       └── JacksonConfig.java
│
├── src/main/resources/
│   ├── application.yml                      ← Настройки приложения
│   └── test-data/
│       └── test_input.geojson               ← Тестовый GeoJSON
│
├── pom.xml                                  ← Зависимости Maven
├── Dockerfile                               ← Сборка контейнера (в разработке)
├── docker-compose.yml                       ← Запуск БД
├── README.md                                ← Этот файл
└── .gitignore                               ← Что не коммитить
```

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

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Тестовый эндпоинт: `http://localhost:8080/api/v1/hello`
- Загрузка файла: `POST /api/v1/solve` (multipart/form-data)

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

## 🔄 Git Workflow

Мы используем **feature branch workflow** — простую и эффективную стратегию для команды из 2 человек.

### Основные правила

1. **Никогда не пушьте напрямую в `master`** — только через Pull Request.
2. **Создавайте отдельную ветку для каждой задачи:**

   ```bash
   git checkout master
   git pull origin master
   git checkout -b feature/краткое-описание
   ```

3. **Пушьте ветку на GitHub:**

   ```bash
   git push origin feature/краткое-описание
   ```

4. **Создавайте Pull Request** на GitHub: из вашей ветки в `master`.
5. **После мержа** — удалите ветку.

### Соглашение об именовании веток

Формат: `<тип>/<краткое-описание>`

| Тип | Назначение | Пример |
|---|---|---|
| `feature/` | Новая функциональность | `feature/geojson-reader` |
| `bugfix/` | Исправление бага | `bugfix/routing-error` |
| `docs/` | Только документация | `docs/readme-update` |
| `refactor/` | Рефакторинг без изменения поведения | `refactor/service-layer` |

### Пример работы

```bash
# 1. Обновить master
git checkout master
git pull origin master

# 2. Создать ветку
git checkout -b feature/geojson-reader

# 3. Работать, коммитить
git add .
git commit -m "feat: add streaming GeoJSON reader"

# 4. Запушить
git push origin feature/geojson-reader

# 5. На GitHub создать Pull Request
# 6. После мержа удалить ветку
```

## 🤝 Как внести вклад

1. Создайте ветку от `master`.
2. Внесите изменения.
3. Пушьте ветку.
4. Создайте Pull Request.
5. Дождитесь ревью от напарника.

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
- [x] 5 JPA-сущностей: `GeoObject`, `NewNetwork`, `TieIn`, `Reconstruction`, `Variant`
- [x] 5 репозиториев для работы с БД
- [x] Hibernate автоматически создаёт таблицы

**API-слой**
- [x] `HelloController` — тестовый эндпоинт
- [x] `GeoJsonReaderController` — тестовое чтение
- [x] `TaskController` — `POST /solve`, `GET /status/{taskId}`, `GET /result/{taskId}`
- [x] DTO для запросов и ответов (`TaskStatus`)
- [x] Асинхронная обработка файлов (`@Async`)

**Потоковая обработка**
- [x] `GeoJsonReaderService` — чтение GeoJSON (все типы геометрии)
- [x] `GeoJsonWriterService` — запись GeoJSON (все типы геометрии)
- [x] `TaskService` — управление задачами

**Геометрия**
- [x] `CoordinateTransformer` — WGS84 → UTM37N

### ❌ Что ещё не сделано

**Бизнес-логика (Service)**
- [ ] `RoutingService` — построение маршрутов
- [ ] `FlowCalculationService` — расходы и диаметры
- [ ] `ReconstructionService` — реконструкция существующей сети
- [ ] `CostService` — расчёт стоимости
- [ ] `VariantService` — формирование и ранжирование вариантов

**Геометрия (JTS)**
- [ ] `ObstacleChecker` — проверка препятствий
- [ ] `GraphBuilder` — построение графа

**Конфигурация**
- [ ] `SwaggerConfig` — детальная настройка OpenAPI
- [ ] `JacksonConfig` — настройка сериализации GeoJSON

**Docker**
- [ ] `Dockerfile` для приложения
- [ ] Объединение app и db в одном `docker-compose.yml`

**Документация**
- [ ] Описание алгоритма трассировки
- [ ] Описание обработки ОКС без маршрута
- [ ] Описание выходных данных
- [ ] Описание границ применения

**Тестирование**
- [ ] Модульные тесты
- [ ] Интеграционные тесты

---

## 📅 План разработки (ориентировочно)

### Неделя 1 (15–21 сентября)
- [x] Инфраструктура (Java 11, Spring Boot, PostgreSQL, Docker)
- [x] JPA-сущности и репозитории
- [x] `GeoJsonReaderService`
- [x] `GeoJsonWriterService`
- [x] `CoordinateTransformer`
- [x] `TaskController` (API)

### Неделя 2 (22–29 сентября)
- [ ] `ObstacleChecker`
- [ ] `GraphBuilder`
- [ ] `RoutingService`
- [ ] `FlowCalculationService`
- [ ] `ReconstructionService`
- [ ] `CostService`
- [ ] `VariantService`
- [ ] `Dockerfile` + объединение docker-compose
- [ ] Презентация и документация

**Дедлайн сдачи:** 29 сентября 2026, 23:59 МСК

## 📄 Лицензия

Проект создан в рамках хакатона «Лидеры цифровой трансформации 2026».