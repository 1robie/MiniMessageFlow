# Formatter library plan (draft, 2026-11-10, revised after review)

Move MessageFlow's formatting into its own library, in its own repository, and make it the best text formatter
for Adventure: fast like [FastMiniMessage](https://github.com/Neoramm/FastMiniMessage) (FMM), but always as correct
as MiniMessage, on every Adventure version from 4.17 to 5.x.

Name: **MiniMessageFlow** (repository `MiniMessageFlow`, groupId `fr.robie.minimessageflow`, like MessageFlow's
`fr.robie.messageflow`), **MIT** license.

Decisions taken (2026-11-10):
- separate repository with its own releases; MessageFlow depends on it like any library;
- **Gradle** (Kotlin DSL), see 3;
- placeholders written `%name%` **and** MiniMessage-style `<name>`, see 4.6;
- hybrid parser: our own compiler for common tags, real MiniMessage for everything else, **chosen per message**
  (a message is either fully native or fully MiniMessage, see 4.3);
- Adventure 4.17 (Paper 1.20.6) up to Adventure 5.x (Minecraft 26), more features on newer versions;
  version-specific code lives in one module per Adventure major (see 3);
- **Paper only**: Adventure is always present. No Spigot-without-Adventure support in the library;
- legacy codes (`&c`, `§c`, `&#rrggbb`, `#rrggbb`, `&x&r&r...`) read and converted on input;
- string-first API (`%name%`, modifiers, `Placeholder`), plus an optional typed fast path;
- text from resolvers that may carry formatting (global placeholders, PlaceholderAPI) is compiled **isolated**:
  its colours and unclosed tags never reach the text after it (see 4.2, a behaviour change).

Non-goals:
- running without Adventure, or on platforms other than Paper (the API has no Bukkit type, so other Adventure
  platforms may work, but they are not tested or supported);
- replacing MiniMessage's syntax or adding our own tags;
- sending (titles, boss bars, sounds): that stays in MessageFlow.

## 1. What we start from

MessageFlow today (`core/formatter/AdventureMessageFormatter`, `common/formatter/*`, `common/util/PlaceholderScanner`):
- `%key%` tokens found by `PlaceholderScanner` (single pass, no regex), modifiers `%price.comma%` in
  `PlaceholderModifiers`.
- Placeholders become `<mfph_N>` tags, MiniMessage parses the text once into a component holding marker texts,
  and each send swaps markers for values (`Template`). Values are never parsed as MiniMessage: safe.
- Three Guava caches: `templates` (by message), `resolvedTemplates` (text changed by global placeholders /
  PlaceholderAPI or a value inside a tag), `parsedWithValues` (gradients and hovers, by text and values).
- Legacy codes go through a string pre-pass (`colorMiniMessage`) before MiniMessage.
- Tied to Bukkit (`Player`, `Plugin`, `CommandSender`) and to Guava.

Numbers (from `FAST_TEMPLATES_PLAN.md`, JMH, 2026-11-09; **TODO: copy the hardware and JDK of that run here**):
static 54 ns, shop line with 3 placeholders and a click ~2 µs, click argument with a value that changes ~7-9 µs,
gradient with a changing value ~7.8 µs. `MiniMessage.deserialize` with resolvers: 12-14 µs.

Limits of the current design: every send still scans the text and looks up a cache by `String`; values in tag
arguments and gradients create one cache entry per value (churn); `<gradient>` over a changing value is a full
parse; formatting can't be used outside Bukkit.

## 2. What we take from FMM, and what we do differently

Take:
- **compile once, render many**: an immutable tree of nodes, static parts folded into a finished `Component`
  (a fully static template renders in ~1 ns, no allocation);
- colour and decoration flattening, literal merging at compile time;
- **slots in tag arguments** (`<click:run_command:'/tp %player%'>`): only that `ClickEvent` is rebuilt at render;
- **transform nodes** for `<gradient>` / `<rainbow>` over changing children;
- typed keys stored in an array for hot loops (as an option, not the main API);
- a large equivalence suite against MiniMessage.

Do differently (FMM column as read on 2026-11-10, to re-check before publishing this document):

| FMM | MiniMessageFlow |
|---|---|
| Adventure 5.2+ only, Java 21/25 | Adventure 4.17 to 5.x, Java 21; newer tags enabled when present |
| own parser for every tag: parity must be kept by hand for ever | own compiler for common tags, **MiniMessage for any message with another tag**: never wrong, even on tags added later |
| dynamic tags must be registered before compiling | unknown tags and user `TagResolver`s work through MiniMessage |
| typed `PayloadSchema` is the main API | `%name%` / `Placeholder.of(...)` first, typed slots optional |
| legacy input through a separate converter | legacy codes read by the scanner itself, no string pre-pass |
| no modifiers | `%price.comma%`, `%name.upper%`, `%n.plural:item:items%`... |

Our own process rules (not a comparison): JMH in CI with one baseline per table, hardware and JDK printed, cold
compile shown; small public API, semantic versioning, `@ApiStatus` annotations, binary-compat check.

## 3. Repository and modules

Gradle with the Kotlin DSL and the wrapper, Java 21 bytecode through a toolchain (runs on Java 25 for Minecraft 26).
- Versions in one catalog (`gradle/libs.versions.toml`); shared settings in a convention plugin (`build-logic/`).
- Adventure version matrix through a property: `./gradlew test -PadventureVersion=4.17.0`; CI runs one job per
  version.
- JMH through `me.champeau.jmh` (`./gradlew :benchmarks:jmh`).
- The one-jar artifact built with Shadow; published to Maven Central (`com.vanniktech.maven.publish`) and JitPack,
  so MessageFlow, which stays on Maven, depends on it like any library.
- API stability checked with the binary-compatibility plugin (`japicmp`) against the last release.

No runtime dependency except Adventure, which Paper provides (no Guava, no Caffeine: a small bounded cache of our
own).

```
minimessageflow/
  api/                 public types: MiniMessageFlow, Template, Placeholder, PlaceholderValue, Modifier, Resolver, Slot.
                       Uses only Adventure types that exist unchanged in every supported version
                       (Component, Style, Pointered, TagResolver).
  core/                scanner, compiler, nodes, passes, renderer, caches, MiniMessage engine.
                       Compiled against the lowest supported Adventure (4.17).
                       Reaches version-specific Adventure only through the AdventureBridge interface.
  compat/
    adventure-4/       AdventureBridge for 4.17 - 4.x                    (compiled against 4.17)
    adventure-5/       AdventureBridge for 5.x + tags that only exist there
                       (shadow color, sprites, heads...)                 (compiled against 5.x)
    adventure-N/       added the day Adventure breaks again; nothing else moves
  minimessageflow/            the one artifact users depend on: api + core + every compat module in one jar
  testkit/             equivalence corpus, component normaliser, helpers; reused by MessageFlow's tests
  benchmarks/          JMH (not in the normal build)
```

How this absorbs an Adventure break:
- **One rule**: no call to an Adventure API that differs between versions outside `compat/`. `AdventureBridge`
  is small and internal (`@ApiStatus.Internal`): what tags exist, how a style/event that changed is built, the
  gradient colour maths of that version, feature flags (`hasShadowColor()`, `hasVirtualComponents()`).
- **Selection once at startup**: `core` tries the bridges newest first by class lookup (a fixed list of class
  names, no `ServiceLoader`: plugins shade and relocate this library, and service files break without a shade
  transformer). The chosen bridge is a `static final`; nothing is detected per send, no `NoClassDefFoundError`
  caught on the render path.
- **CI proves the rule**: `core` is compiled *and* tested against every Adventure of the matrix
  (`-Dadventure.version=4.17.0`, newest 4.x, each 5.x minor). A use of an API missing in one version fails the
  build, not a server.
- **New Adventure major**: add `compat/adventure-N`, add a matrix row, add its class name to the lookup list.
  `api` and `core` change only if a type in the public API itself was removed, which is the one case that needs
  a MiniMessageFlow major version.
- **Dropping an old version**: delete its compat module and matrix row.
- A tag that exists only on a new Adventure is registered by that compat module as a native tag or simply left
  to MiniMessage; `core` never names it.

## 4. Architecture

```
text ──Scanner──> tokens ──┬─ all tags native ──Compiler──> Node tree ──passes──> native Template ─┐
                           │                                                                      ├─ render(values, viewer) ─> Component
                           └─ any other tag ──────────────> MiniMessage + markers ─> MM Template ──┘
```

### 4.1 Scanner
- One pass over `char`s, no regex, no intermediate string: MiniMessage tags `<...>` with quoted arguments and
  escapes, `%placeholder%` with modifiers (same rules as `PlaceholderScanner`: `100% sure %player%` still works),
  legacy codes `&`/`§`, `&#rrggbb`, `#rrggbb`, `&x&r&r&g&g&b&b`.
- Legacy codes are only read in text, never inside a tag (`<color:#ff0000>`, a URL in a click argument).
- Gives positions for error messages (`unclosed <hover> at column 12`).

### 4.2 Nodes (native templates)
- `Static`: a finished `Component`.
- `Value`: a placeholder value inserted as a component, with the style it inherits.
- `Text slot`: a tag argument made of literal parts and placeholders (`'/tp %player%'`). The value is put in the
  argument as is: nothing is reparsed, so nothing is removed (a URL keeps its `&` and `%`).
- `Event`: a click / hover / insertion whose argument has slots; rebuilds only the event.
- `Transform`: gradient / rainbow over dynamic children; re-colours at render with the colour maths of the
  running Adventure version (from the bridge, tested equal).
- `Parsed`: text from a resolver that is allowed to carry formatting (global placeholders, PlaceholderAPI,
  `Placeholder.parsed`). Compiled lazily on its own, cached by its text, and **isolated**: it is a closed
  subtree, so a colour or an unclosed tag in it ends with it.

  Behaviour change from MessageFlow 1.1.x, where this text is pasted into the message before parsing: a prefix
  that returns `&c[Admin] ` no longer colours the name after it. To colour what follows, put the colour in the
  message (`<red>%prefix% %player%`). Goes in the 1.2.0 changelog and migration notes.

Passes after compiling: flatten colours and decorations onto leaves, merge neighbouring literals with the same
style, fold every subtree without slots into `Static`. Click, hover and insertion are **not** flattened: they
stay on the parent, so they are not copied onto every leaf and the serialized component does not grow.
A template with no slot is just one `Component`.

### 4.3 Native or MiniMessage: decided per message
MiniMessage is not made of independent subtrees: unclosed tags and `<reset>` cross boundaries, pre-process tags
put raw text back into the token stream, and a user `TagResolver` can redefine `<red>`. So there is no fallback
*node*. A message is compiled natively only when:
- every tag in it is in the native set below, and
- no user `TagResolver` claims one of its tag names (`resolver.has(name)`, checked at compile time).

Otherwise the whole message becomes a **MiniMessage template**: today's technique (marker resolvers, compiled
once, values swapped at render; cached by values in a small bounded cache when a value sits in a tag argument or
under a gradient, where sanitizing `< > ' " \ % & §` still applies because the value is reparsed).
This is the stage 1 engine, kept for ever.

