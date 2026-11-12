# Changelog

## Unreleased

- Legacy codes are no longer read inside a `<click>` or `<insert>` tag: a URL keeps its `&b=2` and `#abcdef`, a
  copied `#ff0000` stays as written. A hover's text is still formatted.
- A `<` that opens no tag (`I <3 you`, `5 < 6`) no longer changes the placeholders after it: their values keep every
  character and their style, and the message is compiled once instead of once per value.
- A key is evaluated once per render, wherever it is written (text, `<key>`, tag arguments).
- `errorHandler` on the builder: a resolver or a value that throws is no longer silent. By default it is logged
  once per key (`System.getLogger("MiniMessageFlow")`). `Resolver.of(Placeholder)` lets a failing value through
  to it.
- `modifier(name, modifier)` on the builder: modifiers of one instance (`ModifierSet`), known before any message is
  compiled. `PlaceholderModifiers.register` stays, shared by every instance.
- `bareHexColors(false)` on the builder: a `#rrggbb` without `&` is text.
- `Template.placeholders()`, `Template.kind()`, `Template.renderPlain(...)`; `MiniMessageFlow.compileAll(Map)` and
  `formatPlain(...)`.
- `Placeholder.of(Map)` and `Placeholder.Builder.registerAll(Map)`. Removed: `Placeholder.PLACEHOLDER_PATTERN`, a
  regular expression nothing used.
- `MiniMessageFlowTranslator` falls back like resource bundles: `zh_Hant_TW` to `zh_Hant`, `zh_TW`, `zh`, then a
  template registered for `Locale.ROOT`, then the default locale.
- Tags are found exactly as MiniMessage's tokenizer finds them, checked against it on every Adventure of the matrix
  (`TagRangesTest`). Each bridge has its own tests. JitPack runs the tests before publishing.

- Values computed at each render or for the viewer can be components: `Placeholder.ofComponent(key, supplier)`,
  `Placeholder.ofViewerComponent(key, function)`, `registerComponent`, `registerViewerComponent` on the builder
  (`PlaceholderValue.DynamicComponentValue`, `ViewerComponentValue`; `PlaceholderValue.asComponent`, `isComponent`,
  `needsViewer`).
- `Resolver.resolveComponent(key, viewer)`: a resolver can give a component, asked before its text.
  `Resolver.of(Placeholder)` gives component values as they are instead of writing and parsing them again; with a
  modifier (`%key.upper%`) such a value is its plain text, as for a value of the call.

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
