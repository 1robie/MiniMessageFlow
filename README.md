# MiniMessageFlow

A fast text formatter for [Adventure](https://github.com/KyoriPowered/adventure). It reads MiniMessage tags, legacy
colour codes (`&c`, `§c`, `&#rrggbb`, `#rrggbb`) and placeholders written `%name%` or `<name>`, compiles each message
once and renders it in nanoseconds. It runs on Adventure 4.17 (Paper 1.20.6) to 5.x (Minecraft 26), and has no
dependency besides Adventure.

It is the formatting engine of MessageFlow 1.2, tested on Paper 1.20.6, 1.21
and 26. It is not published yet: `./gradlew publishToMavenLocal` installs `fr.robie.minimessageflow:minimessageflow`
in the local Maven repository.

## Use

```java
MiniMessageFlow text = MiniMessageFlow.builder()
        .resolver(Resolver.of(globals))   // values the call doesn't give: %server%, <server>... (formatted text)
        .build();                          // immutable, thread-safe: build one and share it

Template shop = text.compile("<green>%item%</green> for <gold><price:comma></gold> <click:run_command:'/buy %id%'>[Buy]");
Component line = shop.render(Placeholder.builder()
        .register("item", "Sword")
        .register("price", "1250")
        .register("id", "42")
        .build(), player);

Component once = text.format("<red>Hello %name%", Placeholder.of("name", "Steve"));   // compiled and cached
```

- **Placeholders**: `%name%` and `<name>` are the same placeholder. Modifiers: `%price.comma%`, `<price:comma>`,
  `<n:plural:item:items>`, `%name.upper%`... (`PlaceholderModifiers.register` adds yours). A value of the call is plain
  text, never read as tags or colour codes (`Placeholder.of("x", component)` for styled values). A value can also
  depend on the viewer (`Placeholder.of("hp", viewer -> ...)`). Without a value, a placeholder stays as written.
- **Resolvers** give the values a call doesn't: global placeholders, PlaceholderAPI... Their text is formatted, and
  read on its own: its colours and unclosed tags end with it.
- **Builder options**: `legacyCodes(boolean)`, `tags(TagResolver)` (your own tags, which can replace standard ones),
  `templateCacheSize`, `valueCacheSize`, `cacheFactory(CacheFactory)` (your caches, for example Guava or Caffeine with
  an expiry; the default is a bounded map without dependency), `nativeCompiler(boolean)`.

**Depend on the `minimessageflow` artifact** (the one jar with every Adventure bridge), not on `core`. Shade it and
relocate it freely: it finds its bridges relative to its own package.

## Compared with MiniMessage

The output renders the same as `MiniMessage.deserialize` with the same values (its component tree may be shaped
differently), except for four things done on purpose:

1. Text from a resolver is formatted on its own: a prefix returning `&c[Admin] ` does not colour what follows it.
2. A value written into a tag argument (`<click:run_command:'/tp %name%'>`) loses `< > ' " \ % & §`, so it can't close
   the tag or be read again. Text from a resolver is used there as plain text.
3. `<name>` placeholders. A standard tag always wins (`<red>`, `<b>`): such a placeholder is only reachable as
   `%red%`. Inside a tag argument `<name>` stays text, as in MiniMessage.
4. A value written into a tag can make a standard tag (`<%color%>` with `red`) but never a `<name>` placeholder.

## How it is fast

- **Compiled once.** Messages made only of colours, decorations, `reset`, `newline`, `click`, `hover`, `insert`,
  `gradient`, `rainbow`, `transition`, `font`, `key`, `lang` (and `tr`, `translate`, `lang_or`...), placeholders, and
  on Adventure 5 `shadow`, `sprite` and `head` (no argument, or a player name) are compiled without MiniMessage: what
  never changes is built once, a render only places the values (and colours a gradient, builds a click with its
  values). Each is checked against MiniMessage when it is compiled, and left to MiniMessage if they differ.
- **Other messages** are parsed once by MiniMessage with a marker where each value goes, and the markers are swapped at
  render; when that can't be done, they are parsed with their values, cached by values. That is every message with:
  `<selector>`/`<sel>`, `<score>`, `<nbt>`/`<data>`, `<pride>`; `<shadow>`, `<sprite>`, `<head>` and
  `<click:custom>` on Adventure 4; `<head>` with a UUID, a texture or the hat argument; `<click:show_dialog>` (which
  MiniMessage refuses); `<hover:show_item>` with data components or NBT; a user tag; or text MiniMessage's tokenizer
  reads in its own way (`a < b > c`). Every other click action, `show_item` with a key and count, `show_entity` and
  decoration arguments (`<bold:off>`) are native.

JMH 1.37, JDK 25.0.3, Adventure 4.17, Windows desktop; `-wi 5 -i 10 -f 2`, ± is the 99.9% error
(`./gradlew :benchmarks:jmh`).

**Rendering** a compiled message:

| Message | MiniMessageFlow | Allocated | `MiniMessage.deserialize` |
|---|---:|---:|---:|
| no placeholder, `Template.render()` | 1.4 ± 0.1 ns | 0 B | 1.9 ± 0.2 µs, 5.2 KB |
| no placeholder, `format(String)` (cache lookup) | 3.0 ± 0.3 ns | 0 B | |
| 3 placeholders + click | 155 ± 11 ns | 656 B | |
| shop line: 2 placeholders, a modifier, a click | 245 ± 17 ns | 1.1 KB | 7.7 ± 1.7 µs, 16 KB |
| the same, `<key>` placeholders | 261 ± 13 ns | 1.1 KB | |
| the same, `format(String, ...)` | 242 ± 15 ns | 1.1 KB | |
| the same, tags that stay with MiniMessage (markers swapped in its tree) | 1.08 ± 0.09 µs | 3.8 KB | |
| `<font>`, `<lang>`, `<key>`, `<transition>` + 2 placeholders (native) | 213 ± 39 ns | 952 B | |
| `<selector>`, `<score>` + 2 placeholders (MiniMessage, markers) | 727 ± 47 ns | 2.1 KB | |
| `<sprite>`, `<head:name>` + 2 placeholders, Adventure 5.2 (native) | 73 ± 8 ns | 328 B | |
| the same on Adventure 4.26 (MiniMessage, markers) | 479 ± 60 ns | 1.4 KB | |
| a global placeholder (resolver text, isolated) | 188 ± 18 ns | 848 B | |
| click argument, same value / changing every send | 287 ± 39 / 422 ± 53 ns | 1.5 / 1.9 KB | |
| gradient, same value / changing every send | 559 ± 48 / 556 ± 42 ns | 2.4 / 2.7 KB | |

**Compiling** a message, once per text:

| Message | Compile | Allocated | One `MiniMessage.deserialize` |
|---|---:|---:|---:|
| no placeholder | 2.3 ± 0.1 µs | 8.8 KB | 1.5 ± 0.1 µs, 5.2 KB |
| shop line (native) | 10.0 ± 1.3 µs | 28 KB | 5.9 ± 0.5 µs, 16 KB |
| the same, `verifyNative(false)` | **1.5 ± 0.2 µs** | 5.6 KB | |
| value in a click argument (native) | 5.9 ± 0.3 µs | 18 KB | |
| the same, `verifyNative(false)` | **1.0 ± 0.1 µs** | 4.1 KB | |
| `<font>`, `<lang>`, `<key>`, `<transition>` (native) | 15.6 ± 2.0 µs | 60 KB | |
| `<selector>`, `<score>` (MiniMessage, markers) | 12.5 ± 1.3 µs | 43 KB | |

A message the native compiler takes is compiled without MiniMessage; the check (on by default) adds one MiniMessage
parse to compare (two under a gradient or rainbow, whose colours depend on the values' length). So compiling costs
1-2 `MiniMessage.deserialize` with the check, a quarter of one without it. A message with other tags goes through
MiniMessage (a discovery parse and two probe parses): about three. Compile what you send often once (MessageFlow
compiles its messages on reload), or let `format` cache it.

## Modules

| Module | What |
|---|---|
| `api` | `Placeholder`, `PlaceholderValue`, `PlaceholderModifiers`, `Template`, `Resolver`, `cache.Cache`, `cache.CacheFactory` |
| `core` | `MiniMessageFlow`, the engine, the native compiler; `AdventureBridge`, the only door to version-specific Adventure |
| `compat/adventure-4`, `compat/adventure-5` | one bridge per Adventure major, compiled against its lowest minor |
| `minimessageflow` | the jar to depend on: all of the above |
| `testkit` | `FormattingContract` (the behaviour, as tests every engine runs), `ReferenceFormatter`, `ComponentRuns` |
| `benchmarks` | JMH, on demand |

## Build

Needs a JDK 21 for the toolchain; Gradle itself runs on 17+.

```bash
./gradlew build                              # on Adventure 4.17.0, the lowest supported
./gradlew build -PadventureVersion=5.2.0     # core compiled against, and every test run on, another Adventure
./gradlew publishToMavenLocal                # the minimessageflow artifact, for MessageFlow or your plugin
./gradlew :benchmarks:jmh -Pjmh.includes=Template
```

`.github/workflows/build.yml` runs `build` on 4.17.0, 4.26.1, 5.0.1, 5.1.1 and 5.2.0 (not run yet: the repository
has no remote).

## Rules

- No Adventure API that differs between versions outside `compat/`: `core` asks `AdventureBridges.current()`.
  The version matrix enforces it (a missing or deprecated API fails compilation, `-Werror`).
- A deliberate behaviour change edits `FormattingContract` in the same commit.
- [docs/PLAN.md](docs/PLAN.md) records each stage, its numbers, and what was not done.

## License

MIT
