//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Locale;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.foreign.SymbolLookup;
import org.eclipse.jetty.util.IO;

class NativeHelper
{
    private static final CLinker LINKER = CLinker.getInstance();
    private static final ClassLoader CLASSLOADER = NativeHelper.class.getClassLoader();
    private static final SymbolLookup LIBRARIES = lookup();
    private static final MethodHandles.Lookup MH_LOOKUP = MethodHandles.lookup();

    private static void loadNativeLibraryFromClasspath() {
        try
        {
            String libName = getNativePrefix() + "/" + System.mapLibraryName("quiche");
            File lib = extractFromResourcePath(libName, NativeHelper.class.getClassLoader());
            System.load(lib.getAbsolutePath());
            lib.deleteOnExit();
        }
        catch (IOException e)
        {
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError("Cannot find quiche native library").initCause(e);
        }
    }

    private static String getNativePrefix()
    {
        // TODO: check for macos and windows
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

        String osArch = System.getProperty("os.arch");
        switch (osArch)
        {
            case "amd64":
                osArch = "x86-64";
                break;
            // TODO: add arm64
        }

        return osName + "-" + osArch;
    }

    public static boolean isLinux()
    {
        // TODO add constant & check correctness
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }

    public static boolean isMac()
    {
        // TODO add constant & check correctness
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    public static boolean isWindows()
    {
        // TODO add constant & check correctness
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
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

    private static SymbolLookup lookup()
    {
        loadNativeLibraryFromClasspath();
        SymbolLookup loaderLookup = SymbolLookup.loaderLookup();
        SymbolLookup systemLookup = CLinker.systemLookup();
        return name -> loaderLookup.lookup(name).or(() -> systemLookup.lookup(name));
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
}
