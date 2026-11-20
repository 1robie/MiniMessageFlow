# Changelog

## 1.5.0

- Paper: `PlaceholderApi.Builder.cache(Duration)`: what PlaceholderAPI gives is kept per player and placeholder for
  that long (a time window: the same placeholder for the same player is asked once per window, however many renders
  show it; an unknown placeholder is kept as unknown). With `mainThreadOnly`, a value kept for the window is still
  given off the owning thread. `cacheSize(int)` (10,000 by default) and `cacheFactory(CacheFactory)` (a bounded map
  by default; `GuavaCacheFactory`...), with the new `CacheFactory.Kind.PLACEHOLDER_VALUES`. Off by default.

- `Template.style(Style)` and `TemplateList.style(Style)` / `style(line -> style)`: each render gets a style on its
  root, set over the root's own (the text inside keeps its styles): an item's name and lore not italic. While the
  template gives the same render (values unchanged, `rememberRenders`), the styled one is the same instance too, per
  `Values`.
- `builder.placeholdersInFormattedValues(true)`: the `%key%` placeholders written inside a formatted value
  (`setFormatted`, `registerFormatted`, a formatted lookup) are read at each render through the resolvers, for the
  render's viewer, then the value is parsed: a configuration's `"%vault_eco_balance%$"` shows the balance. Plain
  values, `raw` and resolvers' own text are never read; one level, modifiers not read there; only values holding a
  `%` pay for it. Off by default.

- Changes after build: `MiniMessageFlow.tag(name, Tag)`, `tags(TagResolver)`, `resolver(...)`, `valueTag(...)`,
  `constant(...)` and their `remove...` add to an instance and remove from it at any time, thread-safe. Every
  template it compiled, before or after (`compile`, `format`, `compileAll`, `compileText`, the translator's), uses
  them from its next render: it compiles its message again once. Several changes in a row cost one rebuild, made
  on the next use. A tag cannot take a value tag's name, nor a value tag a tag's.
- `MiniMessageFlow.numberStyle(NumberStyle)`: the number style changed on the instance, for a configuration read
  again, without making a new one. Its templates use it from their next render, and the `Values` it made write a
  `double` in it from their next `set`; a text already set keeps its separators until set again.
- `compileList(List<String>)` → `TemplateList`: lines compiled once (lore, a help page), rendered as a
  `List<Component>`, each line split at its breaks as `renderLines` does; `send(audience, values)` sends one chat
  message per line. `placeholders()` of every line, `isStatic()`.
- `Values` by name: `set("page", 3)`, `setFormatted`, `unset`, `isSet` declare a key the first time they see it,
  for names known only at runtime; a render still reads it by its place (by its name for a template compiled
  before). `get(name)` reads a name's text through the layers and lookups; `merge(other)` copies another layer;
  `flatten()`; `copy()` makes a root holding everything a layer sees, lookups included, for another thread.
- `formatText(text, values, viewer)` and `formatLines(lines, values, viewer)`: `compileText` and lines through a
  cache, as `format` does (`CacheFactory.Kind.TEXT_TEMPLATES`, new).
- `builder.legacyPrefixModifiers(true)`: `%upper_x%`, `%lower_x%`, `%capitalize_x%` (first letter only),
  `%add_one_x%`, `%remove_one_x%`, read when compiled; a value for the prefixed name itself wins.
- `builder.failedModifier(FailedModifier.EMPTY)`: a value whose modifiers don't apply gives the empty text instead
  of the placeholder as written. A placeholder without a value stays as written either way.
- Paper: `GuavaCacheFactory`, caches made with the Guava every Paper server has: bounded by size, least recently
  used dropped first, optionally `expireAfterAccess(Duration)`, or `expireAfterAccess(Supplier<Duration>)` read again each time a cache is
  cleared, for an expiry from a configuration reloaded. Opt-in (`cacheFactory(GuavaCacheFactory.create())`): a cache
  read costs about 30 ns and 24 B more than the default's, which stays `PaperFlow`'s.
- README: a "Which one to use" table, how to add to an instance after build, scopes, and a section for code coming
  from a string placeholder parser.

## 1.4.0

- `raw` modifier: `%nick.raw%`, `<nick:raw>`, `<papi:x:raw>`, `%nick.max:16.raw%` place the value as plain text,
  never read as tags or colour codes, whatever its source (a resolver's text, a value tag, formatted text, a
  constant); a component gives its plain text. For text a player may have typed, where the plugin can't tell. Read
  when the message is compiled: a render pays nothing for it. `raw` is reserved: registering a modifier of that name
  fails. Elsewhere than last in a chain it changes nothing.
- Formatted values: `Values.setFormatted(key, text)`, `Placeholder.Builder.registerFormatted(key, text)`,
  `PlaceholderValue.ofFormatted(text)`. Read as a resolver's text (tags and colour codes, on its own: its colours end
  with it), through the instance's value cache, so the same text is parsed once. A value of the call stays plain text.
