# Changelog

## Unreleased

- A component value that comes again at the same place of a message (a resolver's text, which is parsed once and
  kept; a component the caller keeps) is put under the style around it once, not at each render.
  `<gray>[%server%] <white>%name%` with `%server%` from a resolver, on Adventure 4.17: 150 ns and 824 B to 72 ns
  and 288 B.
- `Template.fallbackReason()`: for a `MARKERS` or `PARSED` message, why it is not compiled natively: the tag left
  to MiniMessage (`the tag <selector>`), that the native result differed from MiniMessage's, or that the native
  compiler is off. Null for a `STATIC` or `NATIVE` message.
- `constant(key, text)` and `constant(key, component)` on the builder: a value that never changes for the instance
  (`%prefix%`, `%server%`), formatted once on its own as a resolver's text is. A message whose only placeholders
  are constants is rendered when it is compiled and is `STATIC` (every render returns the same component, 0 B);
  in other messages a constant is read without a lookup. A value given to a render for a constant's key is ignored.
- A natively compiled message made of one part is that part, without an empty component around it.
- A message MiniMessage parsed with markers (a tag the native compiler leaves to it: `<selector>`, `<score>`, a
  user tag...) is prepared once: a render copies only the components above a value and no longer walks the whole
  tree or compacts it. `<selector:@p> %item% <score:steve:kills> %price%`: 684 ns and 2.1 KB to 138 ns and 0.8 KB.
  The component shows the same but is no longer compacted with its values in: a value is its own component next
  to the text around it (`Hi %name%` is three components, as a natively compiled message gives, where it was one).
- A render builds less besides its result: a text value goes straight into its component (and into a click's or
  a hover's argument) instead of through a component of its own, the component a group or a gradient puts its
  content in is built when the message is compiled, and a click with a value is set on the style around it
  instead of merged into it. A click with a value in its command: 377 ns and 1.6 KB to 108 ns and 0.8 KB; three
  values and a click: 162 ns and 680 B to 129 ns and 608 B.
- A gradient or rainbow over a value keeps, for each length it has coloured (up to 63 characters), the colour of
  each position and the finished characters of the text before the first value and after the last: a render only
  builds the characters of the values. `<gradient:red:blue>Welcome %name%!`: 532 ns and 2.4 KB to 180 ns and 0.7 KB.
- `Values`: the values of a render kept in an array and set again for each send, instead of a `Placeholder` built
  each time. Declare the names once (`ValueKey PLAYER = builder.key("player")`), make one `Values` per thread
  (`text.newValues()`), then `values.set(PLAYER, name)` and `template.render(values, viewer)`. It works with every
  template of the instance; a key that is not set goes to the resolvers, as a key missing from a `Placeholder`.
  Not thread-safe. Three values and a click, set and rendered: 115 ns and 680 B, against 207 ns and 1,400 B with a
  `Placeholder` built for the send.
- Building a `Placeholder` costs about a third of what it did (three values with the builder: 64 ns and 728 B to
  20 ns and 328 B). `build()` no longer copies the builder's map (the builder copies it if it is used again), and
  `Placeholder.of(k1, v1, k2, v2)` no longer goes through a `HashMap`.
- A value in a hover's text (`<hover:show_text:'Balance: %coins%'>`) or in a translation argument
  (`<lang:key:'%player%'>`) is placed at render: such a message is `NATIVE` instead of `PARSED`, and a value never
  seen before no longer costs a MiniMessage parse (a hover with a changing value: 22.7 µs and 67 KB to 0.22 µs and
  1.6 KB). A value with a `#`, or a `:` in an argument without quotes, still goes through MiniMessage for that
  render, as does a value under a gradient there.
- Modifiers are read once, when a message is compiled: a render with `%price.comma%` no longer splits and looks
  the chain up each time (the shop line renders about three times faster and allocates less than half).
  A shared modifier registered with `PlaceholderModifiers.register` after a message is compiled is not seen by
  that message: register before compiling, as the Javadoc asks. A `Modifier` must not change its `args` array,
  which is now the same on every call. `PlaceholderModifiers.compile(chain)` and `ModifierSet.compile(chain)`
  give a `Chain` to apply to many values. `comma` on a whole number no longer goes through `String.format`.
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
