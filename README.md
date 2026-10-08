# horno

*installs Forge and the kinda-Forges*

**horno** (ukr. *горно*) is the open hearth that heats metal *before* forging.
It runs from a plain Mojang `version.json`, performs whatever installing that
version needs, and hands off to the game.

Forge, NeoForge, and the forks that behave like them install four different
ways — processors, a LaunchWrapper tweaker, a client-jar overlay, a bare
universal zip. A launcher should not have to know which. It reads a document
that names `net.harmoniya.horno.Main` as its `mainClass`, and horno does the
rest.

Documents for every published Forge and NeoForge build live in
[harmoniya-net/metadata](https://github.com/harmoniya-net/metadata).

**Java 8 and up.** 1.7.10 and every era below it runs on 8, so horno has to —
the jar is Java 8 bytecode, with the Java 9+ half of `ModuleUtil` under
`META-INF/versions/9` where an 8 JVM never looks.

## The principle

> The document **describes** the installation. Horno **performs** it.
> Anything that exists only so horno can work is not a library.

| | |
|---|---|
| the installer jar | **not** a library — properties (path, url, sha1) |
| the ancient-era overlay zip | same |
| the processors' own libraries | horno's job |
| the vanilla client jar | **stays** a library — a real runtime dependency |

The installer used to be declared a library, which put fetching it on the
launcher at the cost of also putting it on `-cp`. There it is at best inert and
at worst fatal: `neoforge-<version>-installer.jar` becomes an automatic module
named `neoforge`, colliding with FML's own, and it is a fat jar whose shaded
Gson shadows the game's — which is how NeoForge 26.x died with
`NoSuchMethodError: JsonObject.get`. It is an input to horno, so horno fetches
it.

Second, smaller principle: **installation finishes before launch, not during
it.** Hence `horno.installOnly` and the standalone installer below.

## Properties

Every document names these; a launcher passes them through as JVM arguments and
needs to understand none of them.

| | |
|---|---|
| `horno.librariesDir` | the `libraries` folder. Detected from the loader's own jar when absent. |
| `horno.minecraft` | the vanilla client jar |
| `horno.installer` | where the loader's installer belongs |
| `horno.installerUrl`, `horno.installerSha1` | where to get it, and what it should hash to |
| `horno.mainClass` | pre-1.13 only: the class to hand off to. **Its presence selects that mode.** |
| `horno.patched` | pre-1.13 only: where the patched client jar goes |
| `horno.jarmod` | pre-1.13 only: `File.pathSeparator`-separated archives to overlay, in order. Absent means strip only. |
| `horno.jarmodUrl`, `horno.jarmodSha1` | the same list, space-separated — on Unix the path separator is `:`, which is in every URL |

## Flags

| | |
|---|---|
| `horno.offline` | never open a connection; missing or mismatched is an error naming the file and the expected hash |
| `horno.forceProcessors` | do not trust what is already built; run the processors again |
| `horno.skipVerify` | do not hash what is already on disk (fast, at your own risk) |
| `horno.installOnly` | do the install half and exit 0 |
| `horno.skipHashCheck` | deprecated synonym for `forceProcessors` |

`skipHashCheck` is inherited and its name lies. It never skipped a hash check:
`checkProcessorFiles` records the sha1s of already-built files as the
processors' expected outputs — which is what produces `Cache Hit!` — and the
flag *clears* them. Library hashes are validated unconditionally inside the
installer's own downloader and the flag never reached them. It keeps working
because people pass it by hand.

Offline is not "the file is on disk". The installer extracts several of its
libraries out of its own jar before it would reach for the network, and
extraction is local — so offline means "on disk **or** inside the installer",
and the whole list is verified up front. Once that passes, the installer has
nothing left to fetch, which is why the mode needs no `ProxySelector` or
`SecurityManager` tricks.

## Standalone

```sh
java -jar horno.jar install --document <url|path> --root <dir>
java -jar horno.jar --help
```

The same install, with nobody to launch afterwards. The only difference between
the two is who has already put the libraries on disk: in a launcher, the
launcher has; here, horno does.

It installs the loader's half and stops there — no asset index, no four
thousand asset objects, no version manifest. Every launcher already does that,
in parallel and with retries, and reaching in there would mean writing a bad
launcher instead of a good installer. What horno fetches is what the document
lists.

**Every era, including the ones horno does not launch.** A 1.6.1-1.12.2 build
names Forge's own `LaunchWrapper` as its `mainClass`, so horno is nowhere in
that launch — and installing one still has to work here, because a tool that can
only finish the job where it also starts the game is not a tool. The era is read
off the document rather than off a version number: naming a class to hand off to
means the client jar has to be rewritten, naming an installer means there are
processors to run, and naming neither means the libraries were the whole of it.

Verified across all four: 1.20.1 (processors), 1.7.10 (LaunchWrapper), 1.5.2
(strip and patch) and 1.4.7 (overlay and patch).

## Pre-1.13 (Minecraft 1.5.2 and older)

Two shapes, one operation:

- **1.5.2** ships an installer whose profile sets `stripMeta`. Forge's own
  classes are a normal library on the classpath; the client jar only needs its
  signature removed.
- **1.5.1 and older** ship a "universal" zip of loose class files meant to be
  copied into `minecraft.jar`, overwriting what was there — the original jar
  mod.

Both are the same job: overlay entries win, client entries fill in the rest,
`META-INF` is dropped. Dropping it is not tidiness — the vanilla client jar is
signed (1.5.2 carries `MOJANG_C.SF`/`.DSA`, 1.2.5 carries `CODESIGN.SF`/`.RSA`),
and a signed jar whose contents no longer match its signature fails to load with
a `SecurityException`. Hence the era's install instructions have always been
"delete the META-INF folder".

The patched jar is built once and rebuilt only when an input is newer than it.

### Where the game thinks it is

Before 1.6 there is no `--gameDir`. Minecraft works the directory out for
itself, and the only thing that overrides it is `minecraft.applet.TargetDirectory`,
the property the old applet wrapper used. Nothing sets it, so the game and FML
both fall back to the OS-standard `.minecraft` — saves, options, mods and FML's
own `lib/` land in the user's home rather than in the installation the launcher
just built. It looks like it works, right up until two instances share one world
folder.

Horno sets it to the working directory, which is the one thing every launcher
already sets per instance. A document cannot say this: `${game_directory}` is
substituted in game arguments, not reliably in JVM ones.

### FML's own libraries

FML 4.x and 5.x fetch a handful of jars into `<game dir>/lib` on first run,
from `files.minecraftforge.net/fmllibs` — gone for years, so the whole era
stops at *"Please try launching again"* against a host that will never answer.
Nine files across every build that asks for any; which ones a build wants is
recorded in the `CoreFMLLibraries` class it ships, and nowhere else.

Horno reads that list out of the archive it is already opening, places the
files, and FML then finds them and never opens a connection.

The document says nothing about any of this, and deliberately: the names and
hashes are Forge's own, and where those nine files live now is a closed
question about a closed era — 568 published builds, no more coming, and eight
of the nine on maven, where an artifact cannot be altered or withdrawn. A
document is for what varies. Keeping it in horno is also what lets `horno
install` set up a 1.4 build from a document that never heard of any of it.

The ninth file was published only by Forge and exists on no maven under any
name, so horno asks for a different artifact carrying the same classes and
**patches FML's embedded list** to match — FML validates the sha1 of what it
finds, so it has to be told. The patch changes string constants and nothing
else, and is refused outright if the list is not the shape it was read as: half
a patched list fails later, and as a network error rather than as itself.

### Nothing is required of the launcher

The patched jar has to be reached *instead of* the vanilla one, not as well as
it — and a launcher that honours `inheritsFrom` always puts the inherited client
jar on `-cp`, with no way for the Mojang format to ask it not to. So horno does
not negotiate: it builds a `URLClassLoader` holding the patched jar first, the
rest of `-cp` after it, and hands off inside that.

Order is what makes this correct. The patched jar is a superset of the vanilla
one, so nothing can resolve past it — which also covers the copy a launcher may
keep under a name of its own, such as `versions/<id>/<id>.jar`, that no check
could recognise.

That is also what LaunchWrapper expects. Its `Launch` casts its own loader to a
`URLClassLoader` to read the sources for `LaunchClassLoader` — a cast that fails
outright against the Java 9+ application loader.

## 1.13+

The installer jar is a **zip**, never a classpath entry. Horno reads
`install_profile.json` out of it and runs the processors itself.

That is a deliberate reversal. Horno used to load the installer's classes and
call into them — `DownloadUtils.downloadLibrary`, `PostProcessors`, a private
`outputs` field reached with `setAccessible` — which meant compiling against one
installer version and running against seven years of them. Three of those five
pins had already bent: the downloader's signature forked between the families,
`PostProcessors.process` changed return type, and the whole thing was held
together by finding methods by shape. Every one of them announced itself as a
`NoSuchMethodError` in front of a user.

The install profile is a published format and the processors are ordinary jars
with a `Main-Class`, so there is nothing there worth borrowing. What horno
*does not* reimplement is the work: `jarsplitter`, `ForgeAutoRenamingTool`,
`binarypatcher` and `installertools` are Forge's and NeoForge's own, and horno
drives them. It owns the driver, not the transformation.

Keeping the jar off the classpath is also what makes its contents permanently
irrelevant — including the shaded Gson that shadowed the game's and killed
NeoForge 26.x.

### What the driver does

1. Read the profile (specs 0 and 1 — 1.13.2-1.16.5 and 1.17+, one language) and the
   version JSON it points at.
2. Put the tools on disk: what is already correct is left alone, then the
   installer's own embedded `maven/` tree, then the network. That middle step is
   why `horno.offline` is not simply "the file is on disk".
3. Resolve the data map. `[coord]` is a file under `libraries/`, `/data/x` is
   unpacked from the installer, and anything else is a literal — a sha1 is
   written `'de86…'` precisely so it cannot be mistaken for a token.
4. For each client-side processor, substitute `{TOKEN}`s into its arguments,
   load its `Main-Class` in a child loader, and call `main`. Then check what it
   wrote against the sha1s the profile declared.

A processor that declares `outputs` says for itself whether it can be skipped.
Half of them declare none — including the binary patcher, the expensive one —
and for those the only available evidence is that every file the profile
addresses by coordinate already exists. It is coarse and all-or-nothing on
purpose: those processors say nothing about what they produce, so one missing
file is no evidence about which of them is stale.

The profile's server half is read and ignored. `MC_UNPACKED` is written by a
server-side processor and never exists in a client install, which is exactly the
trap in judging "already built" from the whole data map.

## JDK internals

On Java 9 and later horno reaches into the JDK for things nothing promises to
keep: adding modules, exports and opens to a JVM that has already started,
putting the libraries the processors produced *first* on the class path
(Forge for Minecraft 26.1 needs them there), and settling the `http` / `https`
URL handlers before any provider is asked (Forge for Minecraft 1.20.2 fails
its first launch otherwise). The last two fail soft — horno prints which
version is affected and carries on — so nothing else would say that a new JDK
had moved a field.

`jdk-probe/run.sh <horno jar>` asks the JDK on `PATH` (or `JAVA_HOME`) the
same questions those builds do and exits non-zero on a wrong answer. CI runs
it against the built jar on every LTS a modular loader uses and on the newest
release.

## Custom detection

Horno provides an [`IFileDetector`](src/main/java/net/harmoniya/horno/detector/IFileDetector.java)
interface. Implement it, ship a jar containing
`META-INF/services/net.harmoniya.horno.detector.IFileDetector` naming the
implementation, and put that jar on the classpath.

## Credits

Horno began as a fork of
[ZekerZhayard/ForgeWrapper](https://github.com/ZekerZhayard/ForgeWrapper), by
way of [PrismLauncher's](https://github.com/PrismLauncher/ForgeWrapper). The
module surgery in `ModuleUtil` is still his work almost line for line. MIT, and
the original copyright stays in `LICENSE`.
