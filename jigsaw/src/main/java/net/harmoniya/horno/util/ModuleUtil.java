package net.harmoniya.horno.util;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import sun.misc.Unsafe;

public class ModuleUtil {
    private final static MethodHandles.Lookup IMPL_LOOKUP = getImplLookup();

    private static MethodHandles.Lookup getImplLookup() {
        try {
            // Get theUnsafe
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);

            // Get IMPL_LOOKUP
            Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            return (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(implLookupField), unsafe.staticFieldOffset(implLookupField));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * add module-path at runtime
     */
    @SuppressWarnings("unchecked")
    public static void addModules(String modulePath) throws Throwable {
        // Find all extra modules, exclude all existing modules
        ModuleFinder finder = ModuleFinder.of(Stream.of(modulePath.split(File.pathSeparator)).map(Paths::get).filter(p -> ModuleFinder.of(p).findAll().stream().noneMatch(mref -> ModuleLayer.boot().findModule(mref.descriptor().name()).isPresent())).toArray(Path[]::new));
        MethodHandle loadModuleMH = IMPL_LOOKUP.findVirtual(Class.forName("jdk.internal.loader.BuiltinClassLoader"), "loadModule", MethodType.methodType(void.class, ModuleReference.class));

        // Resolve modules to a new config and load all extra modules in system class loader (unnamed modules for now)
        List<String> roots = new ArrayList<>();
        for (ModuleReference mref : finder.findAll()) {
            String name = mref.descriptor().name();
            if (!ModuleLayer.boot().findModule(name).isPresent()) {
                loadModuleMH.invokeWithArguments(ClassLoader.getSystemClassLoader(), mref);
                roots.add(name);
            }
        }
        Configuration config = Configuration.resolveAndBind(finder, List.of(ModuleLayer.boot().configuration()), finder, roots);

        // Copy the new config graph to boot module layer config
        MethodHandle graphGetter = IMPL_LOOKUP.findGetter(Configuration.class, "graph", Map.class);
        HashMap<ResolvedModule, Set<ResolvedModule>> graphMap = new HashMap<>((Map<ResolvedModule, Set<ResolvedModule>>) graphGetter.invokeWithArguments(config));
        MethodHandle cfSetter = IMPL_LOOKUP.findSetter(ResolvedModule.class, "cf", Configuration.class);
        // Reset all extra resolved modules config to boot module layer config
        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> entry : graphMap.entrySet()) {
            cfSetter.invokeWithArguments(entry.getKey(), ModuleLayer.boot().configuration());
            for (ResolvedModule resolvedModule : entry.getValue()) {
                cfSetter.invokeWithArguments(resolvedModule, ModuleLayer.boot().configuration());
            }
        }
        graphMap.putAll((Map<ResolvedModule, Set<ResolvedModule>>) graphGetter.invokeWithArguments(ModuleLayer.boot().configuration()));
        IMPL_LOOKUP.findSetter(Configuration.class, "graph", Map.class).invokeWithArguments(ModuleLayer.boot().configuration(), new HashMap<>(graphMap));

        // Reset boot module layer resolved modules as new config resolved modules to prepare define modules
        Set<ResolvedModule> oldBootModules = ModuleLayer.boot().configuration().modules();
        MethodHandle modulesSetter = IMPL_LOOKUP.findSetter(Configuration.class, "modules", Set.class);
        HashSet<ResolvedModule> modulesSet = new HashSet<>(config.modules());
        modulesSetter.invokeWithArguments(ModuleLayer.boot().configuration(), new HashSet<>(modulesSet));

        // Prepare to add all the new config "nameToModule" to boot module layer config
        MethodHandle nameToModuleGetter = IMPL_LOOKUP.findGetter(Configuration.class, "nameToModule", Map.class);
        HashMap<String, ResolvedModule> nameToModuleMap = new HashMap<>((Map<String, ResolvedModule>) nameToModuleGetter.invokeWithArguments(ModuleLayer.boot().configuration()));
        nameToModuleMap.putAll((Map<String, ResolvedModule>) nameToModuleGetter.invokeWithArguments(config));
        IMPL_LOOKUP.findSetter(Configuration.class, "nameToModule", Map.class).invokeWithArguments(ModuleLayer.boot().configuration(), new HashMap<>(nameToModuleMap));

        // Define all extra modules and add all the new config "nameToModule" to boot module layer config
        ((Map<String, Module>) IMPL_LOOKUP.findGetter(ModuleLayer.class, "nameToModule", Map.class).invokeWithArguments(ModuleLayer.boot())).putAll((Map<String, Module>) IMPL_LOOKUP.findStatic(Module.class, "defineModules", MethodType.methodType(Map.class, Configuration.class, Function.class, ModuleLayer.class)).invokeWithArguments(ModuleLayer.boot().configuration(), (Function<String, ClassLoader>) name -> ClassLoader.getSystemClassLoader(), ModuleLayer.boot()));

        // Add all of resolved modules
        modulesSet.addAll(oldBootModules);
        modulesSetter.invokeWithArguments(ModuleLayer.boot().configuration(), new HashSet<>(modulesSet));

        // Reset cache of boot module layer
        IMPL_LOOKUP.findSetter(ModuleLayer.class, "modules", Set.class).invokeWithArguments(ModuleLayer.boot(), null);
        IMPL_LOOKUP.findSetter(ModuleLayer.class, "servicesCatalog", Class.forName("jdk.internal.module.ServicesCatalog")).invokeWithArguments(ModuleLayer.boot(), null);

        // Add reads from extra modules to jdk modules
        MethodHandle implAddReadsMH = IMPL_LOOKUP.findVirtual(Module.class, "implAddReads", MethodType.methodType(void.class, Module.class));
        for (ResolvedModule resolvedModule : config.modules()) {
            Module module = ModuleLayer.boot().findModule(resolvedModule.name()).orElse(null);
            if (module != null) {
                for (ResolvedModule bootResolvedModule : oldBootModules) {
                    Module bootModule = ModuleLayer.boot().findModule(bootResolvedModule.name()).orElse(null);
                    if (bootModule != null) {
                        implAddReadsMH.invokeWithArguments(module, bootModule);
                    }
                }
            }
        }
    }