- `Values` layers: `values.child()` reads its own values first, then its parent's (`parent()`, `root()`); one array
  read per layer. `toPlaceholder()` copies every layer, for another thread.
- Keys after build: `MiniMessageFlow.key(name)` (and `ValueKeys.add`, `builder.key` after `build()`) declares a key
  later, thread-safe; `Values` made before grow when it is set. A message compiled before the key reads it by name.
- `builder.keys(ValueKeys)` and `MiniMessageFlow.keys()`: an instance built on another's keys accepts its
  `ValueKey`s and `Values`, so a plugin that rebuilds its instance on reload keeps the `Values` it made. Call
  `keys(...)` before any `builder.key(name)`: called after, it throws `IllegalStateException`.
- Prefix lookups: `values.lookup(prefix, rest -> text)` and `lookupFormatted` give the value of any key starting with
  the prefix that has no value (`%state_count%`), asked at each render, modifiers applied, through layers.
- `MiniMessageFlow.compileText(text)` → `TextTemplate`: a command, an amount, a URL with `%key%` placeholders, rendered
  as a `String` (no tag or colour code read, `<key>` left as text, every value placed as written, modifiers applied).
  Compiled once, rendered into one builder sized when compiled.
- `Template.renderLines(...)`: the render split at each `\n` of the text and the values (`<newline>` included), each
  line keeping the style open where it starts (colour, decorations, font, click, hover, insertion); a hover's text
  and a translation are not split. Without a break, the render itself in a list of one. For item lore.
- `Values.rememberRenders(true)`: the values remember the last render of each template, so a render whose values are
  all the same gives the same component instance again, allocating nothing (measured: under 1 B per render over
  10,000, a formatted value and a click argument included). The template's own memory is shared by every viewer, and
  values that differ from one viewer to the next keep replacing it; this one is per `Values` (one per player's open
  menu). `forgetRenders()` drops it.
- Fixed: a resolver's text found in the value cache allocated a lambda at each render (16 B).
- Faster renders with `Values`: a plain value set in the values' own layer is read on a short path. 3 placeholders
  from `Values` (`ValuesBenchmark.threeValuesRender`): 6.1 ns, 1.3.0 10.6 ns; 0 B.

- New artifact `paper-minimessageflow`, for Paper 1.20.6 to 26: `PaperFlow.builder(plugin)` sets up an instance
  with PlaceholderAPI when it is installed (`<papi:x>`, `%papi:x%`, and `%x%` for a registered expansion's key;
  modifiers apply), the viewer's and the server's placeholders (`%viewer_name%`, `<server_online>`... add yours
  with `PaperPlaceholders`), and errors logged to the plugin once per placeholder. `PaperMessages` compiles the
  messages of a YAML file and reloads them. Compiled against Paper 1.20.6; CI compiles and tests it against 1.21.11
  and 26.3 too. With a new player at each render (`PaperBenchmark`, players faked): `<papi:x:comma>` 75 ns and
  232 B (a plain resolver: 57 ns, 232 B), `<viewer_name>` with `<server_online>` 84 ns and 345 B (63 ns, 263 B).