Native set, first release: colors (named, hex, `color:`), decorations and their negations, `reset`, `newline`,
`click`, `hover` (show_text), `insert`, `gradient`, `rainbow`.
Not native at first (MiniMessage): `font`, `transition`, `key`, `lang`/`translatable`, `selector`, `score`,
`pride`, and everything else. A tag moves to native only with its equivalence tests and a benchmark that shows
it matters.

### 4.4 Values and safety
- Value kinds (as `PlaceholderValue` today): text, component, lazy `Supplier`, viewer function, typed number
  (so modifiers like `comma`, `2f` don't reparse strings).
- Text values are **never** read as MiniMessage or legacy codes unless the caller asks
  (`Placeholder.parsed("rank", "<red>Admin")`, isolated like every `Parsed` node).
- **Resolution order** for `%name%` at render: values of the call, then the resolvers of the `MiniMessageFlow`
  instance in registration order, then the literal text `%name%`. Which one applies is not known at compile
  time, so every `%name%` is a slot; a template is static only when its text has no placeholder at all.
- The viewer is an Adventure `Pointered`/`Audience`, not a Bukkit `Player`, so `api` and `core` have no Bukkit
  dependency and are tested without a server. MessageFlow passes the player; a PlaceholderAPI resolver casts it.

### 4.5 API sketch
```java
MiniMessageFlow text = MiniMessageFlow.builder()
        .legacyCodes(true)
        .resolver(globalPlaceholders)          // %server%, PlaceholderAPI...
        .tags(myTagResolver)                   // messages using these tags go through MiniMessage
        .build();

Template shop = text.compile("<green>%item%</green> for <gold>%price.comma%</gold> <click:run_command:'/buy %id%'>[Buy]");
Component c = shop.render(Placeholder.of("item", "Sword", "price", 1250, "id", "42"), player);

// optional fast path for hot loops (action bars every tick)
Slot<Integer> HEALTH = Slot.number("health");   // not "Key": that name is Adventure's
Template bar = text.compile("<red>❤ %health%", HEALTH);
Component c2 = bar.render(r -> r.set(HEALTH, 18));   // array slot, no map lookup
```
`text.format(String, Placeholder, viewer)` stays for ad-hoc strings (bounded cache of compiled templates; costs
one cache lookup per call on top of `render`).

### 4.6 `<name>` placeholders
Both spellings mean the same placeholder: `%player%` and `<player>`, with the same values, resolvers and
modifiers.
- **Which `<x>` is a placeholder**: a tag that is not a standard MiniMessage tag (of the running Adventure) and
  that no user `TagResolver` claims. Standard tags always win: a placeholder named `red` or `bold` (or `b`, `u`,
  `i`: MiniMessage's short forms) can only be written `%red%`. It is not refused at registration: `Placeholder` is
  shared with `%key%` messages, where the name is fine.
- **Modifiers** use MiniMessage's argument syntax, since `.` is not allowed in a MiniMessage tag name:
  `<price:comma>`, `<name:upper:max:16>`, `<n:plural:item:items>` (same modifiers as `%price.comma%`).
- **No value at render**: the tag is written back as text (`<player>`), exactly as MiniMessage shows an unknown
  tag, and `%player%` stays `%player%` as today.
- **Closing tags** (`</player>`) and names in other cases (`<Player>`) behave as in MiniMessage: a closing tag
  after a placeholder stays text, as after a MiniMessage `Placeholder.unparsed` (checked in stage 1; the plan first
  said it was dropped).
- **Inside a tag argument** (`<click:run_command:'/tp <player>'>`) it is a `Text slot`, like `%player%`. Plain
  MiniMessage does not do this; it is our one deliberate extension, so the equivalence suite compares those cases
  with the `%player%` form instead of MiniMessage.
- **Native or MiniMessage (4.3)**: a `<name>` placeholder never sends a message to MiniMessage by itself; it is a
  slot like `%name%`. In a MiniMessage template the marker resolver claims every name that is not a standard or
  user tag, so the same rules hold there.
- **Escaping**: `\<player>` stays literal text, as in MiniMessage.

### 4.7 Threads
Templates and `MiniMessageFlow` are immutable and thread-safe; registries copy-on-write; render scopes are per call
(or a reusable one per thread for the fast path). Folia-safe: no global lock on the render path.

## 5. Correctness

- **Equivalence suite** (`testkit`): a corpus of a few hundred messages (each native tag, nesting, unclosed tags,
  legacy mixes, placeholders everywhere, MessageFlow's example lang files). For each: our output equals
  `MiniMessage.deserialize` with the same values. Runs on every Adventure version of the matrix, against that
  version's MiniMessage.
- **Comparison through a normaliser**, not `compact()` + `equals`: a flattened tree and MiniMessage's nested
  tree can render the same and not be equal. The testkit turns both into a list of runs (text, colour,
  decorations, click, hover, insertion, font) and compares the lists.
- **Fuzzing** (jqwik): random tag soup must never throw and must equal MiniMessage, natively or not.
- **Isolation tests** for `Parsed`: unclosed tags, `<reset>` and legacy colours in resolver text do not change
  the text after it.
- MessageFlow's current tests (`AdventureFormattingTest`, `SharedFormattingTest`, `ColorConversionTest`,
  `TemplateCacheTest`, `ThreadingTest`, `PlaceholderModifiersTest`, `PlaceholderScannerTest`) move or are copied
  into the library first: they define today's behaviour. The ones that rely on colour bleed from resolver text
  are rewritten with the isolation rule.

## 6. Performance targets

JMH, same machine, one baseline per table, hardware and JDK printed with each table, run in CI on each release.

| Case | MessageFlow 1.1.x | Target |
|---|---:|---:|
| static, `Template.render` | 54 ns | < 5 ns, 0 B |
| static, `format(String)` | 54 ns | ≤ 54 ns (one cache lookup) |
| 3 placeholders + click, native | ~2 µs | < 0.5 µs |
| click argument, value changes, native | ~7-9 µs | < 1 µs |
| gradient, value changes, native | ~7.8 µs | < 2 µs |
| cold compile, native message | (MiniMessage ~12 µs) | ≤ MiniMessage |
| cold compile, MiniMessage message | (MiniMessage ~12 µs) | MiniMessage + one scan, measured and shown |
| render, MiniMessage message | as today | as today (stage 1 engine) |

Compared against `MiniMessage.deserialize`, MessageFlow 1.1.x, and FMM on Adventure 5.

## 7. Stages

Each stage is a release of the library and has an exit criterion.

0. **Repo and safety net**: new repo with `api`, `core`, `compat/adventure-4`, `compat/adventure-5`, `minimessageflow`,
   `testkit`, `benchmarks`; CI matrix; `PlaceholderScanner` and `PlaceholderModifiers` moved without change;
   MessageFlow's tests ported.
   *Exit*: ported tests green on every Adventure of the matrix; `core` compiles against each.

   **Done 2026-11-10.** Gradle 9.9.0, matrix 4.17.0 / 4.26.1 / 5.0.1 / 5.1.1 / 5.2.0: `build` green on each, 60 tests,
   `-Werror`. What differs from the text above:
   - `Placeholder`, `PlaceholderValue`, `PlaceholderModifiers` are in `api`; `PlaceholderScanner` is in `api` too
     (`fr.robie.minimessageflow.internal`, `@ApiStatus.Internal`) because `Placeholder` uses it. Changes: package,
     Bukkit `Player` → Adventure `Pointered` (`PlayerValue` → `ViewerValue`, `isPlayerDependent` →
     `isViewerDependent`), Guava `Preconditions` → plain checks.
   - There is no engine yet, so MessageFlow's formatting tests became `testkit/FormattingContract` (16 cases from
     `AdventureFormattingTest`, `SharedFormattingTest`, `TemplateCacheTest`, plus a thread test), run today against
     `ReferenceFormatter` (MessageFlow 1.1's formatting done with plain MiniMessage, from `TemplateCacheTest.OldFormat`).
     Stage 1's engine runs the same contract. Engines are compared with `ComponentRuns` (the normaliser of 5).
   - Not ported, stage 1 engine tests instead: cache internals (`changingTextsKeptApart`, identity of cached
     gradients), how many times a shared message is formatted. Not ported at all (MessageFlow's own): sending,
     `Message`/`formatLines`, custom `TextResolver`s with their own syntax (no resolver API yet), the `§`-string
     legacy output.
   - The matrix found its first break: `ClickEvent.value()` is deprecated in 4.26 and gone in 5.x (`ClickEvent<T>`).
     Tests compare whole events built with `ClickEvent.runCommand(...)` instead.
   - Not done yet: publishing (vanniktech) and `japicmp`, until there is a release to compare with.
1. **Wrap first**: the MiniMessage template (MiniMessage + markers, as MessageFlow today), compiled once and held
   by the template instead of a cache lookup per send. `Parsed` isolation lands here.
   **MessageFlow uses it behind a flag from this stage**, so the API is proven by a real plugin, not only tests.
   *Exit*: MessageFlow's suite green with the flag on; static `render` < 5 ns.

   **Done 2026-11-10.** Both exit criteria met: MessageFlow's 50 tests pass with `formatter.engine: minimessageflow`
   (1 skipped: it reads the built-in engine's private cache) and without it; static `render` 0.53 ns, 0 B.
   MiniMessageFlow: 110 tests (contract 27 cases, against the engine and the reference) on all 5 Adventure versions.
   - **API**: `MiniMessageFlow` (builder: `legacyCodes`, `resolver`, `tags`, cache sizes; `compile`, `format`,
     `clearCache`), `Template` (`render`, `isStatic`, `source`), `Resolver` (`key, viewer -> formatted text or null`,
     `Resolver.of(Placeholder)`). `MiniMessageFlow` lives in `core` (same package as `api`): the one jar exposes both.
   - **Engine** (`core/Engine`): `%key%` outside tags → slot tag `<mmf_slot_N>`; `<key>` claimed by a per-parse
     `TagResolver` when no standard or user tag has the name; both parse to a marker swapped at render. `%key%` in a tag
     → text written in at render, compiled once per text (bounded cache). Unknown `<key>` → the message compiled again
     without claiming it (one variant per set of unknown keys, at most 64 kept).
   - **Found on the way**: MiniMessage calls a `TagResolver` 2-4 times per tag, differently in 4.17 and 5.2, and
     parses hovers more than once, so counting markers can't tell whether all can be swapped (MessageFlow 1.1 counted
     `<mfph_` in the text, which `<key>` tags don't allow). The engine renders both ways once at compile, with unique
     probe values, and swaps markers only when the results are equal; else it parses with the values (cached by
     values). Plain messages, colours, clicks, `<lang>` take the fast path; hovers with a value, gradients, translatable
     arguments with a value don't.
   - **Resolver text is isolated** (the planned change): parsed on its own, cached by text, inserted like a component;
     in a tag argument only its plain text. The contract and the reference changed with it.
   - **Limits kept for later stages**: `<key>` inside a tag argument stays text, as in MiniMessage (stage 3 text
     slots); a key with whitespace or one of `< > & § \ ' " #` is never a placeholder (so the scan finds the same tokens
     whatever the values); `mmf_slot_N` is a reserved tag name; the caches evict about a quarter when full, without
     access order.
   - **The reference had MessageFlow 1.1's collision**: its `mfph_N` tags took over a `<mfph_3>` the message wrote
     itself. It uses `mmfref_N` now; the engine never had it.
   - **MessageFlow** (branch `feat/minimessageflow-engine`): `formatter.engine` setting (default `messageflow`, or the
     `messageflow.formatter.engine` system property); `TextResolver.resolvesKeys()` (true for global placeholders and
     PlaceholderAPI, asked one `%key%` at a time); other resolvers still run on the whole text first; MiniMessageFlow
     shaded and relocated to `fr.robie.messageflow.libs.minimessageflow` (bridge lookup checked in the shaded jar on
     Paper 1.21.11's Adventure); surefire runs the suite a second time with the flag on. It depends on
     `0.1.0-SNAPSHOT` from the local Maven repository: a JitPack build of that branch fails until MiniMessageFlow is
     published.

   JMH, JDK 25, this machine, short runs (`-wi 3 -i 5 -f 1`, ±10-50%):

   | Case | MiniMessageFlow | Allocated |
   |---|---:|---:|
   | static, `render()` | 0.53 ns | 0 B |
   | static, `format(String)` | 2 ns | 0 B |
   | shop line, 2 placeholders + click (`%key%` or `<key>`) | 0.95-1.0 µs | 3.9 KB |
   | `MiniMessage.deserialize`, same shop line | 4.7-5.4 µs | 16.1 KB |
   | global placeholder (isolated) | 0.87 µs | 4.0 KB |
   | click argument, same value / changing | 0.40 µs / 4.4 µs | 1.6 KB / 13.4 KB |
   | gradient, same value / changing | 29 ns / 4.8 µs | 96 B / 13.9 KB |
2. **Own scanner and compiler** for the native set without slots in arguments (a placeholder in a tag argument
   or under a gradient still sends the message to MiniMessage). Legacy codes in the scanner. Passes.
   *Exit*: equivalence suite and fuzzing green on the matrix; "3 placeholders + click" target met.

   **Done 2026-11-10.** 142 tests on all 5 Adventure versions; the contract (28 cases, 30,000 random messages) passes
   against the native compiler with its compile-time check off.
   - **`core/NativeCompiler`**: one pass over the text (tags with quoted arguments and escapes), a tree of
     elements, then passes: fold every subtree without a value into one fixed component, flatten colours and
     decorations onto values, keep click/hover/insertion on a group around values (not copied onto leaves), join
     neighbouring fixed parts. Rendering builds the component from the parts: no parse, no `compact()`.
   - **Native set**: named and hex colours (`grey` aliases), `color`/`colour`/`c`, decorations and their short forms,
     `<!x>`, `<x:false>`, `<x:true>`, `reset`, `newline`/`br`, `click` (run/suggest command, open URL, copy), `hover`
     with `show_text` (its text compiled natively too, without values), `insert`, `%key%` and `<key>`. Anything else
     (gradient, font, translatable, `<pre>`, a user tag, `<!red>`, `<#12345>`, `<red:x>`, a `<` that is not a tag
     before a later `>`...) leaves the message to the stage 1 engine.
   - **Checked at compile**: a native template is rendered with the probe values and compared with MiniMessage's
     parse of the same message (`core/Rendering`, the runs rule of `ComponentRuns`); if they differ, the message
     stays with MiniMessage. `verifyNative(false)` turns it off (tests). So a native compiler bug, or a later Adventure
     reading a tag differently, costs speed, never output.
   - **MiniMessage's rules found by probing** (same on 4.17 and 5.2): only `\<` and `\\` are escapes; a closing tag
     matches its opening tag by exact name (`</bold>` does not close `<b>`) and closes the tags opened after it; a
     closing tag that matches nothing is text; tag names are case-insensitive; `<>` and `</>` are text.
   - **New rule, in the contract**: a value written into a tag can make a standard tag (`<%color%>` → `<red>`) but
     never a `<key>` placeholder (`<%ref%>` stays text). The stage 1 engine already behaved so; the reference now does.
   - **Not done as planned**: legacy codes are still converted to tags before the scan (`LegacyCodes`), not read by
     the scanner: it only costs compile time and keeps the conversion identical for both engines. Cold compile is
     slower than MiniMessage alone, not ≤: discovery and the check each parse with MiniMessage. Both belong to the
     moment the check can be turned off by default.
   - `ClickEvent` is generic in Adventure 5: core builds a click into a `Style` and never names the type (the matrix
     failed compiling on 5.2 until then).

   JMH, JDK 25, this machine, short runs (`-wi 3 -i 5 -f 1`), same run for every row:

   | Case | Stage 2 (native) | Stage 1 (markers) | `MiniMessage.deserialize` |
   |---|---:|---:|---:|
   | 3 placeholders + click (the target, < 0.5 µs) | **148 ns**, 680 B | | |
   | shop line, 2 placeholders + `.comma` + click | 247 ns, 1.1 KB | 962 ns, 3.9 KB | 4.65 µs, 16.2 KB |
   | same with `<key>` tags | 228 ns | | |
   | global placeholder (isolated) | 156 ns, 848 B | 870 ns | |
   | click argument, same value | 166 ns, 880 B | 400 ns | |
   | static `render()` / `format(String)` | 0.52 ns / 2.9 ns, 0 B | | 1.44 µs |
   | click argument / gradient, changing value | 4.5 µs / 4.5 µs | | (stage 3) |
3. **Slots and transforms**: `Event` and `Transform` nodes (click/hover arguments, gradients over changing
   values); typed fast path.
   *Exit*: the two "value changes" targets met.

   **Done 2026-11-10** (without the typed fast path, see below). 152 tests on all 5 Adventure versions.
   - **Event arguments**: a message whose values sit in a `click` value or an `insert` argument is compiled once;
     the argument is literal pieces and values, and only the event is built at render. A value is written in as the
     stage 1 engine writes it (stripped of `< > ' " \ % & §`, legacy `#rrggbb` converted with the character before it
     as context). Stays on the stage 1 path (text compiled per value): a value touching `&`, `§`, `#`, a hex digit or
     another value; a value in a hover, a colour or gradient argument; a click with values inside a gradient. Falls
     back for one render: in an argument without quotes, a value with `:` or a `<` from a legacy colour (it would
     change how MiniMessage splits the tag); an event Adventure refuses (5.1+ checks URLs).
   - **Gradients and rainbows** (`<gradient:colors...[:phase]>`, `<rainbow[:!][phase]>`, native also without values):
     the characters are coloured at render with MiniMessage's maths, ported from `GradientTag`/`RainbowTag` (identical
     from 4.17 to 5.2; only the argument parsing order changed, and the native compiler refuses arguments where the
     two orders differ). Inside one: text, values, decorations, fixed events; a colour, another gradient or a click
     with values leaves the message to MiniMessage. A value with its own style or children falls back for that render
     (MiniMessage skips colouring it), parsed with the values and cached.
   - **Found on the way, a stage 1 bug**: on Adventure 4.17 (no virtual component) a `<rainbow>` or `<gradient>` over a
     value was given the marker form, because the probe values were as long as a marker and coloured the same. The
     compile-time checks (marker swap and native) now render with two probe sets of different lengths.
   - **Contract**: gradients and rainbows of every kind over values of 0 to 30 characters (with non-BMP code points),
     styled values under a gradient, click/insert arguments with awkward values; random pieces with gradient
     arguments. All against the reference, with the compile-time check off.
   - **Not done: the typed fast path** (`Slot<T>`, array-backed values). With values resolved by name in ~150-250 ns
     per render, the map lookups it saves are a small part; it would add a second API for every caller to learn.
     Left for when a profile on a real server asks for it.

   JMH, JDK 25, this machine, short runs (`-wi 3 -i 5 -f 1`), same run for every row:

   | Case | Stage 3 | Stage 2 | Target |
   |---|---:|---:|---:|
   | click argument, value changes every send | **342 ns**, 1.9 KB | 4.5 µs, 15 KB | < 1 µs |
   | gradient, value changes every send | **486 ns**, 2.7 KB | 4.5 µs, 14 KB | < 2 µs |
   | click argument, same value | 242 ns | 166 ns (cache hit) | |
   | gradient, same value | 504 ns | 29 ns (cache hit) | |
   | 3 placeholders + click | 138 ns | 148 ns | < 0.5 µs |
   | `MiniMessage.deserialize`, shop line | 4.67 µs | | |

   The cost of the change: a gradient or click argument whose value repeats was a cache hit and is now built each
   render (still well under the targets); a value that changes no longer fills a cache. A small cache by values in
   front of a transform would win both, if a real server shows it matters.
4. **MessageFlow on MiniMessageFlow by default** (MessageFlow 1.2.0): `AdventureMessageFormatter` becomes a thin adapter
   (sending, titles, boss bars, sounds stay in MessageFlow); each registered message is compiled on `reload()`;
   `Placeholder` kept (delegating) so plugins don't break. What MessageFlow does with its own
   `LegacyMessageFormatter` is its decision; the library gives it nothing for servers without Adventure.
   *Exit*: flag removed, changelog documents the `Parsed` isolation change.

   **Done 2026-11-10** in MessageFlow, branch `feat/minimessageflow-engine` (version number not bumped: the owner's
   release step). Both exit criteria met: `formatter.engine` and the second surefire run are gone, the 1.2.0 changelog
   states the isolation change. MessageFlow: 52 core tests and 66 common tests pass; shaded jar checked (all
   MiniMessageFlow classes relocated, bridge found).
   - `AdventureMessageFormatter` lost the 1.1 engine (markers, three Guava caches, the legacy conversion): about 450
     lines. It keeps sending, titles, boss bars and sounds, and formats through one `MiniMessageFlow`.
   - Reload: the manager gives every loaded text of every language (`MessageFormatter.precompile`) to the formatter,
     which compiles each, with and without the prefix, into a map replaced at each reload; `format` looks there
     first, then in the library's bounded cache.
   - `Placeholder` (MessageFlow's) builds its MiniMessageFlow form once, lazily; `PlaceholderModifiers.register` also
     registers with the library, so custom modifiers keep working. `common` now depends on MiniMessageFlow (shaded by
     `core`).
   - Tests that read the 1.1 engine's caches became checks of output (runs) or of the registered templates; the 1.1
     legacy conversion moved into a test helper, as the reference for `ColorConversionTest` and `TemplateCacheTest`.
   - **Blocks a release**: MiniMessageFlow is only in the local Maven repository; MessageFlow builds elsewhere (JitPack)
     fail until it is published.
   **Full benchmark, 2026-11-11** (after stage 4; JMH 1.37, JDK 25.0.3, Adventure 4.17, `-wi 5 -i 10 -f 2`; every
   number with its error is in the README). Rendering matches the stage 3 short runs within their noise (3
   placeholders + click 155 ns, shop line 245 ns vs `MiniMessage.deserialize` 7.7 µs, changing click 422 ns, changing
   gradient 556 ns, static render 1.4 ns). **Compiling had never been measured**: a message with placeholders takes
   23-28 µs, about 4× one `MiniMessage.deserialize` (6.4 µs); a static message 2.3 µs (1.8 µs). `verifyNative(false)`
   saves only 3 µs on a native message: the cost is the MiniMessage parses every compile makes (discovery of `<key>`
   placeholders, and the two probe parses that choose between the marker form and parsing with values). The plan's
   "cold compile ≤ MiniMessage" target is missed by 4×. Ways to reach it, for a later stage: discover `<key>` tags with
   the native scanner instead of a MiniMessage parse; skip the probe parses when the native compiler accepts the
   message and the check is off.

   **Done the same day: native first.** `Engine.compile` now tries the native compiler before any MiniMessage parse;
   it finds the `<key>` placeholders itself, in a copy of the slot table dropped if it gives up (a `<key>` inside a
   hover makes it give up, as before). The check parses once with probe values, twice only when a gradient or rainbow
   is coloured at render. Messages the native compiler refuses take the stage 3 path unchanged. 153 tests on all 5
   Adventure versions, check on and off. Compile (`-wi 5 -i 10 -f 2`), next to `MiniMessage.deserialize` at 5.9 µs:
   shop line 27.5 → 10.0 µs with the check, **1.5 µs without**; click argument with a value 24.0 → 5.9 µs, **1.0 µs
   without**; other tags 23.3 → 17.7 µs; static 2.3 µs (1.5 µs). The target (≤ MiniMessage) is met with
   `verifyNative(false)`; with the check, the default, a native message costs 1-2 MiniMessage parses to compile.
   Rendering unchanged (shop line 282 ns, 3 placeholders + click 133 ns).
5. **More native tags** as benchmarks ask for them, Adventure 5.x-only tags in `compat/adventure-5`, translation
   store integration (`GlobalTranslator`) for per-player languages, docs site and 1.0.0.

   **Native tags done 2026-11-11** (the owner chose this part only; Adventure 5 tags, the translation store and 1.0.0
   are left). Written from MiniMessage's own source of 4.17, 4.26 and 5.2. 156 tests on all 5 Adventure versions,
   check on and off.
   - `font` (`<font:value>`, `<font:ns:value>`; an invalid key stays with MiniMessage), `key` and `lang`/`tr`/
     `translate`, `lang_or`/`tr_or`/`translate_or`: inserting tags, so what follows them goes into the keybind or
     translatable component (a group with a base component); translation arguments compiled natively, without values.
   - `transition`: the colour computed at compile with `TransitionTag`'s maths (the negative phase reverses the colours
     and interpolates backwards). Only arguments every version reads the same way: 4.17 reads the phase first and has
     no `grey` alias, later versions read the colour first.
   - `shadow` (`#rrggbbaa`, `color[:alpha]`, `<!shadow>`): built by `AdventureBridge.shadow`, as `ShadowColor` only
     exists from 4.18. The Adventure 5 bridge builds it; the Adventure 4 bridge, compiled against 4.17, can't name the
     type and returns null, so on 4.18-4.26 shadows stay with MiniMessage (correct, slower).
   - Not native: `pride` (a gradient over a table of flags that MiniMessage changed between 4.26 and 5.2: copying it
     would mean following every release), `selector`, `score`, `nbt`, and 5.x object tags.
   - JMH (`-wi 5 -i 10 -f 2`): a message with `font`, `lang`, `key`, `transition` and 2 placeholders renders in
     213 ± 39 ns (with `selector`/`score` instead, MiniMessage's path: 727 ± 47 ns). Its compile with the check is
     15.6 µs, more than the MiniMessage path (12.5 µs): the check's MiniMessage parse reads the translatable and keybind
     too. Without the check it is a native compile.

   **"Adventure 5.x-only tags": none exist.** MiniMessage 5.2's standard tags are exactly 4.26's. The tags newer than
   the 4.17 baseline are `shadow` (done above), `sprite` and `head` (object components, Adventure 4.25+).
   **`sprite` and `head` done 2026-11-11** through `AdventureBridge.sprite`/`head`: the Adventure 5 bridge builds
   them; the Adventure 4 bridge, compiled against 4.17, can't name object components, so on 4.25-4.26 they stay with
   MiniMessage. `sprite` (`<sprite:key>`, `<sprite:atlas:key>`) reads the same in 4.26 and 5.2. `head` changed (5.2
   detects UUIDs another way and checks names; no 5.0/5.1 source was at hand), so it is native only as `<head>` or
   `<head:name>` with a plain name (1-16 of `[A-Za-z0-9_]`, not `true`/`on`/`false`/`off`, which are the hat flag);
   UUIDs, textures and the hat argument stay with MiniMessage. Both are self-closing tags that insert a component
   (a new `Insert` node); inside a gradient they stay with MiniMessage (it colours an object as one character).
   157 tests on all 5 versions. JMH: a message with a sprite, a head and 2 placeholders renders in 73 ± 8 ns on 5.2
   (native) and 479 ± 60 ns on 4.26 (MiniMessage, markers).

   **Checked against every MiniMessage tag name (4.17, 4.26, 5.2), then the gaps inside supported tags covered
   (2026-11-11).** No tag was missing; native now also:
   - decoration arguments: `false` and `off` turn off, any other argument turns on (`DecorationTag`, same in 4.17; the
     native compiler had only `true`/`false` before);
   - click actions read in lower case (`<click:RUN_COMMAND:...>`), `change_page` through `AdventureBridge`
     (Adventure 4 keeps the page as text; 5 needs an integer), `custom` (key, optional NBT) on Adventure 5;
     `show_dialog` stays with MiniMessage, which refuses it;
   - `<hover:show_item:key[:count]>` and `<hover:show_entity:type:uuid[:name]>` (name read natively), read the same in
     4.17 and 5.2; the hover action keeps its case, as in `HoverTag`; item data components and legacy NBT stay with
     MiniMessage.
   - Found: on Adventure 4.26, `ClickEvent` parses a `change_page` page as a number and MiniMessage lets the
     exception through, so `MiniMessage.deserialize("<click:change_page:x>")` throws. The engine leaves such a message
     to MiniMessage, so it throws the same way; the contract checks that the engine throws where the reference does.
   - Still not native: `pride`, `selector`, `score`, `nbt`, `show_item` with data components, `head` beyond a plain
     name, and on Adventure 4 `shadow`, `sprite`, `head`, `custom`. 160 tests on all 5 versions.

## 8. Risks
- Two engines (native + MiniMessage) can disagree: mitigated by the equivalence suite on every version, and by
  choosing the engine per message so they never mix inside one.
- Adventure API differences (4.17 vs 5.x, and later majors): kept in `compat/` behind `AdventureBridge`; `core`
  compiled against every supported version in CI.
- Gradient maths must match Adventure's exactly (rounding, phase): algorithm per bridge + tests per version.
- `Parsed` isolation changes what servers see when a PlaceholderAPI value carries a colour: migration note, and a
  warning logged once per message when resolver text ends with an open colour or tag.
- Scope creep: a tag goes native only when a benchmark shows it matters.

## 9. Answered questions (2026-11-10)
- Q1: name **MiniMessageFlow**, groupId `fr.robie.minimessageflow` (proposed, matching MessageFlow), **MIT**.
- Q2: **yes**, `<name>` placeholders too, rules in 4.6. The scanner reads both from stage 2; the stage 1
  MiniMessage template supports them through its marker resolver.
- Q3: **Gradle** (Kotlin DSL), see 3.

Nothing blocks stage 0.
