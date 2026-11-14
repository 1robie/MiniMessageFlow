# MiniMessageFlow

A fast text formatter for [Adventure](https://github.com/KyoriPowered/adventure). It reads MiniMessage tags, legacy
colour codes (`&c`, `§c`, `&#rrggbb`, `#rrggbb`) and placeholders written `%name%` or `<name>`, compiles each message
once and renders it in nanoseconds. It runs on Adventure 4.17 (Paper 1.20.6) to 5.x (Minecraft 26), and has no
dependency besides Adventure.

It is the formatting engine of MessageFlow 1.2, tested on Paper 1.20.6, 1.21 and 26. Version 1.0.0
([changelog](CHANGELOG.md)).

## Install

From [JitPack](https://jitpack.io/#1robie/MiniMessageFlow). Adventure is not included: Paper provides it.

```kotlin
// Gradle (Kotlin DSL)
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.1robie:MiniMessageFlow:1.0.0")
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
    <version>1.0.0</version>
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
- **Builder options**: `legacyCodes(boolean)`, `tags(TagResolver)` (your own tags, which can replace standard ones),
  `templateCacheSize`, `valueCacheSize`, `cacheFactory(CacheFactory)` (your caches, for example Guava or Caffeine with
  an expiry; the default is a bounded map without dependency), `nativeCompiler(boolean)`,
  `errorHandler(BiConsumer<String, Throwable>)` (told when a resolver or a value throws; by default one warning per
  key in the log), `modifier(name, modifier)` (a modifier of this instance only), `bareHexColors(boolean)` (false: a
  `#rrggbb` without `&` is text, so `Issue #123456` keeps its number).
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
   the tag or be read again. Text from a resolver is used there as plain text.
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
- **Other messages** are parsed once by MiniMessage with a marker where each value goes, and a render fills the
  markers; when that can't be done, they are parsed with their values, cached by values (`Template.kind()` and
  `fallbackReason()` tell which, and why). That is every message with:
  `<selector>`/`<sel>`, `<score>`, `<nbt>`/`<data>`, `<pride>`; `<shadow>`, `<sprite>`, `<head>` and
  `<click:custom>` on Adventure 4; `<head>` with a UUID, a texture or the hat argument; `<click:show_dialog>` (which
  MiniMessage refuses); `<hover:show_item>` with data components or NBT; a user tag; or text MiniMessage's tokenizer
  reads in its own way (`a < b > c`). Every other click action, `show_item` with a key and count, `show_entity` and
  decoration arguments (`<bold:off>`) are native.

JMH 1.37, JDK 21.0.8, Adventure 4.17, Windows desktop; `-wi 5 -i 10 -f 2` with 1 s iterations, ± is the 99.9%
error (`./gradlew :benchmarks:jmh -Pjmh.time=1s`).

**Rendering** a compiled message:

| Message | MiniMessageFlow | Allocated | `MiniMessage.deserialize` |
|---|---:|---:|---:|
| no placeholder, `Template.render()` | 0.4 ± 0.1 ns | 0 B | 1.5 ± 0.1 µs, 5.3 KB |
| no placeholder, `format(String)` (cache lookup) | 1.9 ± 0.1 ns | 0 B | |
| 1 / 5 / 20 placeholders, each under a colour | 38 ± 1 / 118 ± 1 / 401 ± 35 ns | 264 / 472 / 1,248 B | |
| 3 placeholders + click | 132 ± 2 ns | 608 B | |
| the same, values set in a `Values` for the send | 106 ± 1 ns | 608 B | |
| the same, a `Placeholder` built for the send | 123 ± 2 ns | 936 B | |
| shop line: 2 placeholders, a modifier, a click | 114 ± 1 ns | 512 B | 5.1 ± 0.6 µs, 17 KB |
| the same, `<key>` placeholders | 113 ± 1 ns | 512 B | |
| the same, `format(String, ...)` | 118 ± 1 ns | 512 B | |
| the same, tags that stay with MiniMessage (markers filled in its tree) | 159 ± 3 ns | 968 B | |
| `<font>`, `<lang>`, `<key>`, `<transition>` + 2 placeholders (native) | 150 ± 5 ns | 848 B | |
| `<selector>`, `<score>` + 2 placeholders (MiniMessage, markers) | 112 ± 7 ns | 496 B | |
| `<sprite>`, `<head:name>` + 2 placeholders, Adventure 5.2 (native) | 70 ± 2 ns | 328 B | |
| the same on Adventure 4.26 (MiniMessage, markers) | 101 ± 3 ns | 336 B | |
| a global placeholder (resolver text, isolated) | 73 ± 1 ns | 288 B | |
| click argument, same value / changing every send | 84 ± 1 / 90 ± 1 ns | 592 / 704 B | |
| a value in a hover's text, changing every send | 126 ± 3 ns | 1.0 KB | |
| a value in a translation argument, changing every send | 180 ± 30 ns | 784 B | |
| gradient, same value / changing every send | 146 ± 4 / 140 ± 2 ns | 552 / 644 B | |

**Compiling** a message, once per text:

| Message | Compile | Allocated | One `MiniMessage.deserialize` |
|---|---:|---:|---:|
| no placeholder | 2.4 ± 0.1 µs | 8.9 KB | 1.5 ± 0.1 µs, 5.3 KB |
| shop line (native) | 9.3 ± 0.4 µs | 30 KB | 5.2 ± 0.1 µs, 17 KB |
| the same, `verifyNative(false)` | **2.1 ± 0.1 µs** | 8.3 KB | |
| value in a click argument (native) | 4.9 ± 0.1 µs | 18 KB | |
| the same, `verifyNative(false)` | **1.0 ± 0.1 µs** | 4.8 KB | |
| `<font>`, `<lang>`, `<key>`, `<transition>` (native) | 13.1 ± 0.1 µs | 61 KB | |
| `<selector>`, `<score>` (MiniMessage, markers) | 12.1 ± 0.1 µs | 46 KB | |

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
  The version matrix enforces it (a missing or deprecated API fails compilation, `-Werror`).
- A deliberate behaviour change edits `FormattingContract` in the same commit.

## License

MIT