- `paper-minimessageflow`, more: `PaperTranslations` (only when loaded) registers `lang/*.yml` in Adventure's
  `GlobalTranslator`, a file per locale, with `reload()` and `close()`; MiniPlaceholders 2.x and 3.x, found when the
  server runs (`<player_name>`, `%player_name%`), asked directly on Adventure 4 (47 ns and 217 B per value, a plain
  resolver 39 ns and 184 B; the first version parsed each: 639 ns and 2,185 B, still the case on Paper 26);
  `<viewer_item>`, `<viewer_item_display>`, `<viewer_target>`, `<viewer_vehicle>` (an item's name through
  `effectiveName()` from Paper 1.21, its meta on 1.20.6); Folia: a placeholder reading the player's world is only read
  on the player's region thread, and `mainThreadOnly` means the thread that owns the player. Measured: the region
  check costs nothing on Paper (`<viewer_name>` with `<server_online>` still 83 ns and 345 B).
- Fixed, found on a real Folia 1.21.11 and 26.3 server: `server_tps` and `server_mspt` off a region's thread (Folia has
  no tick time there) logged an error; they are shown as written, quietly. A smoke test on real Paper and Folia
  servers 1.20.6, 1.21.11 and 26.3 (`platform/paper-smoke`, see CONTRIBUTING.md) checks the Paper module as a plugin
  ships it.
- Note: an installed placeholder plugin answers before the built-in placeholders: MiniPlaceholders 2.x has its own
  `server_mspt` (written `0`, the built-in `0.00`).
- `AdventureBridge.insertedTag`: a tag resolver asked directly, without a parse, on Adventure 4.
- Changed: the JitPack names. The library is `com.github.1robie.MiniMessageFlow:MiniMessageFlow:<version>`, the Paper
  integration `com.github.1robie.MiniMessageFlow:paper-minimessageflow:<version>`. The old
  `com.github.1robie:MiniMessageFlow` still works, but brings both: switch to the new name.
- The value tags added one after another are one resolver, which finds a key's tag with one lookup: 14 of them cost
  what one does.

- `builder.valueTag(name, ValueTag)`: a tag computed at each render, for the viewer (`<papi:player_name>`,
  `<online>`). It is the placeholder `name:args`, so `<papi:x>` and `%papi:x%` are the same, modifiers apply
  (`<papi:x:comma>`), a message using it keeps the fast path, and it is asked once per render, never while compiling.
  A name a standard tag or a `tags(...)` tag claims is refused. A value for the viewer, a new viewer at each render
  (`TagBenchmark`, JMH, 1 s iterations): `<papi:player_name>` as a value tag 45 ns and 200 B, as a resolver's
  `%player_name%` 43 ns and 200 B. A plain `TagResolver` tag there is resolved once when compiled, so it shows the
  same value to every viewer. Renders without value tags are unchanged: the same bytes per render, the same time
  within the error bars (`RenderBenchmark`, `TemplateBenchmark`).
- Changed: `builder.tags(TagResolver)` adds to the tags given before instead of replacing them (the last one added
  wins a name both claim). `builder.tag(name, Tag)` adds one tag.
- Documented: a `TagResolver` tag is a tag of the message: it may be resolved once when the message is compiled, and
  is not given the viewer. For a value that changes, use `valueTag`.

- `Placeholder.parse(message, viewer, ModifierSet)`: reads modifiers with a set, so `parse` can give the text a
  `MiniMessageFlow` renders (its own modifiers, its number style). `parse(message)` and `parse(message, viewer)` use
  the shared modifiers in the `AUTO` style, as before, and now say so.
- `Placeholder.toString()` lists the keys and the kind of each value (`Placeholder{name=text, rank=viewer}`), never
  the values, so a log does not show a player's data.
