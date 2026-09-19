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
│   │   ├── TaskController.java              ← POST /solve, GET /status, GET /result
│   │   └── dto/                             ← DTO для запросов/ответов
│   │
│   ├── service/                             ← Бизнес-логика
│   │   ├── GeoJsonReaderService.java        ← Потоковое чтение GeoJSON
│   │   ├── GeoJsonWriterService.java        ← Потоковая запись GeoJSON
│   │   ├── RoutingService.java              ← Построение маршрутов
│   │   ├── FlowCalculationService.java      ← Расчёт расходов и диаметров
│   │   ├── ReconstructionService.java       ← Проверка реконструкции
│   │   ├── CostService.java                 ← Расчёт стоимости
│   │   └── VariantService.java              ← Формирование вариантов
│   │
│   ├── geometry/                            ← Работа с JTS
│   │   ├── CoordinateTransformer.java       ← WGS84 → UTM37N
│   │   ├── ObstacleChecker.java             ← Проверка препятствий
│   │   └── GraphBuilder.java                ← Построение графа
│   │
│   ├── repository/                          ← Работа с БД (Spring Data JPA)
│   │   ├── ObjectRepository.java
│   │   ├── NewNetworkRepository.java
│   │   ├── TieInRepository.java
│   │   └── ReconstructionRepository.java
│   │
│   ├── model/                               ← JPA-сущности (таблицы)
│   │   ├── GeoObject.java
│   │   ├── NewNetwork.java
│   │   ├── TieIn.java
│   │   ├── Reconstruction.java
│   │   └── Variant.java
│   │
│   └── config/                              ← Конфигурация
│       ├── SwaggerConfig.java
│       └── JacksonConfig.java
│
├── src/main/resources/
│   ├── application.yml                      ← Настройки приложения
│   └── db/migration/                        ← SQL-скрипты
│
├── pom.xml                                  ← Зависимости Maven
├── Dockerfile                               ← Сборка контейнера
├── docker-compose.yml                       ← Запуск app + db
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

## 📖 Документация API

После запуска приложения документация доступна по адресу:

```
http://localhost:8080/swagger-ui.html
```

## 🔄 Git Workflow

Мы используем **feature branch workflow** — простую и эффективную стратегию для команды из 2 человек.

### Основные правила

1. **Никогда не пушьте напрямую в `main`** — только через Pull Request.
2. **Создавайте отдельную ветку для каждой задачи:**

   ```bash
   git checkout main
   git pull origin main
   git checkout -b feature/краткое-описание
   ```

3. **Пушьте ветку на GitHub:**

   ```bash
   git push origin feature/краткое-описание
   ```

4. **Создавайте Pull Request** на GitHub: из вашей ветки в `main`.
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
# 1. Обновить main
git checkout main
git pull origin main

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

1. Создайте ветку от `main`.
2. Внесите изменения.
3. Пушьте ветку.
4. Создайте Pull Request.
5. Дождитесь ревью от напарника.


---

## 📊 Статус разработки

> Проект находится на стадии активной разработки. Ниже — список того, что уже готово, и что предстоит сделать.

### ✅ Что уже сделано

- [x] Настроен проект Spring Boot 2.6.3 на Java 11
- [x] Настроен Maven с зависимостями (Web, JPA, PostgreSQL, Hibernate Spatial, JTS, Lombok, Swagger)
- [x] Поднят PostgreSQL 15 + PostGIS 3.3 в Docker
- [x] Настроено подключение к БД через `application.yml`
- [x] Подключён Swagger UI (Springdoc OpenAPI 1.7.0)
- [x] Настроен `.gitignore`
- [x] Создан репозиторий на GitHub
- [x] Настроен Docker Compose для запуска БД

### 🚧 Что в процессе

- [x] Тестовый контроллер `HelloController`
- [x] JPA-сущности (модели таблиц)
- [x] Репозитории для работы с БД

### ❌ Что ещё не сделано

**API-слой**
- [ ] `TaskController` — `POST /solve`, `GET /status/{taskId}`, `GET /result/{taskId}`
- [ ] DTO для запросов и ответов
- [ ] Асинхронная обработка файлов

**Бизнес-логика (Service)**
- [x] `GeoJsonReaderService` — потоковое чтение GeoJSON
- [ ] `GeoJsonWriterService` — потоковая запись GeoJSON
- [ ] `RoutingService` — построение маршрутов
- [ ] `FlowCalculationService` — расходы и диаметры
- [ ] `ReconstructionService` — реконструкция существующей сети
- [ ] `CostService` — расчёт стоимости
- [ ] `VariantService` — формирование вариантов

**Геометрия (JTS)**
- [ ] `CoordinateTransformer` — WGS84 → UTM37N
- [ ] `ObstacleChecker` — проверка препятствий
- [ ] `GraphBuilder` — построение графа

**Работа с БД**
- [x] JPA-сущности: `GeoObject`, `NewNetwork`, `TieIn`, `Reconstruction`, `Variant`
- [x] Репозитории
- [ ] SQL-скрипт для PostGIS

**Конфигурация**
- [ ] `SwaggerConfig`
- [ ] `JacksonConfig`

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
- [ ] Тестовый GeoJSON-набор

---

## 📅 План разработки (ориентировочно)

### Неделя 1 (15–21 сентября)
- [x] Инфраструктура (Java 11, Spring Boot, PostgreSQL, Docker)
- [ ] JPA-сущности и репозитории
- [ ] `GeoJsonReaderService`
- [ ] `CoordinateTransformer`

### Неделя 2 (22–29 сентября)
- [ ] `RoutingService`
- [ ] `FlowCalculationService`
- [ ] `ReconstructionService`
- [ ] `CostService`
- [ ] `VariantService`
- [ ] `TaskController` (API)
- [ ] Презентация и документация

**Дедлайн сдачи:** 29 сентября 2026, 23:59 МСК


## 📄 Лицензия

Проект создан в рамках хакатона «Лидеры цифровой трансформации 2026».