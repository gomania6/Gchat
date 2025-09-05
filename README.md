# gChat

# EN
It has a minimum of settings and a minimum of functions.
The plugin is made for minimal chat setup!

### Support:

```
PlaceholderAPI
LuckPerms
```


### 📋 Main features
💬 Chat system
- Local chat (/l) - messages are visible within a radius of 50 blocks
- Global chat (/g) - messages are visible to all players
- One-time global messages (!message) - without changing the mode
- Configuring the radius of the local chat in config.yml

🎨 Message formatting
- Color codes (&a, &b, &c, etc.) with the gchat.color right
- Gradients ({#FF0000>}Text{#0000FF<}) with the gchat.gradient right
- PlaceholderAPI support (%player_name%, %vault_eco%, etc.)
- Group formats via LuckPerms

⚙️ Configuration
- Reloadable config.yml (/gchat reload)
- Localization via messages.yml
- Settings formats for different groups
- Turn on/off the chat system

### 🔧 Technical features
🚀 Performance
- Asynchronous message processing
- Message format caching
- Optimized gradient algorithms

🔐 Security
- Access rights checking
- Spam and abuse protection
- Input data validation

👮‍♂️ Access rights
```
gchat.reload - reload configs
gchat.color - use color codes
gchat.gradient - use gradients
gchat.group.<group> - access to group formats
```



# RU
Минимум настроек и минимум функций.
Плагин сделан для минимальной настройки чата!

### Поддержка:

```
PlaceholderAPI
LuckPerms
```

### 📋 Основные возможности
💬 Система чатов
- Локальный чат (/l) - сообщения видны в радиусе 50 блоков
- Глобальный чат (/g) - сообщения видны всем игрокам
- Разовые глобальные сообщения (!сообщение) - без смены режима
- Настройка радиуса локального чата в config.yml

🎨 Форматирование сообщений
- Цветовые коды (&a, &b, &c, и т.д.) с правом gchat.color
- Градиенты ({#FF0000>}Text{#0000FF<}) с правом gchat.gradient
- Поддержка PlaceholderAPI (%player_name%, %vault_eco%, и т.д.)
- Групповые форматы через LuckPerms

⚙️ Конфигурация
- Перезагружаемый config.yml (/gchat reload)
- Локализация через messages.yml
- Настройка форматов для разных групп
- Включение/выключение системы чата

### 🔧 Технические особенности
🚀 Производительность
- Асинхронная обработка сообщений
- Кэширование форматов сообщений
- Оптимизированные алгоритмы градиентов

🔐 Безопасность
- Проверка прав доступа
- Валидация входных данных

👮‍♂️ Права доступа

```
gchat.reload - перезагрузка конфигов
gchat.color - использование цветовых кодов
gchat.gradient - использование градиентов
gchat.group.<group> - доступ к групповым форматам
```

### config.yml
```
# gChat Configuration

# Chat modes: local (default) or global
default-chat-mode: local

# Local chat settings
local-chat:
  enabled: true
  radius: 50
  format: "&8[Локально]"
  show-silent-message: true # Добавляет сообщение (В радиусе никого нет... Тишина.)

# Global chat settings
global-chat:
  enabled: true
  format: "&8[Глобально]"

# Default format if no permission matches
default-format: "%gchat_format% %player_name% &7» %message%"

# Group-based formats
group-formats:
  admin: "%gchat_format% {#FF5555>}АДМИН{#FFAAAA<} {#FF5555>}%player_name%{#FFAAAA<} &8» %message%"
  moderator: "%gchat_format% {#5555FF>}МОДЕРАТОР{#AAAACC<} {#5555FF>}%player_name%{#AAAACC<} &8» %message%"
  vip: "%gchat_format% {#FFFF55>}VIP{#FFFFAA<} {#FFFF55>}%player_name%{#FFFFAA<} &8» %message%"
  builder: "%gchat_format% {#55FF55>}СТРОИТЕЛЬ{#AAFFAA<} {#55FF55>}%player_name%{#AAFFAA<} &8» %message%"

# Enable/disable chat formatting
enabled: true

# Log formatted messages to console
log-to-console: false

# Reload command cooldown (in seconds)
reload-cooldown: 5
```

### messages.yml
```
# gChat Messages

prefix: "&8[&6gChat&8]"

# Общие сообщения
no-permission: "{prefix} &cУ вас нет прав для выполнения этой команды!"
players-only: "{prefix} &cЭта команда только для игроков!"
unknown-command: "{prefix} &cНеизвестная команда. Используйте &6/gchat help"
config-reloaded: "{prefix} &aКонфигурация успешно перезагружена!"
reload-cooldown: "{prefix} &cПодождите &e{time} &cсекунд перед повторным использованием."

# Сообщения чата
local-chat-disabled: "{prefix} &cЛокальный чат отключен."
local-chat-silent: "{prefix} &7В радиусе никого нет... Тишина."
chat-mode-local: "{prefix} &aРежим чата изменен на &6локальный&a. Сообщения будут видны только nearby игрокам."
chat-mode-global: "{prefix} &aРежим чата изменен на &6глобальный&a. Все сообщения будут видны всем игрокам."
use-chat-normal: "{prefix} &7Просто напишите сообщение в чат! Команды /g и /l только меняют режим."

# Помощь
help:
  - "&6&l=== gChat Help ==="
  - "&e/gchat reload &7- Перезагрузить конфигурацию"
  - "&e/gchat help &7- Показать эту помощь"
  - "&e/g &7- Переключиться на глобальный чат"
  - "&e/g <сообщение> &7- Отправить глобальное сообщение"
  - "&e/l &7- Переключиться на локальный чат"
  - "&e/l <сообщение> &7- Отправить локальное сообщение"
  - "&e!<сообщение> &7- Отправить разовое глобальное сообщение"
  - "&6Формат градиента: &7{#RRGGBB>}Text{#RRGGBB<}"
```
