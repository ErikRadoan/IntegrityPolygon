package dev.erikradovan.integritypolygon.core;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Isolated classloader for each module JAR. Uses parent-first delegation for
 * framework API classes and Velocity classes (so modules share the same types),
 * and child-first for everything else (module isolation).
 */
public class ModuleClassLoader extends URLClassLoader {

    private static final String[] PARENT_FIRST_PACKAGES = {
            "dev.erikradovan.integritypolygon.api.",
            "com.velocitypowered.",
            "net.kyori.",
            "org.slf4j.",
            "com.google.gson."
    };

    public ModuleClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // For framework/Velocity packages, always delegate to parent first
        for (String pkg : PARENT_FIRST_PACKAGES) {
            if (name.startsWith(pkg)) {
                return super.loadClass(name, resolve);
            }
        }

        // For everything else, try child first (module isolation)
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            try {
                loaded = findClass(name);
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            } catch (ClassNotFoundException e) {
                // Fall back to parent
                return super.loadClass(name, resolve);
            }
        }
    }
}

