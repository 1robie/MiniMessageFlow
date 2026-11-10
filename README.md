# MiniMessageFlow

A fast text formatter for [Adventure](https://github.com/KyoriPowered/adventure): MiniMessage syntax and
`%placeholders%`, compiled once and rendered many times, with the same output as MiniMessage, on Adventure
4.17 (Paper 1.20.6) to 5.x (Minecraft 26).

**Status: stage 2 of the [plan](docs/PLAN.md).** Messages made of colours, decorations, clicks, hovers and
placeholders are compiled without MiniMessage (checked against it once, at compile); others are compiled once with
MiniMessage and the values swapped in at render. MessageFlow can use it behind `formatter.engine: minimessageflow`.
Nothing is published (`./gradlew publishToMavenLocal` for local use).

```java
MiniMessageFlow text = MiniMessageFlow.builder()
        .resolver(Resolver.of(globals))          // %server%, <server>...: formatted text, read on its own
        .build();

Template shop = text.compile("<green>%item%</green> for <gold><price:comma></gold>");
Component line = shop.render(Placeholder.of("item", "Sword", "price", "1250"), player);
```

## Modules

| Module | What |
|---|---|
| `api` | `Placeholder`, `PlaceholderValue`, `PlaceholderModifiers` (ported from MessageFlow 1.1), `Template`, `Resolver` |
| `core` | `MiniMessageFlow` and the engine; `AdventureBridge`, the only door to version-specific Adventure |
| `compat/adventure-4`, `compat/adventure-5` | one bridge per Adventure major, compiled against its lowest minor |
| `minimessageflow` | the one jar users depend on: all of the above |
| `testkit` | `FormattingContract` (MessageFlow's behaviour as tests), `ReferenceFormatter`, `ComponentRuns` |
| `benchmarks` | JMH, on demand |

## Build

Java 21 (Gradle finds it through toolchains; the build itself runs on 21+).

```bash
./gradlew build                              # on Adventure 4.17.0, the lowest supported
./gradlew build -PadventureVersion=5.2.0     # core compiled against, and every test run on, another Adventure
./gradlew :benchmarks:jmh                    # all benchmarks
./gradlew :benchmarks:jmh -Pjmh.includes=Baseline
```

CI runs `build` once per Adventure of the matrix (`.github/workflows/build.yml`).

## Rules

- No Adventure API that differs between versions outside `compat/`: `core` asks `AdventureBridges.current()`.
  The matrix build is what enforces it (a missing or deprecated API fails compilation, `-Werror`).
- A deliberate behaviour change edits `FormattingContract` in the same commit.

## License

MIT
