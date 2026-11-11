# Changelog

## 1.0.0

First release. A fast text formatter for Adventure 4.17 (Paper 1.20.6) to 5.x (Minecraft 26), with no dependency
besides Adventure. Tested on Paper 1.20.6, 1.21 and 26 through MessageFlow 1.2.

- **`MiniMessageFlow`**: `compile(String)` gives an immutable, thread-safe `Template`; `format(...)` compiles and
  caches. Builder: `resolver`, `tags`, `legacyCodes`, `templateCacheSize`, `valueCacheSize`, `cacheFactory`,
  `nativeCompiler`, `verifyNative`.
- **Placeholders** written `%name%` or `<name>`, with modifiers (`%price.comma%`, `<price:comma>`,
  `<n:plural:item:items>`...). Values of a call are plain text or components, never read as tags. Values a call doesn't
  give come from `Resolver`s, formatted on their own (their colours and unclosed tags end with them).
- **Native compiler**: messages made of colours, decorations (with arguments), `reset`, `newline`, `click` (every
  action), `hover` (`show_text`, `show_item`, `show_entity`), `insert`, `font`, `key`, `lang`/`tr`/`translate` (and
  `_or`), `gradient`, `rainbow`, `transition` and placeholders, and on Adventure 5 `shadow`, `sprite`, `head`, are
  compiled without MiniMessage and checked against it once; others go through MiniMessage with markers.
- **`MiniMessageFlowTranslator`** for Adventure's `GlobalTranslator`: templates per key and locale, arguments
  `<arg:i>`/`%i%`.
- **`cache.Cache` / `cache.CacheFactory`** to supply your own caches (Guava, Caffeine...).
- One jar (`minimessageflow`) with a bridge per Adventure major, found by package so it can be relocated.

Speed (JMH, JDK 25): a message without placeholders renders in 1.4 ns; 3 placeholders and a click in 155 ns; a shop
line in 245 ns where `MiniMessage.deserialize` takes 7.7 µs. Compiling a native message takes 10 µs (1.5 µs with
`verifyNative(false)`). Every number is in the README.
