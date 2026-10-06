# Forganizer

ИИ-помощник по организации папок и файлов на Android. Сканирует корень выбранной папки (например, `Downloads`), группирует связанные по смыслу файлы и показывает «картину папки»: целевые папки, наборы файлов, уверенность и причины. Перемещение выполняется только после предпросмотра, пишется в журнал и отменяется.

Спецификация: [docs/TZ.md](docs/TZ.md). Политика конфиденциальности: [docs/privacy.md](docs/privacy.md).

## Структура

| Путь | Что внутри |
|---|---|
| `core/` | Чистый Kotlin (JVM): `FileSource`, скан, правила, кластеризация, дубли, клиент двух фаз, валидатор плана, применение/журнал/отмена, экспорт. Unit-тесты. |
| `app/` | Android (Kotlin, Jetpack Compose): экраны, `FileBackend` (all-files access) и `SafBackend` (SAF), Room-журнал, DataStore, OkHttp-клиент. Flavors `full` и `play`. |
| `server/` | FastAPI: `/health`, `/plan`, `/refine` (патчи по текстовой инструкции), фолбэк моделей OpenRouter → Groq, промпты из приложений A и C ТЗ. |
| `render.yaml` | Деплой сервера на Render. |

## Сборка APK (GitHub Actions)

Workflow `.github/workflows/android.yml` на каждый push в `main` прогоняет тесты `core` и собирает `fullRelease` и `playRelease`. APK лежат в артефактах запуска (Actions → запуск → Artifacts). При пуше тега `v*` APK прикрепляются к GitHub Release.

Настройки репозитория (Settings → Secrets and variables → Actions):

| Имя | Тип | Назначение |
|---|---|---|
| `SERVER_URL` | variable | Адрес сервера, например `https://forganizer-api.onrender.com` |
| `APP_TOKEN` | secret | Тот же токен, что `APP_TOKEN` на сервере |
| `KEYSTORE_BASE64` | secret | Release-keystore в base64 (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | secret | Параметры keystore |

Без keystore release-APK подписывается debug-ключом (устанавливается, но не подходит для публикации).

Создать keystore:
```bash
keytool -genkeypair -v -keystore release.jks -alias forganizer -keyalg RSA -keysize 2048 -validity 10000
```

Локально: `./gradlew assembleFullDebug` (нужен Android SDK).

## Сервер (Render)

```bash
cd server
pip install -r requirements-dev.txt
APP_TOKEN=dev OPENROUTER_API_KEY=... uvicorn app.main:app --reload
python -m pytest -q
```

Переменные окружения: `APP_TOKEN`, `OPENROUTER_API_KEY`, `GROQ_API_KEY`, `MODELS` (порядок фолбэка, `provider:model,provider:model`), `MAX_BODY_BYTES` (512 КБ), `MODEL_TIMEOUT` (60 с), `TEMPERATURE` (0–0.2).

Тела запросов и ответы моделей не логируются, только метрики.

## Уточнение плана текстом (`/refine`)

На экране «Картина папки» поле «Что поправить?» отправляет инструкцию на `/refine`. Сервер возвращает патч (только `move`, `to_leave`, `create_folder`, `rename_folder`, `merge_folders`, `unbundle`, не более 20 операций, строгая схема). Приложение раскрывает селекторы локально (`PatchEngine` в `core`), применяет патч к копии плана и показывает экран разницы с кнопками «Принять» и «Отклонить».

- Ручные действия (галочки, перенос, переименование) и принятые правки закрепляются (`Pins`). ИИ не может их изменить, такие операции пропускаются с пояснением.
- Правка, затрагивающая больше половины файлов, требует отдельного подтверждения.
- Каждая принятая правка создаёт версию плана. Откат доступен в меню «Версии плана».
- Лимит 10 правок ИИ на сессию (`PlanSession.MAX_REFINES`).
- «Сохранить схему» сохраняет план в приложении. При открытии файлы, которых нет или которые изменились, помечаются и исключаются из применения.
- «Forganize» всегда ведёт через предпросмотр.

## Отклонения от ТЗ и упрощения MVP

- `FileSource.list` возвращает `Listing` (узлы + счётчик пропущенных), добавлены `stat` и `openRead` (хэш дублей, проверка «файл не изменился»).
- Применение выполняется в корутине ViewModel (экран не закрывается во время работы), без WorkManager.
- `scan_cache` не реализован (в ТЗ необязателен). Таблицы `plan_versions`, `saved_plans`, `pinned_decisions` есть (миграция БД 1 → 2 сохраняет журнал).
- Сохранённая схема хранит текущий план, закреплённые решения и историю инструкций. Версии плана живут в рамках сессии (и пишутся в `plan_versions`).
- В закреплённых решениях кроме `folder_name` и `file_in_folder` передаётся `file_excluded` (пользователь снял галочку).
- Если в `move` указана папка, которой нет в плане, она создаётся (после тех же проверок имени).
- `.apk/.xapk/.apks` раскладываются локально без ИИ в «Установщики» или «Старые установщики» (старше N дней).
