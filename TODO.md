# TODO

## Ten Forge 1.5 betas cannot be installed

Three different bodies were published under the name `deobfuscation_data_1.5.zip`.
Only the last survives. The other two are wanted by ten early Forge 1.5 betas
(`1.5-7.7.0.559`, `1.5-7.7.0.567` and their neighbours) and are in no archive
and no launcher's mirror — Prism and MultiMC cannot have them either, since
their mirrors are keyed by filename and one name holds one file.

    f06a8e84e627d0e3cae96443e25e888bd8865e67   7 builds
    28078aef3b7a86b467745d140c9903bf6968cfb1   3 builds

Those ten launch nowhere today. horno refuses them by name and says why
(`FmlDeobfuscation`), rather than letting FML report a dead host.

Substituting the surviving 1.5 data for them would make FML start — the hash
lives in `fmlversion.properties`, inside the archive horno already patches —
but it is mapping data from a different Forge build, and "nearly right"
deobfuscation breaks mods silently. Not worth it.

## Verified

Forge 1.1 (`1.1-1.3.4.29`) and 1.2.5 (`1.2.5-3.4.9.171`) both launch: overlay
fetched, client jar rewritten with 65 and 87 Forge entries respectively and no
`META-INF` left, LWJGL and the sound engine up, and the game writing to the
directory the launcher chose rather than to `~/.minecraft`.

Both log `FileNotFoundException: http://s3.amazonaws.com/MinecraftResources/`.
That is vanilla's pre-1.6 sound and music downloader against a bucket Mojang
retired, not Forge and not horno. The game then reads `resources/` in its own
directory, which is where opys now puts those assets.

Forge 1.5 (`1.5-7.7.0.598`) and 1.5.1 (`1.5.1-7.7.2.682`) launch too, now that
their deobfuscation data is fetched from the mirror beside the documents.
