# MiniMessageFlow

A fast text formatter for [Adventure](https://github.com/KyoriPowered/adventure): MiniMessage syntax and
`%placeholders%`, compiled once and rendered many times, with the same output as MiniMessage, on Adventure
4.17 (Paper 1.20.6) to 5.x (Minecraft 26).

**Status: stage 0 of the [plan](docs/PLAN.md).** The build, the Adventure version matrix and the test safety net
exist; the engine does not yet. Nothing is published.

## Modules

| Module | What |
|---|---|
| `api` | `Placeholder`, `PlaceholderValue`, `PlaceholderModifiers` (ported from MessageFlow 1.1) |
| `core` | the engine (stage 1+); `AdventureBridge`, the only door to version-specific Adventure |
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