- `Placeholder.of(...)` refuses a null key when it is given (`NullPointerException`), as the builder does; it failed
  at the next lookup, during a render.
- A `Placeholder` of more than 8 values holds an immutable copy of its map: one handed to another thread without a
  lock reads every value.
- In `parse`, a value that throws is asked once, not at each of its tokens.
- `isViewerDependent` no longer wraps text values to read them.
- The class documentation's `Placeholder.of("time", ...)` example compiles.
- A null supplier, function or component is refused when the value is made (`NullPointerException`), by
  `PlaceholderValue.of...`, `Placeholder.of(...)` and the builder; it failed at a render.
- In `Placeholder.parse`, a modifier on a component value reads its plain text, as in a render: `%item.length%`
  counted the `§` codes, `%item.max:3%` could cut one. The value alone (`%item%`) still gives its legacy text.
- A component's legacy text keeps its hex colours (`§x§f§f§8§8§0§0`, Bukkit's form); they became the nearest of the
  16 named colours.
- Documented: the `PlaceholderValue.of...` factories are public; without a viewer, a viewer value is empty text.
- `builder.key(name)` refuses a name no placeholder can have (whitespace, `% < > & § \ ' " #`): its value never
  reached a message. The rule is `PlaceholderScanner.isKey`, the one the engine reads tokens with.
- `Values.set(key, double)`: a number with decimals, written in full without an exponent (`2.5`, `0.000001`), with
  the decimal point of the instance's `NumberStyle` (`2,5` in `EUROPEAN`), so its number modifiers read it.
  `ValueKeys.newValues(NumberStyle)` makes values for a style; `MiniMessageFlow.newValues()` uses its own.
- `Values.toString()` lists the keys set and the kind of each value, never the values.
- Documented: `set(key, null)` needs a cast (`(String) null`), and `unset` removes a value.

## 1.2.1

- A value in a tag argument without quotes is one argument: a `:` in it no longer starts another.
  `<click:run_command:/tp %p%>` with `a:b` ran `/tp a`; it runs `/tp a:b`. Such an argument is read as if written
  in quotes (`'/tp %p%'`). In a tag's name, or in an argument with quotes or a backslash of its own, the value loses
  its `:` as it loses its quotes.
- `MiniMessageFlowTranslator`: the default locale falls back as a player's locale does, so a default of `en_US`
  finds a template registered for `en`. The less specific locales of each locale asked for are kept for 256
  locales at most (they were kept for every locale ever asked for, and a client chooses its own).
- A `</reset>` is dropped, as MiniMessage drops it; the native compiler kept it as text (with `verifyNative`, the
  message went the MiniMessage way).
- The text of a `<hover:show_text:...>` without values is compacted, as MiniMessage's is.
- `NumberStyle`, set with `MiniMessageFlow.builder().numberStyle(...)` or `ModifierSet.Builder.numberStyle(...)`: how
  the number modifiers read a value's separators and write theirs. `AUTO` (the default) reads `1,234` as 1234 and
  `1,5` as 1.5 and writes `1,234.50`; `ENGLISH` reads and writes `1,234.50`; `EUROPEAN` reads `1.234,50` or
  `1 234,50` and writes `1.234,50`. A plugin can let its users choose it in its configuration.
- Changed: every `,` was read as a decimal point, so `1,234` (what `comma` writes) was 1.234: `%v.comma.add:1%`
  gave `2.234`. With `AUTO` it is 1234. For the old reading of `1,234`, use `EUROPEAN`.
- Changed: a number is digits, a sign, separators and an exponent only. `NaN`, `Infinity`, `2f`, `10d` and hex
  were read as numbers (`isnumber` said `true`); they are not.
- A number past what a `long` holds made `int`, `round`, `floor`, `ceil` and `comma` give `9223372036854775807`
  (`Infinity.comma` too) and `NaN.int` gave `0`: `int`, `round`, `floor`, `ceil` and `even` fail on it (the
  placeholder is kept), and `comma` writes it in full.