    public static void addExports(List<String> exports) {
        TypeToAdd.EXPORTS.implAdd(exports);
    }

    public static void addOpens(List<String> opens) {
        TypeToAdd.OPENS.implAdd(opens);
    }

    public static ClassLoader getPlatformClassLoader() {
        return ClassLoader.getPlatformClassLoader();
    }

    private enum TypeToAdd {
        EXPORTS("Exports"),
        OPENS("Opens");

        private final MethodHandle implAddMH;
        private final MethodHandle implAddToAllUnnamedMH;

        TypeToAdd(String name) {
            try {
                this.implAddMH = IMPL_LOOKUP.findVirtual(Module.class, "implAdd" + name, MethodType.methodType(void.class, String.class, Module.class));
                this.implAddToAllUnnamedMH = IMPL_LOOKUP.findVirtual(Module.class, "implAdd" + name + "ToAllUnnamed", MethodType.methodType(void.class, String.class));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        void implAdd(List<String> extras) {
            for (String extra : extras) {
                ParserData data = ModuleUtil.parseModuleExtra(extra).orElse(null);
                if (data != null) {
                    Module module = ModuleLayer.boot().findModule(data.module).orElse(null);
                    if (module != null) {
                        try {
                            if ("ALL-UNNAMED".equals(data.target)) {
                                this.implAddToAllUnnamedMH.invokeWithArguments(module, data.packages);
                            } else {
                                Module targetModule = ModuleLayer.boot().findModule(data.target).orElse(null);
                                if (targetModule != null) {
                                    this.implAddMH.invokeWithArguments(module, data.packages, targetModule);
                                }
                            }
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                    }
                }
            }
        }
    }

    // <module>/<package>=<target>
    private static Optional<ParserData> parseModuleExtra(String extra) {
        String[] all = extra.split("=", 2);
        if (all.length < 2) {
            return Optional.empty();
        }

        String[] source = all[0].split("/", 2);
        if (source.length < 2) {
            return Optional.empty();
        }
        return Optional.of(new ParserData(source[0], source[1], all[1]));
    }

    private static class ParserData {
        final String module;
        final String packages;
        final String target;

        ParserData(String module, String packages, String target) {
            this.module = module;
            this.packages = packages;
            this.target = target;
        }
    }

    /**
     * Put what the processors produced on the class path, ahead of everything
     * already there.
     *
     * <p>Ahead, because that is where the installer's own document lists them:
     * the patched client is its second library, and the vanilla jar comes last.
     * These files did not exist when the JVM started, so they could not be on
     * {@code -cp}, and appending them puts the vanilla jar first instead. Forge
     * for Minecraft 26.1 finds "the Minecraft jar" by asking the class loader
     * who has {@code net/minecraft/client/Minecraft.class}. Since 26.1 ships
     * unobfuscated that is true of the vanilla jar too, the first answer wins,
     * and Forge then crashes transforming classes nobody patched ("Field fluid
     * is not private"). Before 26.1 the vanilla names were obfuscated and only
     * the patched jar matched; from 26.1.1 Forge looks for a marker file.
     *
     * <p>{@code URLClassPath} can only append, so the front is reached through
     * its fields. If a JDK has moved them the jars are appended as before,
     * which is right for every build but those.
     */
    public static void setupClassPath(Path libraryDir, List<String> paths) throws Throwable {
        Class<?> urlClassPathClass = Class.forName("jdk.internal.loader.URLClassPath");
        Object ucp = IMPL_LOOKUP.findGetter(Class.forName("jdk.internal.loader.BuiltinClassLoader"), "ucp", urlClassPathClass).invokeWithArguments(ClassLoader.getSystemClassLoader());
        List<URL> urls = new ArrayList<>();
        for (String path : paths) {
            urls.add(libraryDir.resolve(path).toUri().toURL());
        }
        try {
            prepend(urlClassPathClass, ucp, urls);
        } catch (Throwable t) {
            System.err.println("[horno] Java " + Runtime.version() + " keeps its class path somewhere horno does not know, so the"
                + " libraries the installer produced go last on it instead of first. Forge for Minecraft 26.1 crashes on start"
                + " like this (\"Field fluid is not private\"); every other version is unaffected. If this is that one, use an"
                + " older Java, and either way please report it: https://github.com/harmoniya-net/horno/issues\n[horno]   " + t);
            MethodHandle addURLMH = IMPL_LOOKUP.findVirtual(urlClassPathClass, "addURL", MethodType.methodType(void.class, URL.class));
            for (URL url : urls) {
                addURLMH.invokeWithArguments(ucp, url);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void prepend(Class<?> urlClassPathClass, Object ucp, List<URL> urls) throws Throwable {
        Class<?> loaderClass = Class.forName("jdk.internal.loader.URLClassPath$Loader");
        List<URL> path = (List<URL>) IMPL_LOOKUP.findGetter(urlClassPathClass, "path", ArrayList.class).invoke(ucp);
        List<Object> loaders = (List<Object>) IMPL_LOOKUP.findGetter(urlClassPathClass, "loaders", ArrayList.class).invoke(ucp);
        Map<String, Object> opened = (Map<String, Object>) IMPL_LOOKUP.findGetter(urlClassPathClass, "lmap", HashMap.class).invoke(ucp);
        MethodHandle getLoader = IMPL_LOOKUP.findVirtual(urlClassPathClass, "getLoader", MethodType.methodType(loaderClass, URL.class));
        MethodHandle key = IMPL_LOOKUP.findStatic(Class.forName("sun.net.util.URLUtil"), "urlNoFragString", MethodType.methodType(String.class, URL.class));

        // Everything is resolved before anything is changed, so a failure
        // leaves the class path as it was for the caller to append to.
        List<URL> fresh = new ArrayList<>();
        List<Object> freshLoaders = new ArrayList<>();
        synchronized (ucp) {
            for (URL url : urls) {
                if (path.contains(url) || fresh.contains(url)) {
                    continue;
                }
                fresh.add(url);
                freshLoaders.add(getLoader.invoke(ucp, url));
            }
            for (int i = 0; i < fresh.size(); i++) {
                loaders.add(i, freshLoaders.get(i));
                path.add(i, fresh.get(i));
                opened.put((String) key.invoke(fresh.get(i)), freshLoaders.get(i));
            }
        }
    }

    // ForgeWrapper need some extra settings to invoke BootstrapLauncher.
    public static Class<?> setupBootstrapLauncher(Class<?> mainClass) throws Throwable {
        if (!mainClass.getModule().isOpen(mainClass.getPackageName(), ModuleUtil.class.getModule())) {
            TypeToAdd.OPENS.implAddMH.invokeWithArguments(mainClass.getModule(), mainClass.getPackageName(), ModuleUtil.class.getModule());
        }
        return mainClass;
    }

    /**
     * Settle which handler serves {@code http:} and {@code https:} before any
     * URL is built: the JDK's own, which is the one it would have picked.
     *
     * <p>The first URL of a protocol makes the JVM look for a
     * {@code URLStreamHandlerProvider} through the system class loader, and
     * finding one means loading its class. Forge 1.20.2's {@code securemodules}
     * registers one, in a package that {@link #addModules} is about to define
     * as a module. Once a class of it has been loaded off {@code -cp} the
     * package belongs to the unnamed module, the module cannot be defined, and
     * the launch dies with "Module net.minecraftforge.bootstrap not known to
     * this layer". It only happened on a first launch, because only then does
     * horno fetch the installer before the modules are set up.
     *
     * <p>Horno cannot simply avoid building such a URL. Opening a TLS
     * connection is enough: {@code javax.crypto.JceSecurity} builds
     * {@code http://null.oracle.com/} in its static initialiser. So the answer
     * is put where the lookup would have cached it. A provider is still asked
     * about every other protocol, which is all one is for.
     */
    @SuppressWarnings("unchecked")
    public static void claimUrlHandlers() {
        try {
            Map<String, URLStreamHandler> handlers = (Map<String, URLStreamHandler>) IMPL_LOOKUP.findStaticGetter(URL.class, "handlers", java.util.Hashtable.class).invoke();
            for (String protocol : new String[] {"http", "https"}) {
                Class<?> handler = Class.forName("sun.net.www.protocol." + protocol + ".Handler");
                handlers.putIfAbsent(protocol, (URLStreamHandler) IMPL_LOOKUP.findConstructor(handler, MethodType.methodType(void.class)).invoke());
            }
        } catch (Throwable t) {
            // A JDK that keeps this elsewhere. Every loader but that one build of
            // Forge launches without it, so it is not worth refusing to start over.
            System.err.println("[horno] Java " + Runtime.version() + " keeps its URL handlers somewhere horno does not know. Forge for"
                + " Minecraft 1.20.2 fails its first launch like this (\"Module net.minecraftforge.bootstrap not known to this"
                + " layer\") and starts on the second; every other version is unaffected. Please report it:"
                + " https://github.com/harmoniya-net/horno/issues\n[horno]   " + t);
        }
    }
}
