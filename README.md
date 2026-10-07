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

Workflow `.github/workflows/android.yml` на каждый push в `main` прогоняет тесты `core` и собирает `fullRelease` и `playRelease`. APK лежат в артефактах запуска (Actions → запуск → Artifacts). При создании релиза (тег любого вида: `v0.2.0` или `0.2.0`) APK автоматически прикрепляются к GitHub Release. К уже существующему релизу их можно добавить вручную: Actions → Android APK → Run workflow → в поле `release_tag` указать тег релиза.

Настройки репозитория (Settings → Secrets and variables → Actions):

| Имя | Тип | Назначение |
|---|---|---|
| `SERVER_URL` | variable | Адрес сервера, например `https://forganizer-api.onrender.com` |
| `APP_TOKEN` | secret | Тот же токен, что `APP_TOKEN` на сервере |
| `KEYSTORE_BASE64` | secret | Release-keystore в base64 (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | secret | Параметры keystore |

Без keystore release-APK подписывается debug-ключом (устанавливается, но не подходит для публикации).

Создать keystore можно двумя способами.

**Вручную** (нужен компьютер с JDK):
```bash
keytool -genkeypair -v -keystore release.jks -alias forganizer -keyalg RSA -keysize 2048 -validity 10000
```

**Через GitHub Actions** (подойдёт и с телефона), workflow `Generate release keystore`:
1. Создайте токен: GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token. Срок 7 дней, Repository access: Only select repositories → Forganizer, Repository permissions → **Secrets: Read and write**.
2. В репозитории (Settings → Secrets and variables → Actions → Secrets) создайте два секрета: `SECRETS_PAT` (этот токен) и `BACKUP_PASSPHRASE` (ваша парольная фраза от 12 символов, сохраните её в менеджере паролей).
3. Actions → Generate release keystore → Run workflow. Workflow создаст ключ со случайным паролем, сам запишет секреты `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` и приложит **зашифрованную** резервную копию.
4. Сразу скачайте артефакт `forganizer-keystore-backup` (хранится 7 дней) и сохраните вместе с `BACKUP_PASSPHRASE`. Секреты GitHub обратно прочитать нельзя: без копии потерянный ключ не восстановить, а приложение не получится обновить.
5. Токен `SECRETS_PAT` после этого отзовите и удалите секрет.

Пароли в логи не выводятся. Если секреты подписи уже есть, workflow откажется их заменять, пока не включён флаг `overwrite`. Расшифровка копии на компьютере:
```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in forganizer-keystore-backup.enc -out bundle.tar
tar -xf bundle.tar   # release.jks и credentials.txt
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

## Снимок папки

Кнопка «Снимок папки» на экране «Картина папки» сохраняет или отправляет список файлов и папок **до сортировки**: имена, размеры, даты, тип, скрытые и игнорируемые записи (с пометкой), возможные дубли. Форматы: текст и JSON; сохранить в файл или поделиться. Сами файлы не копируются, пути устройства в текстовый вариант не попадают (в JSON есть `id` записи для точного сопоставления). Для схемы, открытой из «Сохранённых схем», снимок строится по файлам схемы.

## Отклонения от ТЗ и упрощения MVP

- `FileSource.list` возвращает `Listing` (узлы + счётчик пропущенных), добавлены `stat` и `openRead` (хэш дублей, проверка «файл не изменился»).
- Применение выполняется в корутине ViewModel (экран не закрывается во время работы), без WorkManager.
- `scan_cache` не реализован (в ТЗ необязателен). Таблицы `plan_versions`, `saved_plans`, `pinned_decisions` есть (миграция БД 1 → 2 сохраняет журнал).
- Сохранённая схема хранит текущий план, закреплённые решения и историю инструкций. Версии плана живут в рамках сессии (и пишутся в `plan_versions`).
- В закреплённых решениях кроме `folder_name` и `file_in_folder` передаётся `file_excluded` (пользователь снял галочку).
- Если в `move` указана папка, которой нет в плане, она создаётся (после тех же проверок имени).
- **Заглядывание в zip-архивы** (настройка, по умолчанию включена): приложение читает на устройстве только список имён внутри zip и отправляет модели краткую сводку (`inside`: число файлов, корневые папки, типы, маркеры вроде `requirements.txt`). Это расширяет правило ТЗ «в LLM уходят только метаданные»: добавлены имена внутри архивов, поэтому изменены текст согласия, политика конфиденциальности и системный промпт (раздел «АРХИВЫ» в `server/app/prompt.txt`). Содержимое файлов по-прежнему не читается. Поддерживается только zip (в режиме «выбрать папку» архивы больше 64 МБ пропускаются).
- Файлы с нумерацией через дефис (`project-main-1.zip`, `project-main-2.zip`…) теперь тоже склеиваются в кластер (от 5 штук).
- `.apk/.xapk/.apks` раскладываются локально без ИИ в «Установщики» или «Старые установщики» (старше N дней).