- `max`, `length`, `pad` and `capitalize` count characters as shown: `max:1` no longer cuts an emoji in two.
- A render with a `<key>` that has no value keeps the unknown slots as bits (a `long`; an array past 64 slots), not
  a `HashSet` of boxed numbers copied for each render, and looks up the form for them with that key. The shop line
  with a `<missing>` at its end (JMH, 1 s iterations): 151 ns and 573 B to 110 ns and 269 B. Renders where every
  key has a value are unchanged.

## 1.2.0

- `Values` holds computed values: `setComputed(key, supplier)` (asked at each render), `setForViewer(key, function)`
  and `set(key, PlaceholderValue)` for the other kinds (a component computed at each render or for the viewer).
- `MiniMessageFlow.format(String, Values)` and `format(String, Values, Pointered)`.
- `Placeholder.Builder.register(key, PlaceholderValue)`.
- A number modifier (`comma`, `int`, `2f`, `add`, `plural`...) on a whole number written with digits only (`1250`,
  `-3`, up to 15 digits) reads it without a `double`, and `comma` writes numbers under a million as one string.
  The results are the same. `comma` on `1250`: 31 ns and 176 B to 11 ns and 48 B; the shop line with
  `%price.comma%`: 88 ns and 488 B to 69 ns and 360 B (a loop on JDK 21, not JMH).
- A click, hover or insertion over one value and nothing else (`<click:run_command:'/buy'>%item%`) is on the
  value's own component, not on an empty component around it; so is one whose argument or hover text holds values.
  It shows the same. The shop line ending in `<click:run_command:'/buy 42'>%n%`: 648 B to 368 B per render.
- A natively compiled message of one part (`<green>%item%`) is rendered without a list: 128 B to 48 B, the text
  component alone.
- A placeholder without a value shows as written (`%key%`) from a text made when the message is compiled, not at
  each render: a line with one such placeholder allocates as much as with every value set.
- A `Placeholder` of up to 8 values keeps them in one array, read in order, instead of a `HashMap`; `getMap()`
  makes the map the first time it is asked. Building three values: 328 B to 160 B; a lookup no longer hashes the
  key.
- A template gives its last component again when a render's values are the same (the same text, the same
  component instance): a message sent to many players, a line drawn again with nothing changed. Each value is still
  asked for (suppliers, resolvers) at every render. Rendered components are immutable, so sharing one is safe. The
  shop line with the same values (JMH): 89 ns and 360 B to 34 ns and 72 B, 193 to 58 ns on 8 threads. To keep values
  that change at each render cheap, a render is kept after about one miss in eight: with values changing at every
  render, 1-3 % slower on one thread and 5-8 % on 8 threads, 3 B more.
- A render whose values are the last render's allocates nothing: the values are compared as they are read, and the
  arrays are only made at the first that differs. The shop line with the same values: 72 B to 48 B (what `comma`
  writes); three values and a click: 32 B to 0 B.
- Text and values of one style that are all of their message, or all that a click, hover or insertion is over, are
  one component of the text joined when the values are text (`<green>Welcome %name%!`,
  `<click:run_command:'/tp %name%'>[tp %name%]`), instead of a component around a component per piece. A
  component value renders as before. It shows the same. With values changing at each render (JMH, `RenderBenchmark`):
  `<green>Welcome %name%!` 72 ns and 286 B to 32 ns and 105 B; the click line 114 ns and 585 B to 64 ns and 293 B;
  three values and a click 172 ns and 600 B to 131 ns and 411 B.
- A message starting with text without a style (`Hello <red>%name%`) renders that text as the parent of the rest,
  instead of an empty component around all.
