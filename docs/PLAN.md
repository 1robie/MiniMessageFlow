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
  that no user `TagResolver` claims. Standard tags always win: a placeholder named `red` or `bold` is refused when
  it is registered (`IllegalArgumentException`) and can only be written `%red%`.
- **Modifiers** use MiniMessage's argument syntax, since `.` is not allowed in a MiniMessage tag name:
  `<price:comma>`, `<name:upper:max:16>`, `<n:plural:item:items>` (same modifiers as `%price.comma%`).
- **No value at render**: the tag is written back as text (`<player>`), exactly as MiniMessage shows an unknown
  tag, and `%player%` stays `%player%` as today.
- **Closing tags** (`</player>`) and names in other cases (`<Player>`) behave as in MiniMessage (tag names are
  case-insensitive, a closing tag of a placeholder is dropped); covered by the equivalence suite.
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
2. **Own scanner and compiler** for the native set without slots in arguments (a placeholder in a tag argument
   or under a gradient still sends the message to MiniMessage). Legacy codes in the scanner. Passes.
   *Exit*: equivalence suite and fuzzing green on the matrix; "3 placeholders + click" target met.
3. **Slots and transforms**: `Event` and `Transform` nodes (click/hover arguments, gradients over changing
   values); typed fast path.
   *Exit*: the two "value changes" targets met.
4. **MessageFlow on MiniMessageFlow by default** (MessageFlow 1.2.0): `AdventureMessageFormatter` becomes a thin adapter
   (sending, titles, boss bars, sounds stay in MessageFlow); each registered message is compiled on `reload()`;
   `Placeholder` kept (delegating) so plugins don't break. What MessageFlow does with its own
   `LegacyMessageFormatter` is its decision; the library gives it nothing for servers without Adventure.
   *Exit*: flag removed, changelog documents the `Parsed` isolation change.
5. **More native tags** as benchmarks ask for them, Adventure 5.x-only tags in `compat/adventure-5`, translation
   store integration (`GlobalTranslator`) for per-player languages, docs site and 1.0.0.

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
