# Сравнение на 13 ноября 2026

## Решение для передачи

Наш форк обновлён merge upstream v1.20.0 с сохранением дополнительных функций.
Он сохраняет сценарии статей, таблиц, картинок, тем и персональной политики доступа.
Эти возможности не уникальны: chigwell документирует Rich Messages с таблицами,
а better-tg-cli предлагает готовую переносимую установку. Его можно отправить
коллеге как самостоятельный вариант для пробы, но переносимость наших статей и
правил подтверждения следует проверять отдельно. В этих комплектах версия сервера
и Java runtime основаны на 1.20.0; точные зависимости указаны в `manifest.json`.

## tolboy/telegram-mcp-tdlib

Последний проверенный релиз — v1.20.0, опубликован 9 ноября 2026 в 20:22 UTC.
Он использует TDLight 3.5.9 / TDLib 1.8.68, добавляет отмену ожиданий MCP,
устойчивые результаты и восстановление отправок, копирование исходных файлов
перед загрузкой, более строгую проверку параметров и приватности каталога сессии.
В исходниках тега не найдены `send_article`, `edit_article` или `rich_markdown`.
В релизе есть готовый runtime для Windows x64 и macOS ARM64. Intel Mac в матрице
релизов отсутствует.

- https://github.com/tolboy/telegram-mcp-tdlib/releases/tag/v1.20.0
- https://github.com/tolboy/telegram-mcp-tdlib/blob/v1.20.0/CHANGELOG.md
- https://github.com/tolboy/telegram-mcp-tdlib/blob/v1.20.0/docs/RELEASE_BUNDLES.md

## Lifecycle-Innovations-Limited/claude-ops

Активно развивается как большой набор инструментов для Claude Code. Telegram
модуль на gram.js документирует list_dialogs, get_messages, send_message и
search_messages. Это удобная интеграция для переписки. Эквивалент наших article-
инструментов в проверенной документации не подтверждён. Активность всего репозитория
не доказывает развитие конкретного Telegram-модуля.

- https://github.com/Lifecycle-Innovations-Limited/claude-ops
- https://github.com/Lifecycle-Innovations-Limited/claude-ops/wiki/Architecture

## Muhammad18557/telegram-mcp

Telethon и локальная SQLite-копия сообщений. Хорошо подходит к поиску по локальной
истории. GitHub API на дату проверки сообщает последнюю запись репозитория
7 апреля 2025. Оснований считать его более современным универсальным заменителем
нашей связки не найдено. Качество и покрытие native Articles не подтверждены.

- https://github.com/Muhammad18557/telegram-mcp

## chaindead/telegram-mcp

Компактный Go-клиент с CLI и MCP для диалогов, сообщений, черновиков и статусов.
GitHub API сообщает последнюю запись 28 мая 2026. Он заслуживает рассмотрения
для простого клиента, но проверка документации не подтвердила замену наших
нативных Articles с таблицами и сохранением вложений.

- https://github.com/chaindead/telegram-mcp

## chigwell/telegram-mcp

Сильный кандидат для отдельного пилота. Текущий README перечисляет 80+ инструментов,
форум-темы, экспорт, замену медиа и Rich Markdown/HTML с заголовками и таблицами.
Rich-режим явно требует Premium. В опубликованных тестах видны вызовы
InputRichMessageMarkdown/InputRichMessageHTML, проверка Premium и чтение вложенных
RichText. Это подтверждает реализацию, но mocked-тесты не заменяют сравнение на
личном аккаунте. Сохранение встроенных картинок при редактировании именно наших
статей пока не проверено. Утверждать превосходство нашего форка здесь нельзя.

- https://github.com/chigwell/telegram-mcp/blob/main/README.md
- https://github.com/chigwell/telegram-mcp/blob/main/tests/test_rich_messages.py

## TheVilfer/better-tg-cli

Наиболее интересен для простой установки нового клиента на Mac. Документирует
CLI/MCP, Homebrew, Keychain, Windows DPAPI, работу с темами и режимы согласования
записи. В README заявлено чтение rich-содержимого, но покрытие создания и
редактирования наших Articles с таблицами не установлено. Использует другой
стек и другую схему разрешений. Его следует сравнить на тестовом аккаунте,
если приоритет — переписка, поиск и удобство установки.

- https://github.com/TheVilfer/better-tg-cli

## n24q02m/telegram

Есть ещё активно развивающийся Telethon/Bot API коннектор с CLI и MCP. Он включён
в обзор как возможная альтернатива для дальнейшего пилота. В этом проходе изучен
README, равенство наших article-сценариев и политика подтверждений не проверены.

- https://github.com/n24q02m/telegram

Сравнение основано на README, исходниках и релизах. Конкурирующие серверы не
авторизовывались в Telegram-аккаунте и не проходили сравнительный live-тест.