- A component a render builds over children (a message's parts, a click or hover over several, a gradient's
  characters, MiniMessage's tree with its values) is made with Adventure's text component constructor, given the
  render's own array-backed immutable list, instead of `children(list)`, which copies it (twice on 4.x, once on
  5.x). 5.x keeps the list as it is; 4.x copies it once. The constructor is package-private: it is used when
  Adventure's package is open to the library (Adventure on the class path, as on Paper), after a check against the
  API when the class loads; otherwise, or with `-Dminimessageflow.directComponents=false`, the API is used. The
  tests run both ways (`:core:testApiComponents`). With values changing at each render (JMH): five values each under
  a colour 206 ns and 466 B to 143 ns and 364 B on 4.17, 133 ns and 474 B to 74 ns and 262 B on 5.2; three values
  and a click 131 to 104 ns and 411 to 325 B on 4.17, 102 to 62 ns and 403 to 238 B on 5.2; a gradient 247 to
  168 ns and 624 to 498 B on 4.17, 154 to 78 ns and 643 to 373 B on 5.2.
- A `Placeholder` keeps a text value as its `String`, not wrapped in a `PlaceholderValue`: `get(key)` wraps it when
  asked, and the engine reads it as it is (`raw(key)`, internal). One value: 72 B to 56 B; two: 96 B to 64 B; three
  values built and rendered: 33 ns and 160 B to 31 ns and 112 B.
- A translation with values in its arguments (`<lang:key:'%name%'>`) is made once with its style and arguments,
  instead of made, then given its style, then its children, each copying the arguments again: 114 ns and 505 B to
  78 ns and 387 B on 4.17, 141 ns and 576 B to 84 ns and 387 B on 5.2.
- The text of a hover or a tag argument with values is joined at its exact length.
- Renders of one template on several threads wait less for each other. The last render a template keeps, and the
  styled copy of a component value it remembers, are on cache lines of their own, away from what every render reads.
  A render is kept after about one miss in 8 while kept renders are given again, one in 64 while they are not
  (values that change at each send), so such values seldom write a field every thread reads; a component value is
  noted about one render in 8. On 8 threads (JMH, `SharedRenderBenchmark`, values changing at each render):
  `<green>Welcome %name%!` 96 to 54 ns, three values and a click with a component value 391 to 300 ns, a gradient
  255 to 193 ns, the same values each time 35 to 27 ns. One thread: the same or faster, about 10 B less per render.
- `SharedRenderBenchmark`: renders on templates shared by the threads (`-Pjmh.threads=8`).
- `FastMiniMessageBenchmark` compares renders with values that change at each send and values set for each send
  (`-PadventureVersion=5.2.0 -PfastMiniMessageVersion=2.0.0-beta.2`), and checks both give the same text first. On
  5.2: three values and a click 54 ns and 231 B here, 103 ns and 608 B on FastMiniMessage 2.0.0-beta.2; five values
  in five colours 58 ns and 246 B, 166 ns and 1,240 B; a gradient 69 ns and 362 B, 224 ns and 1,192 B.
- `-Pjmh.jvmArgs=...` passes JVM arguments to the benchmark forks.
- `RenderBenchmark`: renders with values that change at each send (16 sets made before the run), so a render is
  measured rather than the last render given again; `...Send` adds the JSON serialisation.

## 1.1.0

- The parts of a natively compiled message that never change are compacted when it is compiled: the component a
  render gives has fewer components in it (a static line of three coloured words and a bold one: 12 to 8), for the
  same render cost.
- A component value that comes again at the same place of a message (a resolver's text, which is parsed once and
  kept; a component the caller keeps) is put under the style around it once, not at each render.
  `<gray>[%server%] <white>%name%` with `%server%` from a resolver, on Adventure 4.17: 150 ns and 824 B to 72 ns
  and 288 B.
- `Template.fallbackReason()`: for a `MARKERS` or `PARSED` message, why it is not compiled natively: the tag left
  to MiniMessage (`the tag <selector>`), that the native result differed from MiniMessage's, or that the native
  compiler is off. Null for a `STATIC` or `NATIVE` message.
