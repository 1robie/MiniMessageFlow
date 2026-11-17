# Contributing

## Working on the code

```bash
./gradlew build                              # compiles and tests on Adventure 4.17.0, the lowest supported
./gradlew build -PadventureVersion=5.2.0     # the same on another Adventure
```

- Version-specific Adventure API goes in `compat/adventure-N` only, behind `AdventureBridge`. `core` is compiled
  with `-Werror` against each Adventure of the matrix, so a missing or deprecated API fails the build.
- A deliberate change of behaviour edits `FormattingContract` (and `ReferenceFormatter` when the reference changes)
  in the same commit, and gets a line under "Unreleased" in `CHANGELOG.md`.
- A new tag in the native compiler is checked against MiniMessage by the contract tests: add messages that use it to
  `FormattingContract`, and run the whole matrix, since MiniMessage reads some tags differently between versions.
- Public API is `api` and `MiniMessageFlow`, `MiniMessageFlowTranslator` in `core`, and `platform/paper`'s public
  classes. Everything marked `@ApiStatus.Internal` can change in any release.
- `platform/paper` is compiled against Paper 1.20.6: newer Paper API goes behind `PaperBridge` (found when the server
  runs). Its tests fake Bukkit; `./gradlew :platform:paper:test -PpaperVersion=26.3.build.253-stable` runs them
  against another Paper (Java 25 for 26.x).

## Smoke test on real servers

`platform/paper-smoke` is a test plugin (never published) that shades and relocates `paper-minimessageflow` as a
plugin does, and checks it on real Paper and Folia servers 1.20.6, 1.21.11 and 26.3, with PlaceholderAPI and
MiniPlaceholders on Paper: what can be checked without a player (the version bridges found, PlaceholderAPI in every
form, MiniPlaceholders' API, the server placeholders, real items, default files copied from the jar, translations).

```bash
./gradlew :platform:paper-smoke:smoke -Psmoke.eula=true --continue   # all six, about a minute once downloaded
./gradlew :platform:paper-smoke:smokePaper1_21_11 -Psmoke.eula=true  # one
```

`-Psmoke.eula=true` accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the servers it starts: without it
they don't start. The first run downloads the servers (from PaperMC, which fetches Mojang's) and the plugins (Hangar,
Modrinth) into `platform/paper-smoke/build/`. Each server writes `plugins/MiniMessageFlowSmoke/result.txt` and stops;
the task fails on any `FAIL` line. Per-viewer placeholders need a player: only the unit tests cover them.

## Releasing

1. The matrix is green: `.github/workflows/build.yml` on the commit to release, or locally

   ```bash
   for v in 4.17.0 4.26.1 5.0.1 5.1.1 5.2.0; do ./gradlew build -PadventureVersion=$v || break; done
   ```

2. If `platform/paper` changed, run the smoke test on real servers (above).
3. If rendering or compiling changed, run the benchmarks and update the tables of the README:
   `./gradlew :benchmarks:jmh` (about half an hour; `-Pjmh.includes=Template` for one class).
4. Set `version` in `gradle.properties`, the version in the README's install snippets, and rename "Unreleased" in
   `CHANGELOG.md` to the version.
5. Commit, tag the commit with the version (`git tag 1.1.0`), push the branch and the tag.
6. Open `https://jitpack.io/#1robie/MiniMessageFlow`, look up the tag and check its build log: JitPack runs
   `./gradlew build publishToMavenLocal` (`jitpack.yml`), tests included.
