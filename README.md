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
  `gradient`, `rainbow` and placeholders are compiled without MiniMessage: what never changes is built once, a render
  only places the values (and colours a gradient, builds a click with its values). Each is checked against MiniMessage
  when it is compiled, and left to MiniMessage if they differ.
- **Other messages** (`<font>`, `<lang>`, `<transition>`, a user tag...) are parsed once by MiniMessage with a marker
  where each value goes, and the markers are swapped at render; when that can't be done, they are parsed with their
  values, cached by values.

JMH, JDK 25 (`./gradlew :benchmarks:jmh`), short runs:

| Message | MiniMessageFlow | `MiniMessage.deserialize` |
|---|---:|---:|
| no placeholder | ~1 ns, 0 B | 1.4 µs |
| 3 placeholders + click | 0.14 µs | |
| shop line, 2 placeholders + modifier + click | 0.25 µs, 1.1 KB | 4.7 µs, 16 KB |
| click argument, value changing every send | 0.34 µs | |
| gradient, value changing every send | 0.49 µs | |

Compiling is slower than one `MiniMessage.deserialize` (it parses with MiniMessage to check, not measured yet):
compile what you send often once, or let `format` cache it.

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
