package net.harmoniya.horno;

/**
 * The switches horno reads, all of them {@code -Dhorno.<name>=true}.
 *
 * <p>They are read here rather than at each use so that the whole set is one
 * short list, and so that the deprecated spelling has exactly one home.
 */
public final class Flags {
    private Flags() {
    }

    /**
     * Never open a connection. A file that is missing, or whose sha1 does not
     * match, is an error naming the file and the hash that was expected.
     *
     * <p>The check this implies is not "the file is on disk" — the installer
     * extracts several of its libraries out of its own jar before it would
     * reach for the network, and extraction is local. So offline means "on disk
     * or inside the installer", which is why the whole list is verified up
     * front: once it passes, the installer has nothing left to fetch.
     */
    public static boolean offline() {
        return Boolean.getBoolean("horno.offline");
    }

    /**
     * Do not hash what is already on disk. Faster, and at your own risk: it
     * covers horno's own downloads only, since the installer validates its
     * libraries internally and has no switch for it.
     */
    public static boolean skipVerify() {
        return Boolean.getBoolean("horno.skipVerify");
    }

    /** Do the install half and exit, instead of handing off to the game. */
    public static boolean installOnly() {
        return Boolean.getBoolean("horno.installOnly");
    }

    /**
     * Do not trust what the processors already built; run them again.
     *
     * <p>Half the processors declare no outputs at all, so "already built" is
     * decided for those by whether the files they name exist. This is the switch
     * for when that guess is wrong — a half-written jar, or a tool that changed
     * under a version that did not.
     *
     * <p>{@code skipHashCheck} is the inherited spelling and its name lies: it
     * never skipped a hash check, it cleared the recorded outputs and forced a
     * re-run, which is this. Kept working because people pass it by hand.
     */
    public static boolean forceProcessors() {
        return Boolean.getBoolean("horno.forceProcessors")
            || Boolean.getBoolean("horno.skipHashCheck")
            || Boolean.getBoolean("forgewrapper.skipHashCheck");
    }

    /**
     * {@link #offline()} and {@link #skipVerify()} are opposites of intent —
     * one makes verification the only source of truth, the other removes it.
     * Together they mean "run whatever is lying there", which is a legitimate
     * thing to want while debugging and a terrible thing to want by accident.
     */
    public static void warnOnContradictions() {
        if (offline() && skipVerify()) {
            System.out.println("[horno] horno.offline with horno.skipVerify: nothing will be fetched and nothing will be checked");
        }
    }
}
