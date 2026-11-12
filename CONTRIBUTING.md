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
- Public API is `api` and `MiniMessageFlow`, `MiniMessageFlowTranslator` in `core`. Everything marked
  `@ApiStatus.Internal` can change in any release.

## Releasing

1. The matrix is green: `.github/workflows/build.yml` on the commit to release, or locally

   ```bash
   for v in 4.17.0 4.26.1 5.0.1 5.1.1 5.2.0; do ./gradlew build -PadventureVersion=$v || break; done
   ```

2. If rendering or compiling changed, run the benchmarks and update the tables of the README:
   `./gradlew :benchmarks:jmh` (about half an hour; `-Pjmh.includes=Template` for one class).
3. Set `version` in `gradle.properties`, the version in the README's install snippets, and rename "Unreleased" in
   `CHANGELOG.md` to the version.
4. Commit, tag the commit with the version (`git tag 1.1.0`), push the branch and the tag.
5. Open `https://jitpack.io/#1robie/MiniMessageFlow`, look up the tag and check its build log: JitPack runs
   `./gradlew build publishToMavenLocal` (`jitpack.yml`), tests included.
