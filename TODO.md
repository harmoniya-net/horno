# TODO

## Forge 1.5 and 1.5.1 do not install

104 published builds. Everything else in the pre-1.13 range works; this is the
one hole, and it is a known amount of work rather than an open question.

### What is missing

FML 5 added a second list of files to fetch, and horno only reads the first.

- **The first list** is `cpw/mods/fml/relauncher/CoreFMLLibraries`, two parallel
  `String[]`s of filenames and sha1s. `FmlLibraryList` reads it and
  `FmlLibraries` says where each file lives now. This part works.
- **The second** is one file, `deobfuscation_data_<mc>.zip`, and it is not in
  any `ILibrarySet`. `cpw/mods/fml/relauncher/FMLInjectionData` builds the name
  from `"deobfuscation_data_" + mcVersion + ".zip"` and takes its sha1 from
  `fmlversion.properties` in the same archive, key
  `fmlbuild.deobfuscation.hash` (the Minecraft version is
  `fmlbuild.mcversion`). Nothing in the class files spells the whole filename,
  which is why a search for the literal finds nothing.

1.4.7 and older have no such mechanism — `FMLInjectionData` there carries no
deobfuscation keys at all. 1.5.2 needs no `lib/` whatsoever: it is the first
build with a real installer, and it takes its libraries from the document.

### What to do

1. Read `fmlversion.properties` out of the overlay archive — horno already
   opens it to patch `CoreFMLLibraries`. Parse `fmlbuild.mcversion` and
   `fmlbuild.deobfuscation.hash`; absent means this build predates the
   mechanism and there is nothing more to place.
2. Add the two files to `FmlLibraries`. They are keyed by name there, and the
   name is shared, so this one needs a source keyed by **sha1**:

   | sha1 | builds | file |
   |---|---|---|
   | `22e221a0d89516c1f721d6cab056a7e37471d0a6` | 68 | `deobfuscation_data_1.5.1.zip` |
   | `5f7c142d53776f16304c0bbe10542014abad6af8` | 26 | `deobfuscation_data_1.5.zip` |

3. Place them in `<game dir>/lib` beside the rest. No patch to FML's list is
   involved: unlike `asm-all-4.0.jar`, these keep their own name and hash.

### Where the files come from

They have to be mirrored — this is the case where there is no alternative.
Forge's own host is gone, no maven artifact exists or could (this is generated
mapping data, not a library), and no substitute carries the same content the
way `asm-debug-all` carries ASM's. Both survive in the Internet Archive:

    https://web.archive.org/web/2016id_/http://files.minecraftforge.net/fmllibs/deobfuscation_data_1.5.1.zip
    https://web.archive.org/web/2014id_/http://files.minecraftforge.net/fmllibs/deobfuscation_data_1.5.zip

Around 400 KB together. Put them under `static/fmllibs/` in
harmoniya-net/metadata, served by the same Pages site as the documents, and
point `FmlLibraries` at them. Mirroring these is not unusual: it is the only
way anybody serves this era. `files.prismlauncher.org/fmllibs/` and
`files.multimc.org/fmllibs/` exist for exactly this reason, and by the official
route — download the universal zip from Forge, follow their instructions — the
era does not start at all today, because `files.minecraftforge.net/fmllibs/`
answers 404 to everything.

### The ten that cannot be fixed

Three different bodies were published under the name `deobfuscation_data_1.5.zip`.
Only the last survives. The other two are wanted by ten early Forge 1.5 betas
(`1.5-7.7.0.559`, `1.5-7.7.0.567` and their neighbours) and are in no archive
and no launcher's mirror — Prism and MultiMC cannot have them either, since
their mirrors are keyed by filename and one name holds one file.

    f06a8e84e627d0e3cae96443e25e888bd8865e67   7 builds
    28078aef3b7a86b467745d140c9903bf6968cfb1   3 builds

Those ten launch nowhere today. When the file is missing, say which one and
why, rather than letting FML report a dead host.

Substituting the surviving 1.5 data for them would make FML start — the hash
lives in `fmlversion.properties`, inside the archive horno already patches —
but it is mapping data from a different Forge build, and "nearly right"
deobfuscation breaks mods silently. Not worth it.

## Verified since

Forge 1.1 (`1.1-1.3.4.29`) and 1.2.5 (`1.2.5-3.4.9.171`) both launch: overlay
fetched, client jar rewritten with 65 and 87 Forge entries respectively and no
`META-INF` left, LWJGL and the sound engine up, and the game writing to the
directory the launcher chose rather than to `~/.minecraft`.

Both log `FileNotFoundException: http://s3.amazonaws.com/MinecraftResources/`.
That is vanilla's pre-1.6 sound and music downloader against a bucket Mojang
retired, not Forge and not horno — the same era gap as the missing `resources/`
asset mapper on the opys side. The game runs; it has no sounds.

## Release plumbing nobody has watched work

- **The dispatch into `metadata` is untested.** `METADATA_DISPATCH_TOKEN` was
  set after `0.1.0` shipped, and the step only runs on a tag, so the first real
  check is the next release. If it fails the release still stands; the nightly
  run picks the new jar up, or `gh workflow run pages.yml -R harmoniya-net/metadata`.
- **`actions/setup-java@v4` is deprecated** in both workflows; move to `v5`.
- **`fuckforge.harmoniya.net` still resolves.** Nothing of ours names it any
  more. Drop the DNS record, or decide what it should point at.
