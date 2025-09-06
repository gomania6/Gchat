# gChat
It has a minimum of settings and a minimum of functions.
The plugin is made for minimal chat setup!

### Support:

```
PlaceholderAPI
LuckPerms
```


##📋 Main Features

###💬 Chat
Local chat /l — messages visible in a radius (configurable, default 50 blocks)
Global chat /g — messages visible to all players
One-time global message !message — without switching chat mode

###🎨 Message Formatting
Color codes &a, &b, &c (requires gchat.color)
Gradients {#RRGGBB>}Text{#RRGGBB<} (requires gchat.gradient)
PlaceholderAPI support (%player_name%, %vault_eco%, etc.)
Group formats via LuckPerms or permission-based fallback

###⚙️ Configuration
Reloadable config.yml (/gchat reload)
Custom formats for different groups
Enable/disable chat system
Localizable messages via messages.yml

##🔧 Technical Features

###🚀 Performance
Asynchronous message handling
Optimized gradient processing

###🔐 Security
Permission checks (gchat.use, gchat.global, gchat.local)
Message length and cooldown checks
Bad word filtering with optional replacement or block

👮‍♂️ Access rights
```
gchat.use        - chat access
gchat.global     - global chat
gchat.local      - local chat
gchat.color      - use colors
gchat.gradient   - use gradients
gchat.reload     - reload config
gchat.group.<group> - group formats
gchat.bypass.*   - bypass cooldowns/filters
```
