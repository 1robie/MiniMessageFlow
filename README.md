# MiniMessageFlow

A fast text formatter for [Adventure](https://github.com/KyoriPowered/adventure). It reads MiniMessage tags, legacy
colour codes (`&c`, `§c`, `&#rrggbb`, `#rrggbb`) and placeholders written `%name%` or `<name>`, compiles each message
once and renders it in nanoseconds. It runs on Adventure 4.17 (Paper 1.20.6) to 5.x (Minecraft 26), and has no
dependency besides Adventure.

It is the formatting engine of MessageFlow 1.2, tested on Paper 1.20.6, 1.21 and 26. Version 1.2.1
([changelog](CHANGELOG.md)).

## Install

From [JitPack](https://jitpack.io/#1robie/MiniMessageFlow). Adventure is not included: Paper provides it.

```kotlin
// Gradle (Kotlin DSL)
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.1robie:MiniMessageFlow:1.2.1")
}
// Shade it into your plugin and relocate it (shadow plugin):
tasks.shadowJar {
    relocate("fr.robie.minimessageflow", "your.plugin.libs.minimessageflow")
}
```

```xml
<!-- Maven -->
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.1robie</groupId>
    <artifactId>MiniMessageFlow</artifactId>
    <version>1.2.1</version>
</dependency>
<!-- maven-shade-plugin: <relocation><pattern>fr.robie.minimessageflow</pattern>
     <shadedPattern>your.plugin.libs.minimessageflow</shadedPattern></relocation> -->
```

Relocating is safe: the library finds its Adventure bridges relative to its own package. A local build
(`./gradlew publishToMavenLocal`) installs it as `fr.robie.minimessageflow:minimessageflow` instead.

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
  `<n:plural:item:items>`, `%name.upper%`... (yours: `modifier(...)` on the builder, or
  `PlaceholderModifiers.register` for every instance, before messages are compiled). A value of the call is plain
  text, never read as tags or colour codes. A value can be computed at each render (`Placeholder.of("time", () -> ...)`)
  or for the viewer (`Placeholder.of("hp", viewer -> ...)`). Each of these can be a component instead, inserted with
  its own style: `Placeholder.of("x", component)`, `Placeholder.ofComponent("item", () -> item.displayName())`,
  `Placeholder.ofViewerComponent("rank", viewer -> ...)`. In a tag argument or with a modifier, a component gives its
  plain text. Without a value, a placeholder stays as written.
- **Values for many sends**: where a message goes to many players, declare its names once and set the values in
  a `Values` kept between sends, instead of building a `Placeholder` each time:
  `ValueKey PLAYER = builder.key("player")` on the builder, `Values values = text.newValues()` (one per thread, not
  thread-safe), then `values.set(PLAYER, name)` and `template.render(values, player)`.
- **Constants**: `constant("prefix", "<gold>[Shop]")` on the builder, for what never changes. A message with only
  constants is rendered once, when it is compiled.
- **Resolvers** give the values a call doesn't: global placeholders, PlaceholderAPI... Their text is formatted, and
  read on its own: its colours and unclosed tags end with it. A resolver can also give a component
  (`resolveComponent`, asked first), inserted as it is and never parsed.
- **Builder options**: `legacyCodes(boolean)`, `tags(TagResolver)` and `tag(name, Tag)` (your own tags, which can
  replace standard ones; each call adds to the others; a tag of the message, which may be resolved once when it is
  compiled), `valueTag(name, ValueTag)` (a tag computed at each render for the viewer, below), `templateCacheSize`, `valueCacheSize`, `cacheFactory(CacheFactory)` (your caches, for example Guava or Caffeine with
  an expiry; the default is a bounded map without dependency), `nativeCompiler(boolean)`,
  `errorHandler(BiConsumer<String, Throwable>)` (told when a resolver or a value throws; by default one warning per
  key in the log), `modifier(name, modifier)` (a modifier of this instance only), `bareHexColors(boolean)` (false: a
  `#rrggbb` without `&` is text, so `Issue #123456` keeps its number), `numberStyle(NumberStyle)` (how the number
  modifiers read and write separators, below).
- **Value tags**: `valueTag("papi", (arguments, viewer) -> ...)` makes `<papi:player_name>` and
  `%papi:player_name%` (the same placeholder) ask your function at each render, with the viewer. Modifiers apply
  (`<papi:vault_eco_balance:comma>`), a message using it keeps the fast path, and it is never called while a message
  is compiled. Return text (formatted, as a resolver's) or a component (`resolveComponent`).
- **Number style**: `AUTO` (the default) reads `1,234` as 1234 and `1,5` as 1.5, and writes `1,234.50`; `ENGLISH`
  reads and writes `1,234.50` only; `EUROPEAN` reads `1.234,50` and `1 234,50` and writes `1.234,50`. A value that
  does not fit the style is not a number, and the placeholder is kept as written. To let a server owner choose:
  `builder.numberStyle(NumberStyle.valueOf(config.getString("number-style", "auto").toUpperCase(Locale.ROOT)))`.
- **A template** tells what it is: `placeholders()` lists the keys it asks for (compare with the keys you give to
  find a `%palyer%`), `kind()` how it renders (`STATIC`, `NATIVE`, `MARKERS`, `PARSED`, fastest first), and
  `renderPlain(...)` gives plain text for a console or a log. `compileAll(Map)` compiles a configuration at once on
  reload.
- **Legacy codes** are read in text and in a hover's text, not inside a `<click>` or `<insert>` tag: a URL keeps
  its `&b=2` and `#abcdef`.

**Translations** for Adventure's `GlobalTranslator`, per player language:

```java
MiniMessageFlowTranslator translator = MiniMessageFlowTranslator.create(Key.key("myplugin", "messages"), text)
        .register("shop.bought", Locale.ENGLISH, "<green>You bought <arg:0> for <gold><arg:1>")
        .register("shop.bought", Locale.FRENCH, "<green>Tu as acheté %0% pour <gold>%1%")
        .defaultLocale(Locale.ENGLISH);
GlobalTranslator.translator().addSource(translator);

player.sendMessage(Component.translatable("shop.bought", item, price));   // in the player's language
```

Arguments are `<arg:i>`/`<argument:i>` (as with MiniMessage's translator) or `%i%`/`<i>`; the locale falls back as
resource bundles do (`zh_Hant_TW` to `zh_Hant`, `zh_TW`, `zh`, then `Locale.ROOT`), then to the default locale; the locale is the render's viewer (`Identity.LOCALE`). A template can use another
of the same translator (`<lang:other.key>`) on every Adventure version (4.17's `GlobalTranslator` does not render those
itself); a template that uses itself stops after 16 levels here, but Adventure 4.18+ renders what is left again, as it
would with MiniMessage's translator.

## Compared with MiniMessage

The output renders the same as `MiniMessage.deserialize` with the same values (its component tree may be shaped
differently), except for four things done on purpose:

1. Text from a resolver is formatted on its own: a prefix returning `&c[Admin] ` does not colour what follows it.
2. A value written into a tag argument (`<click:run_command:'/tp %name%'>`) loses `< > ' " \ % & §`, so it can't close
   the tag or be read again, and stays one argument (an argument without quotes is read as if it had them). Text
   from a resolver is used there as plain text.
3. `<name>` placeholders. A standard tag always wins (`<red>`, `<b>`): such a placeholder is only reachable as
   `%red%`. Inside a tag argument `<name>` stays text, as in MiniMessage.
4. A value written into a tag can make a standard tag (`<%color%>` with `red`) but never a `<name>` placeholder.

## How it is fast

- **Compiled once.** Messages made only of colours, decorations, `reset`, `newline`, `click`, `hover`, `insert`,
  `gradient`, `rainbow`, `transition`, `font`, `key`, `lang` (and `tr`, `translate`, `lang_or`...), placeholders, and
  on Adventure 5 `shadow`, `sprite` and `head` (no argument, or a player name) are compiled without MiniMessage: what
  never changes is built once, a render only places the values (and colours a gradient, builds a click, a hover's
  text or a translation argument with its values). Each is checked against MiniMessage when it is compiled, and
  left to MiniMessage if they differ.
- **No copies.** Text and text values of one style that are all of their parent are one component, their text
  joined. A component over children is made with Adventure's own text component constructor and a list that is
  never changed, which `children(list)` would copy (where the constructor can't be reached, Adventure as a named
  module, the API is used: `-Dminimessageflow.directComponents=false` does the same).
- **Other messages** are parsed once by MiniMessage with a marker where each value goes, and a render fills the
  markers; when that can't be done, they are parsed with their values, cached by values (`Template.kind()` and
  `fallbackReason()` tell which, and why). That is every message with:
  `<selector>`/`<sel>`, `<score>`, `<nbt>`/`<data>`, `<pride>`; `<shadow>`, `<sprite>`, `<head>` and
  `<click:custom>` on Adventure 4; `<head>` with a UUID, a texture or the hat argument; `<click:show_dialog>` (which
  MiniMessage refuses); `<hover:show_item>` with data components or NBT; a user tag; or text MiniMessage's tokenizer
  reads in its own way (`a < b > c`). Every other click action, `show_item` with a key and count, `show_entity` and
  decoration arguments (`<bold:off>`) are native.

JMH 1.37, JDK 21.0.8, Windows desktop (12 threads); `-wi 5 -i 10 -f 2` with 1 s iterations, ± is the 99.9% error
(`./gradlew :benchmarks:jmh -Pjmh.time=1s`, `-PadventureVersion=5.2.0` for the 5.2 column). Adventure 5.x keeps the
children a render gives it without copying them, hence the lower numbers there.

**Rendering**, the values changing at every send (`RenderBenchmark`: 16 sets of values made before the run, so the
render alone is timed):

| Message | Adventure 4.17 | Adventure 5.2 |
|---|---:|---:|
| `<gray>Hello <white>%name%` | 39 ± 1 ns, 185 B | 23 ± 1 ns, 116 B |
| `<green>Welcome %name%!` (one component) | 28 ± 1 ns, 101 B | 28 ± 1 ns, 101 B |
| 5 / 20 placeholders, each under a colour | 134 ± 4 / 451 ± 1 ns, 353 / 980 B | 69 ± 1 / 258 ± 2 ns, 254 / 766 B |
| 3 placeholders + click | 92 ± 1 ns, 315 B | 56 ± 1 ns, 231 B |
| the same, values set in a `Values` | 83 ± 1 ns, 315 B | 49 ± 1 ns, 231 B |
| the same, then serialised to JSON as a server sends it | 1.49 ± 0.06 µs | 0.80 ± 0.01 µs |
| shop line: 2 placeholders, a modifier, a click | 80 ± 1 ns, 271 B | 49 ± 1 ns, 195 B |
| the same, tags that stay with MiniMessage (markers filled in its tree) | 97 ± 1 ns, 562 B | 60 ± 1 ns, 340 B |
| `<font>`, `<lang>`, `<key>`, `<transition>` + 2 placeholders (native) | 141 ± 1 ns, 736 B | 99 ± 1 ns, 567 B |
| a value in a click argument | 56 ± 1 ns, 284 B | 54 ± 1 ns, 300 B |
| a value in a hover's text | 97 ± 1 ns, 453 B | 57 ± 2 ns, 315 B |
| a value in a translation argument | 72 ± 1 ns, 376 B | 78 ± 1 ns, 388 B |
| gradient over a value | 153 ± 3 ns, 484 B | 76 ± 1 ns, 362 B |
| `MiniMessage.deserialize` of the shop line, for comparison | 6.2 ± 1.0 µs, 17 KB | 4.1 ± 0.1 µs, 14 KB |

**Rendering** with the same values as the last render: a message sent to many players, a line drawn again. Every
value is still asked for (suppliers, resolvers), then the last component is given again (Adventure 4.17; 5.2 is the
same within 1 ns):

| Message | MiniMessageFlow | Allocated | `MiniMessage.deserialize` |
|---|---:|---:|---:|
| no placeholder, `Template.render()` | 0.35 ± 0.01 ns | 0 B | 1.8 ± 0.2 µs, 5.2 KB |
| no placeholder, `format(String)` (cache lookup) | 1.8 ± 0.1 ns | 0 B | |
| 3 placeholders + click | 18 ± 1 ns | 0 B | |
| the same, values set in a `Values` for the send | 12 ± 1 ns | 0 B | |
| the same, a `Placeholder` built for the send | 30 ± 1 ns | 112 B | |
| shop line (the modifier writes its text) | 29 ± 1 ns | 48 B | 6.2 ± 1.0 µs, 17 KB |
| the same, `<key>` placeholders / `format(String, ...)` | 29 ± 1 / 33 ± 1 ns | 48 B | |
| `<selector>`, `<score>` + 2 placeholders (MiniMessage, markers) | 12 ± 1 ns | 0 B | |
| `<sprite>`, `<head:name>` + 2 placeholders: 5.2 native / 4.17 markers | 12 ± 1 / 44 ± 1 ns | 0 / 160 B | |
| a global placeholder (resolver text) | 19 ± 1 ns | 0 B | |
| a value in a click argument / hover's text / translation argument | 12 / 16 / 20 ± 1 ns | 0 B | |
| gradient over a value | 5.8 ± 0.1 ns | 0 B | |

**Several threads** rendering the same templates (`SharedRenderBenchmark`, values changing at every send, Adventure
4.17). On this machine a render that writes nothing takes about 1.5 times as long on 8 threads as on one:

| Message | 1 thread | 8 threads |
|---|---:|---:|
| `<green>Welcome %name%!` | 27 ± 1 ns | 52 ± 1 ns |
| 3 placeholders + click, one a component | 197 ± 27 ns | 289 ± 4 ns |
| the same, same values each time | 17 ± 1 ns | 27 ± 1 ns |
| 5 placeholders, each under a colour | 79 ± 1 ns | 156 ± 1 ns |
| gradient over a value | 114 ± 1 ns | 195 ± 2 ns |

**Compared with [FastMiniMessage](https://github.com/Neoramm/FastMiniMessage)** 2.0.0-beta.2, Adventure 5.2
(`FastMiniMessageBenchmark`, `-PfastMiniMessageVersion=2.0.0-beta.2`). It is given the values as components in a
scope it reuses, this library as text; both are checked to show the same text first:

| Message | MiniMessageFlow | FastMiniMessage |
|---|---:|---:|
| 3 placeholders + click, values changing | 54 ± 1 ns, 231 B | 103 ± 1 ns, 608 B |
| the same, values set for each send | 49 ± 1 ns, 231 B | 129 ± 1 ns, 672 B |
| the same, same values | 18 ± 1 ns, 0 B | 104 ± 4 ns, 592 B |
| `<green>Welcome %name%!`, values changing | 27 ± 1 ns, 101 B | 62 ± 2 ns, 456 B |
| 5 placeholders in 5 colours, values changing | 58 ± 1 ns, 246 B | 166 ± 1 ns, 1,240 B |
| gradient over a value, values changing | 69 ± 1 ns, 362 B | 224 ± 1 ns, 1,192 B |
| no placeholder | 0.36 ns, 0 B | 0.38 ns, 0 B |

**Compiling** a message, once per text (Adventure 4.17):

| Message | Compile | Allocated | One `MiniMessage.deserialize` |
|---|---:|---:|---:|
| no placeholder | 2.3 ± 0.1 µs | 8.9 KB | 1.5 ± 0.1 µs, 5.3 KB |
| shop line (native) | 8.8 ± 0.1 µs | 31 KB | 5.0 ± 0.1 µs, 17 KB |
| the same, `verifyNative(false)` | **2.1 ± 0.1 µs** | 9.1 KB | |
| value in a click argument (native) | 5.5 ± 0.2 µs | 19 KB | |
| the same, `verifyNative(false)` | **1.2 ± 0.1 µs** | 5.6 KB | |
| `<font>`, `<lang>`, `<key>`, `<transition>` (native) | 13.3 ± 0.2 µs | 62 KB | |
| `<selector>`, `<score>` (MiniMessage, markers) | 11.9 ± 0.5 µs | 47 KB | |

A message the native compiler takes is compiled without MiniMessage; the check (on by default) adds one MiniMessage
parse to compare (two under a gradient or rainbow, whose colours depend on the values' length). So compiling costs
1-2 `MiniMessage.deserialize` with the check, less than half of one without it. A message with other tags goes
through MiniMessage (a discovery parse and two probe parses): about three. Compile what you send often once
(MessageFlow compiles its messages on reload), or let `format` cache it.

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

`.github/workflows/build.yml` runs `build` on 4.17.0, 4.26.1, 5.0.1, 5.1.1 and 5.2.0 at each push.

JitPack builds a tag with `./gradlew build publishToMavenLocal`: the tests run (on Adventure 4.17.0) before the jar is
published. [CONTRIBUTING.md](CONTRIBUTING.md) has the release checklist.

## Rules

- No Adventure API that differs between versions outside `compat/`: `core` asks `AdventureBridges.current()`.
  The one internal of Adventure `core` uses, `TextComponentImpl(List, Style, String)` (the same on every version),
  is in `Children`, checked when it loads and replaced by the API when it can't be used.
  The version matrix enforces it (a missing or deprecated API fails compilation, `-Werror`).
- A deliberate behaviour change edits `FormattingContract` in the same commit.

## License

MIT

## Made with AI

Much of this library's code, tests and benchmarks was written with AI assistance (Claude). I direct the work, review
and check every change before it is committed, and run the tests on every supported Adventure version and the
benchmarks before each release.