- `constant(key, text)` and `constant(key, component)` on the builder: a value that never changes for the instance
  (`%prefix%`, `%server%`), formatted once on its own as a resolver's text is. A message whose only placeholders
  are constants is rendered when it is compiled and is `STATIC` (every render returns the same component, 0 B);
  in other messages a constant is read without a lookup. A value given to a render for a constant's key is ignored.
- A natively compiled message made of one part is that part, without an empty component around it.
- A message MiniMessage parsed with markers (a tag the native compiler leaves to it: `<selector>`, `<score>`, a
  user tag...) is prepared once: a render copies only the components above a value and no longer walks the whole
  tree or compacts it. `<selector:@p> %item% <score:steve:kills> %price%`: 684 ns and 2.1 KB to 138 ns and 0.8 KB.
  The component shows the same but is no longer compacted with its values in: a value is its own component next
  to the text around it (`Hi %name%` is three components, as a natively compiled message gives, where it was one).
- A render builds less besides its result: a text value goes straight into its component (and into a click's or
  a hover's argument) instead of through a component of its own, the component a group or a gradient puts its
  content in is built when the message is compiled, and a click with a value is set on the style around it
  instead of merged into it. A click with a value in its command: 377 ns and 1.6 KB to 108 ns and 0.8 KB; three
  values and a click: 162 ns and 680 B to 129 ns and 608 B.
- A gradient or rainbow over a value keeps, for each length it has coloured (up to 63 characters), the colour of
  each position and the finished characters of the text before the first value and after the last: a render only
  builds the characters of the values. `<gradient:red:blue>Welcome %name%!`: 532 ns and 2.4 KB to 180 ns and 0.7 KB.
- `Values`: the values of a render kept in an array and set again for each send, instead of a `Placeholder` built
  each time. Declare the names once (`ValueKey PLAYER = builder.key("player")`), make one `Values` per thread
  (`text.newValues()`), then `values.set(PLAYER, name)` and `template.render(values, viewer)`. It works with every
  template of the instance; a key that is not set goes to the resolvers, as a key missing from a `Placeholder`.
  Not thread-safe. Three values and a click, set and rendered: 115 ns and 680 B, against 207 ns and 1,400 B with a
  `Placeholder` built for the send.
- Building a `Placeholder` costs about a third of what it did (three values with the builder: 64 ns and 728 B to
  20 ns and 328 B). `build()` no longer copies the builder's map (the builder copies it if it is used again), and
  `Placeholder.of(k1, v1, k2, v2)` no longer goes through a `HashMap`.
- A value in a hover's text (`<hover:show_text:'Balance: %coins%'>`) or in a translation argument
  (`<lang:key:'%player%'>`) is placed at render: such a message is `NATIVE` instead of `PARSED`, and a value never
  seen before no longer costs a MiniMessage parse (a hover with a changing value: 22.7 µs and 67 KB to 0.22 µs and
  1.6 KB). A value with a `#`, or a `:` in an argument without quotes, still goes through MiniMessage for that
  render, as does a value under a gradient there.
- Modifiers are read once, when a message is compiled: a render with `%price.comma%` no longer splits and looks
  the chain up each time (the shop line renders about three times faster and allocates less than half).
  A shared modifier registered with `PlaceholderModifiers.register` after a message is compiled is not seen by
  that message: register before compiling, as the Javadoc asks. A `Modifier` must not change its `args` array,
  which is now the same on every call. `PlaceholderModifiers.compile(chain)` and `ModifierSet.compile(chain)`
  give a `Chain` to apply to many values. `comma` on a whole number no longer goes through `String.format`.
- Legacy codes are no longer read inside a `<click>` or `<insert>` tag: a URL keeps its `&b=2` and `#abcdef`, a
  copied `#ff0000` stays as written. A hover's text is still formatted.
- A `<` that opens no tag (`I <3 you`, `5 < 6`) no longer changes the placeholders after it: their values keep every
  character and their style, and the message is compiled once instead of once per value.
- A key is evaluated once per render, wherever it is written (text, `<key>`, tag arguments).
- `errorHandler` on the builder: a resolver or a value that throws is no longer silent. By default it is logged
  once per key (`System.getLogger("MiniMessageFlow")`). `Resolver.of(Placeholder)` lets a failing value through
  to it.
- `modifier(name, modifier)` on the builder: modifiers of one instance (`ModifierSet`), known before any message is
  compiled. `PlaceholderModifiers.register` stays, shared by every instance.
- `bareHexColors(false)` on the builder: a `#rrggbb` without `&` is text.
- `Template.placeholders()`, `Template.kind()`, `Template.renderPlain(...)`; `MiniMessageFlow.compileAll(Map)` and
  `formatPlain(...)`.
- `Placeholder.of(Map)` and `Placeholder.Builder.registerAll(Map)`. Removed: `Placeholder.PLACEHOLDER_PATTERN`, a
  regular expression nothing used.
- `MiniMessageFlowTranslator` falls back like resource bundles: `zh_Hant_TW` to `zh_Hant`, `zh_TW`, `zh`, then a
  template registered for `Locale.ROOT`, then the default locale.
- Tags are found exactly as MiniMessage's tokenizer finds them, checked against it on every Adventure of the matrix
  (`TagRangesTest`). Each bridge has its own tests. JitPack runs the tests before publishing.

- Values computed at each render or for the viewer can be components: `Placeholder.ofComponent(key, supplier)`,
  `Placeholder.ofViewerComponent(key, function)`, `registerComponent`, `registerViewerComponent` on the builder
  (`PlaceholderValue.DynamicComponentValue`, `ViewerComponentValue`; `PlaceholderValue.asComponent`, `isComponent`,
  `needsViewer`).
- `Resolver.resolveComponent(key, viewer)`: a resolver can give a component, asked before its text.
  `Resolver.of(Placeholder)` gives component values as they are instead of writing and parsing them again; with a
  modifier (`%key.upper%`) such a value is its plain text, as for a value of the call.

## 1.0.0

First release. A fast text formatter for Adventure 4.17 (Paper 1.20.6) to 5.x (Minecraft 26), with no dependency
besides Adventure. Tested on Paper 1.20.6, 1.21 and 26 through MessageFlow 1.2.

- **`MiniMessageFlow`**: `compile(String)` gives an immutable, thread-safe `Template`; `format(...)` compiles and
  caches. Builder: `resolver`, `tags`, `legacyCodes`, `templateCacheSize`, `valueCacheSize`, `cacheFactory`,
  `nativeCompiler`, `verifyNative`.
- **Placeholders** written `%name%` or `<name>`, with modifiers (`%price.comma%`, `<price:comma>`,
  `<n:plural:item:items>`...). Values of a call are plain text or components, never read as tags. Values a call doesn't
  give come from `Resolver`s, formatted on their own (their colours and unclosed tags end with them).
- **Native compiler**: messages made of colours, decorations (with arguments), `reset`, `newline`, `click` (every
  action), `hover` (`show_text`, `show_item`, `show_entity`), `insert`, `font`, `key`, `lang`/`tr`/`translate` (and
  `_or`), `gradient`, `rainbow`, `transition` and placeholders, and on Adventure 5 `shadow`, `sprite`, `head`, are
  compiled without MiniMessage and checked against it once; others go through MiniMessage with markers.
- **`MiniMessageFlowTranslator`** for Adventure's `GlobalTranslator`: templates per key and locale, arguments
  `<arg:i>`/`%i%`.
- **`cache.Cache` / `cache.CacheFactory`** to supply your own caches (Guava, Caffeine...).
- One jar (`minimessageflow`) with a bridge per Adventure major, found by package so it can be relocated.

Speed (JMH, JDK 25): a message without placeholders renders in 1.4 ns; 3 placeholders and a click in 155 ns; a shop
line in 245 ns where `MiniMessage.deserialize` takes 7.7 µs. Compiling a native message takes 10 µs (1.5 µs with
`verifyNative(false)`). Every number is in the README.
