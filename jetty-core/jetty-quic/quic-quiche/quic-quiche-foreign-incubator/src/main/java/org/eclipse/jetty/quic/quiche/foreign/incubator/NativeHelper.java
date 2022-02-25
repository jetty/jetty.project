//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.quiche.foreign.incubator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.foreign.SymbolLookup;
import org.eclipse.jetty.util.IO;

class NativeHelper
{
    private static final Platform PLATFORM;
    private static final CLinker LINKER;
    private static final ClassLoader CLASSLOADER;
    private static final SymbolLookup LIBRARIES;
    private static final MethodHandles.Lookup MH_LOOKUP;

    static
    {
        String arch = System.getProperty("os.arch");
        if ("x86_64".equals(arch) || "amd64".equals(arch))
            arch = "x86-64";

        String osName = System.getProperty("os.name");
        String prefix;
        if (osName.startsWith("Linux"))
        {
            prefix = "linux-" + arch;
            PLATFORM = Platform.LINUX;
        }
        else if (osName.startsWith("Mac") || osName.startsWith("Darwin"))
        {
            prefix = "darwin-" + arch;
            PLATFORM = Platform.MAC;
        }
        else if (osName.startsWith("Windows"))
        {
            prefix = "win32-" + arch;
            PLATFORM = Platform.WINDOWS;
        }
        else
            throw new UnsatisfiedLinkError("Unsupported OS: " + osName);

        LINKER = CLinker.getInstance();
        CLASSLOADER = NativeHelper.class.getClassLoader();
        LIBRARIES = lookup(prefix);
        MH_LOOKUP = MethodHandles.lookup();
    }

    private static SymbolLookup lookup(String prefix)
    {
        loadNativeLibraryFromClasspath(prefix);
        SymbolLookup loaderLookup = SymbolLookup.loaderLookup();
        SymbolLookup systemLookup = CLinker.systemLookup();
        return name -> loaderLookup.lookup(name).or(() -> systemLookup.lookup(name));
    }

    private static void loadNativeLibraryFromClasspath(String prefix) {
        try
        {
            String libName = prefix + "/" + System.mapLibraryName("quiche");
            File lib = extractFromResourcePath(libName, NativeHelper.class.getClassLoader());
            System.load(lib.getAbsolutePath());
            lib.deleteOnExit();
        }
        catch (Throwable x)
        {
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError("cannot find quiche native library").initCause(x);
        }
    }

    private static File extractFromResourcePath(String libName, ClassLoader classLoader) throws IOException
    {
        File target = new File(System.getProperty("java.io.tmpdir"), libName);
        target.getParentFile().mkdirs();
        try (InputStream is = classLoader.getResourceAsStream(libName); OutputStream os = new FileOutputStream(target))
        {
            IO.copy(is, os);
        }
        return target;
    }


    static MethodHandle downcallHandle(String name, String desc, FunctionDescriptor fdesc)
    {
        return LIBRARIES.lookup(name)
            .map(addr ->
            {
                MethodType mt = MethodType.fromMethodDescriptorString(desc, CLASSLOADER);
                return LINKER.downcallHandle(addr, mt, fdesc);
            })
            .orElseThrow(() ->
            {
                throw new UnsatisfiedLinkError("unresolved symbol: " + name);
            });
    }

    static <T> MemoryAddress upcallHandle(Class<T> clazz, T t, String name, String desc, FunctionDescriptor fdesc, ResourceScope scope)
    {
        try
        {
            MethodHandle handle = MH_LOOKUP.findVirtual(clazz, name, MethodType.fromMethodDescriptorString(desc, CLASSLOADER));
            handle = handle.bindTo(t);
            return LINKER.upcallStub(handle, fdesc, scope);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError("unresolved symbol: " + name).initCause(e);
        }
    }

    public static boolean isLinux()
    {
        return PLATFORM == Platform.LINUX;
    }

    public static boolean isMac()
    {
        return PLATFORM == Platform.MAC;
    }

    public static boolean isWindows()
    {
        return PLATFORM == Platform.WINDOWS;
    }

    private enum Platform
    {
        LINUX, MAC, WINDOWS
    }
}
