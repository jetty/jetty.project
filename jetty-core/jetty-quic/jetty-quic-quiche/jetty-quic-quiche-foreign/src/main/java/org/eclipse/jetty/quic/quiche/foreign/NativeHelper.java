//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.quiche.foreign;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.util.IO;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class NativeHelper
{
    public static final ValueLayout.OfBoolean C_BOOL = ValueLayout.JAVA_BOOLEAN;
    public static final ValueLayout.OfByte C_CHAR = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfShort C_SHORT = ValueLayout.JAVA_SHORT;
    public static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong C_LONG_LONG = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfFloat C_FLOAT = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfDouble C_DOUBLE = ValueLayout.JAVA_DOUBLE;
    public static final AddressLayout C_POINTER = ValueLayout.ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(java.lang.Long.MAX_VALUE, JAVA_BYTE));
    public static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG;

    private static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.loaderLookup().or(Linker.nativeLinker().defaultLookup());
    private static final Platform PLATFORM;

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
        {
            throw new UnsatisfiedLinkError("Unsupported OS: " + osName);
        }
        loadNativeLibraryFromClasspath(prefix);
    }

    private static void loadNativeLibraryFromClasspath(String prefix)
    {
        try
        {
            String libName = prefix + "/" + System.mapLibraryName("quiche");
            Path lib = extractFromResourcePath(libName, NativeHelper.class.getClassLoader());
            System.load(lib.toAbsolutePath().toString());
            lib.toFile().deleteOnExit();
        }
        catch (Throwable x)
        {
            throw (UnsatisfiedLinkError)new UnsatisfiedLinkError("Cannot find quiche native library for architecture " + prefix).initCause(x);
        }
    }

    private static Path extractFromResourcePath(String libName, ClassLoader classLoader) throws IOException
    {
        Path target = Path.of(System.getProperty("java.io.tmpdir")).resolve(libName);
        Files.createDirectories(target.getParent());
        try (InputStream is = classLoader.getResourceAsStream(libName);
             OutputStream os = Files.newOutputStream(target))
        {
            IO.copy(is, os);
        }
        return target;
    }

    public static MethodHandle downcallHandle(String symbol, FunctionDescriptor fdesc)
    {
        return Linker.nativeLinker().downcallHandle(
            SYMBOL_LOOKUP.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("unresolved symbol: " + symbol)),
            fdesc);
    }

    public static <T> MemorySegment upcallMemorySegment(Class<T> clazz, String methodName, T instance, FunctionDescriptor fdesc, Arena scope)
    {
        try
        {
            MethodHandle handle = MethodHandles.lookup().findVirtual(clazz, methodName, fdesc.toMethodType());
            handle = handle.bindTo(instance);
            return Linker.nativeLinker().upcallStub(handle, fdesc, scope);
        }
        catch (ReflectiveOperationException ex)
        {
            throw new AssertionError(ex);
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
