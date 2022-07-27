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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.invoke.MethodType.methodType;

/**
 * TYPE Utilities.
 * Provides various static utility methods for manipulating types and their
 * string representations.
 *
 * @since Jetty 4.1
 */
public class TypeUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(TypeUtil.class);
    public static final Class<?>[] NO_ARGS = new Class[]{};
    public static final int CR = '\r';
    public static final int LF = '\n';

    private static final HashMap<String, Class<?>> name2Class = new HashMap<>();

    static
    {
        name2Class.put("boolean", java.lang.Boolean.TYPE);
        name2Class.put("byte", java.lang.Byte.TYPE);
        name2Class.put("char", java.lang.Character.TYPE);
        name2Class.put("double", java.lang.Double.TYPE);
        name2Class.put("float", java.lang.Float.TYPE);
        name2Class.put("int", java.lang.Integer.TYPE);
        name2Class.put("long", java.lang.Long.TYPE);
        name2Class.put("short", java.lang.Short.TYPE);
        name2Class.put("void", java.lang.Void.TYPE);

        name2Class.put("java.lang.Boolean.TYPE", java.lang.Boolean.TYPE);
        name2Class.put("java.lang.Byte.TYPE", java.lang.Byte.TYPE);
        name2Class.put("java.lang.Character.TYPE", java.lang.Character.TYPE);
        name2Class.put("java.lang.Double.TYPE", java.lang.Double.TYPE);
        name2Class.put("java.lang.Float.TYPE", java.lang.Float.TYPE);
        name2Class.put("java.lang.Integer.TYPE", java.lang.Integer.TYPE);
        name2Class.put("java.lang.Long.TYPE", java.lang.Long.TYPE);
        name2Class.put("java.lang.Short.TYPE", java.lang.Short.TYPE);
        name2Class.put("java.lang.Void.TYPE", java.lang.Void.TYPE);

        name2Class.put("java.lang.Boolean", java.lang.Boolean.class);
        name2Class.put("java.lang.Byte", java.lang.Byte.class);
        name2Class.put("java.lang.Character", java.lang.Character.class);
        name2Class.put("java.lang.Double", java.lang.Double.class);
        name2Class.put("java.lang.Float", java.lang.Float.class);
        name2Class.put("java.lang.Integer", java.lang.Integer.class);
        name2Class.put("java.lang.Long", java.lang.Long.class);
        name2Class.put("java.lang.Short", java.lang.Short.class);

        name2Class.put("Boolean", java.lang.Boolean.class);
        name2Class.put("Byte", java.lang.Byte.class);
        name2Class.put("Character", java.lang.Character.class);
        name2Class.put("Double", java.lang.Double.class);
        name2Class.put("Float", java.lang.Float.class);
        name2Class.put("Integer", java.lang.Integer.class);
        name2Class.put("Long", java.lang.Long.class);
        name2Class.put("Short", java.lang.Short.class);

        name2Class.put(null, java.lang.Void.TYPE);
        name2Class.put("string", java.lang.String.class);
        name2Class.put("String", java.lang.String.class);
        name2Class.put("java.lang.String", java.lang.String.class);
    }

    private static final HashMap<Class<?>, String> class2Name = new HashMap<>();

    static
    {
        class2Name.put(java.lang.Boolean.TYPE, "boolean");
        class2Name.put(java.lang.Byte.TYPE, "byte");
        class2Name.put(java.lang.Character.TYPE, "char");
        class2Name.put(java.lang.Double.TYPE, "double");
        class2Name.put(java.lang.Float.TYPE, "float");
        class2Name.put(java.lang.Integer.TYPE, "int");
        class2Name.put(java.lang.Long.TYPE, "long");
        class2Name.put(java.lang.Short.TYPE, "short");
        class2Name.put(java.lang.Void.TYPE, "void");

        class2Name.put(java.lang.Boolean.class, "java.lang.Boolean");
        class2Name.put(java.lang.Byte.class, "java.lang.Byte");
        class2Name.put(java.lang.Character.class, "java.lang.Character");
        class2Name.put(java.lang.Double.class, "java.lang.Double");
        class2Name.put(java.lang.Float.class, "java.lang.Float");
        class2Name.put(java.lang.Integer.class, "java.lang.Integer");
        class2Name.put(java.lang.Long.class, "java.lang.Long");
        class2Name.put(java.lang.Short.class, "java.lang.Short");

        class2Name.put(null, "void");
        class2Name.put(java.lang.String.class, "java.lang.String");
    }

    private static final HashMap<Class<?>, Method> class2Value = new HashMap<>();

    static
    {
        try
        {
            Class<?>[] s = {java.lang.String.class};

            class2Value.put(java.lang.Boolean.TYPE,
                java.lang.Boolean.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Byte.TYPE,
                java.lang.Byte.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Double.TYPE,
                java.lang.Double.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Float.TYPE,
                java.lang.Float.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Integer.TYPE,
                java.lang.Integer.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Long.TYPE,
                java.lang.Long.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Short.TYPE,
                java.lang.Short.class.getMethod("valueOf", s));

            class2Value.put(java.lang.Boolean.class,
                java.lang.Boolean.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Byte.class,
                java.lang.Byte.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Double.class,
                java.lang.Double.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Float.class,
                java.lang.Float.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Integer.class,
                java.lang.Integer.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Long.class,
                java.lang.Long.class.getMethod("valueOf", s));
            class2Value.put(java.lang.Short.class,
                java.lang.Short.class.getMethod("valueOf", s));
        }
        catch (Exception e)
        {
            throw new Error(e);
        }
    }

    private static final MethodHandle[] LOCATION_METHODS;

    static
    {
        List<MethodHandle> locationMethods = new ArrayList<>();

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = methodType(URI.class, Class.class);

        try
        {
            locationMethods.add(lookup.findStatic(TypeUtil.class, "getCodeSourceLocation", type));
            locationMethods.add(lookup.findStatic(TypeUtil.class, "getModuleLocation", type));
            locationMethods.add(lookup.findStatic(TypeUtil.class, "getClassLoaderLocation", type));
            locationMethods.add(lookup.findStatic(TypeUtil.class, "getSystemClassLoaderLocation", type));
            LOCATION_METHODS = locationMethods.toArray(new MethodHandle[0]);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to establish Location Lookup Handles", e);
        }
    }

    /**
     * Array to List.
     * <p>
     * Works like {@link Arrays#asList(Object...)}, but handles null arrays.
     *
     * @param a the array to convert to a list
     * @param <T> the array and list entry type
     * @return a list backed by the array.
     */
    public static <T> List<T> asList(T[] a)
    {
        if (a == null)
            return Collections.emptyList();
        return Arrays.asList(a);
    }

    /**
     * Class from a canonical name for a type.
     *
     * @param name A class or type name.
     * @return A class , which may be a primitive TYPE field..
     */
    public static Class<?> fromName(String name)
    {
        return name2Class.get(name);
    }

    /**
     * Canonical name for a type.
     *
     * @param type A class , which may be a primitive TYPE field.
     * @return Canonical name.
     */
    public static String toName(Class<?> type)
    {
        return class2Name.get(type);
    }

    public static String toShortName(Class<?> type)
    {
        StringBuilder b = new StringBuilder();
        Package pkg = type.getPackage();
        if (pkg != null)
        {
            String p = pkg.getName();
            if (StringUtil.isNotBlank(p))
            {
                String[] ss = p.split("\\.");
                for (String s : ss)
                    b.append(s.charAt(0));
            }
            b.append('.');
        }
        Class<?> enclosing = type.getEnclosingClass();
        if (enclosing != null)
            appendEnclosing(b, enclosing);
        b.append(type.getSimpleName());
        return b.toString();
    }

    private static void appendEnclosing(StringBuilder b, Class<?> enclosing)
    {
        Class<?> e = enclosing.getEnclosingClass();
        if (e != null)
            appendEnclosing(b, e);
        b.append(enclosing.getSimpleName()).append('$');
    }

    /**
     * Return the Classpath / Classloader reference for the
     * provided class file.
     *
     * <p>
     * Convenience method for the code
     * </p>
     *
     * <pre>
     * String ref = myObject.getClass().getName().replace('.','/') + ".class";
     * </pre>
     *
     * @param clazz the class to reference
     * @return the classpath reference syntax for the class file
     */
    public static String toClassReference(Class<?> clazz)
    {
        return TypeUtil.toClassReference(clazz.getName());
    }

    /**
     * Return the Classpath / Classloader reference for the
     * provided class file.
     *
     * <p>
     * Convenience method for the code
     * </p>
     *
     * <pre>
     * String ref = myClassName.replace('.','/') + ".class";
     * </pre>
     *
     * @param className the class to reference
     * @return the classpath reference syntax for the class file
     */
    public static String toClassReference(String className)
    {
        return StringUtil.replace(className, '.', '/').concat(".class");
    }

    /**
     * Convert String value to instance.
     *
     * @param type The class of the instance, which may be a primitive TYPE field.
     * @param value The value as a string.
     * @return The value as an Object.
     */
    public static Object valueOf(Class<?> type, String value)
    {
        try
        {
            if (type.equals(java.lang.String.class))
                return value;

            Method m = class2Value.get(type);
            if (m != null)
                return m.invoke(null, value);

            if (type.equals(java.lang.Character.TYPE) ||
                type.equals(java.lang.Character.class))
                return value.charAt(0);

            Constructor<?> c = type.getConstructor(java.lang.String.class);
            return c.newInstance(value);
        }
        catch (NoSuchMethodException | IllegalAccessException | InstantiationException x)
        {
            LOG.trace("IGNORED", x);
        }
        catch (InvocationTargetException x)
        {
            if (x.getTargetException() instanceof Error)
                throw (Error)x.getTargetException();
            LOG.trace("IGNORED", x);
        }
        return null;
    }

    /**
     * Convert String value to instance.
     *
     * @param type classname or type (eg int)
     * @param value The value as a string.
     * @return The value as an Object.
     */
    public static Object valueOf(String type, String value)
    {
        return valueOf(fromName(type), value);
    }

    /**
     * Parse an int from a substring.
     * Negative numbers are not handled.
     *
     * @param s String
     * @param offset Offset within string
     * @param length Length of integer or -1 for remainder of string
     * @param base base of the integer
     * @return the parsed integer
     * @throws NumberFormatException if the string cannot be parsed
     */
    public static int parseInt(String s, int offset, int length, int base)
        throws NumberFormatException
    {
        int value = 0;

        if (length < 0)
            length = s.length() - offset;

        for (int i = 0; i < length; i++)
        {
            char c = s.charAt(offset + i);

            int digit = convertHexDigit((int)c);
            if (digit < 0 || digit >= base)
                throw new NumberFormatException(s.substring(offset, offset + length));
            value = value * base + digit;
        }
        return value;
    }

    /**
     * Parse an int from a byte array of ascii characters.
     * Negative numbers are not handled.
     *
     * @param b byte array
     * @param offset Offset within string
     * @param length Length of integer or -1 for remainder of string
     * @param base base of the integer
     * @return the parsed integer
     * @throws NumberFormatException if the array cannot be parsed into an integer
     */
    public static int parseInt(byte[] b, int offset, int length, int base)
        throws NumberFormatException
    {
        int value = 0;

        if (length < 0)
            length = b.length - offset;

        for (int i = 0; i < length; i++)
        {
            char c = (char)(0xff & b[offset + i]);

            int digit = c - '0';
            if (digit < 0 || digit >= base || digit >= 10)
            {
                digit = 10 + c - 'A';
                if (digit < 10 || digit >= base)
                    digit = 10 + c - 'a';
            }
            if (digit < 0 || digit >= base)
                throw new NumberFormatException(new String(b, offset, length));
            value = value * base + digit;
        }
        return value;
    }

    /**
     * @deprecated use {@link StringUtil#fromHexString(String)} instead
     */
    @Deprecated
    public static byte[] parseBytes(String s, int base)
    {
        byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2)
        {
            bytes[i / 2] = (byte)TypeUtil.parseInt(s, i, 2, base);
        }
        return bytes;
    }

    public static String toString(byte[] bytes, int base)
    {
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes)
        {
            int bi = 0xff & b;
            int c = '0' + (bi / base) % base;
            if (c > '9')
                c = 'a' + (c - '0' - 10);
            buf.append((char)c);
            c = '0' + bi % base;
            if (c > '9')
                c = 'a' + (c - '0' - 10);
            buf.append((char)c);
        }
        return buf.toString();
    }

    /**
     * @param c An ASCII encoded character 0-9 a-f A-F
     * @return The byte value of the character 0-16.
     */
    public static byte convertHexDigit(byte c)
    {
        byte b = (byte)((c & 0x1f) + ((c >> 6) * 0x19) - 0x10);
        if (b < 0 || b > 15)
            throw new NumberFormatException("!hex " + c);
        return b;
    }

    /**
     * @param c An ASCII encoded character 0-9 a-f A-F
     * @return The byte value of the character 0-16.
     */
    public static int convertHexDigit(char c)
    {
        int d = ((c & 0x1f) + ((c >> 6) * 0x19) - 0x10);
        if (d < 0 || d > 15)
            throw new NumberFormatException("!hex " + c);
        return d;
    }

    /**
     * @param c An ASCII encoded character 0-9 a-f A-F
     * @return The byte value of the character 0-16.
     */
    public static int convertHexDigit(int c)
    {
        int d = ((c & 0x1f) + ((c >> 6) * 0x19) - 0x10);
        if (d < 0 || d > 15)
            throw new NumberFormatException("!hex " + c);
        return d;
    }

    public static void toHex(byte b, Appendable buf)
    {
        try
        {
            int d = 0xf & ((0xF0 & b) >> 4);
            buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
            d = 0xf & b;
            buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void toHex(int value, Appendable buf) throws IOException
    {
        int d = 0xf & ((0xF0000000 & value) >> 28);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & ((0x0F000000 & value) >> 24);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & ((0x00F00000 & value) >> 20);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & ((0x000F0000 & value) >> 16);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & ((0x0000F000 & value) >> 12);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & ((0x00000F00 & value) >> 8);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & ((0x000000F0 & value) >> 4);
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = 0xf & value;
        buf.append((char)((d > 9 ? ('A' - 10) : '0') + d));

        Integer.toString(0, 36);
    }

    public static void toHex(long value, Appendable buf) throws IOException
    {
        toHex((int)(value >> 32), buf);
        toHex((int)value, buf);
    }

    /**
     * @deprecated use {@link StringUtil#toHexString(byte)} instead
     */
    @Deprecated
    public static String toHexString(byte b)
    {
        return StringUtil.toHexString(b);
    }

    /**
     * @deprecated use {@link StringUtil#toHexString(byte[])} instead
     */
    @Deprecated
    public static String toHexString(byte[] b)
    {
        return StringUtil.toHexString(b);
    }

    /**
     * @deprecated use {@link StringUtil#toHexString(byte[], int, int)} instead
     */
    @Deprecated
    public static String toHexString(byte[] b, int offset, int length)
    {
        return StringUtil.toHexString(b, offset, length);
    }

    /**
     * @deprecated use {@link StringUtil#fromHexString(String)}
     */
    @Deprecated
    public static byte[] fromHexString(String s)
    {
        return StringUtil.fromHexString(s);
    }

    public static void dump(Class<?> c)
    {
        System.err.println("Dump: " + c);
        dump(c.getClassLoader());
    }

    public static void dump(ClassLoader cl)
    {
        System.err.println("Dump Loaders:");
        while (cl != null)
        {
            System.err.println("  loader " + cl);
            cl = cl.getParent();
        }
    }

    /**
     * @param o Object to test for true
     * @return True if passed object is not null and is either a Boolean with value true or evaluates to a string that evaluates to true.
     */
    public static boolean isTrue(Object o)
    {
        if (o == null)
            return false;
        if (o instanceof Boolean)
            return ((Boolean)o).booleanValue();
        return Boolean.parseBoolean(o.toString());
    }

    /**
     * @param o Object to test for false
     * @return True if passed object is not null and is either a Boolean with value false or evaluates to a string that evaluates to false.
     */
    public static boolean isFalse(Object o)
    {
        if (o == null)
            return false;
        if (o instanceof Boolean)
            return !((Boolean)o).booleanValue();
        return "false".equalsIgnoreCase(o.toString());
    }

    /**
     * Attempt to find the Location of a loaded Class.
     * <p>
     * This can be null for primitives, void, and in-memory classes.
     * </p>
     *
     * @param clazz the loaded class to find a location for.
     * @return the location as a URI (this is a URI pointing to a holder of the class: a directory,
     * a jar file, a {@code jrt://} resource, etc), or null of no location available.
     */
    public static URI getLocationOfClass(Class<?> clazz)
    {
        URI location;

        for (MethodHandle locationMethod : LOCATION_METHODS)
        {
            try
            {
                location = (URI)locationMethod.invoke(clazz);
                if (location != null)
                {
                    return location;
                }
            }
            catch (Throwable cause)
            {
                cause.printStackTrace(System.err);
            }
        }
        return null;
    }

    public static URI getSystemClassLoaderLocation(Class<?> clazz)
    {
        return getClassLoaderLocation(clazz, ClassLoader.getSystemClassLoader());
    }

    public static URI getClassLoaderLocation(Class<?> clazz)
    {
        return getClassLoaderLocation(clazz, clazz.getClassLoader());
    }

    public static URI getClassLoaderLocation(Class<?> clazz, ClassLoader loader)
    {
        if (loader == null)
        {
            return null;
        }

        try
        {
            String resourceName = TypeUtil.toClassReference(clazz);
            if (loader != null)
            {
                URL url = loader.getResource(resourceName);
                if (url != null)
                {
                    URI uri = url.toURI();
                    String uriStr = uri.toASCIIString();
                    if (uriStr.startsWith("jar:file:"))
                    {
                        uriStr = uriStr.substring(4);
                        int idx = uriStr.indexOf("!/");
                        if (idx > 0)
                        {
                            return URI.create(uriStr.substring(0, idx));
                        }
                    }
                    return uri;
                }
            }
        }
        catch (URISyntaxException ignored)
        {
        }
        return null;
    }

    public static URI getCodeSourceLocation(Class<?> clazz)
    {
        try
        {
            ProtectionDomain domain = AccessController.doPrivileged((PrivilegedAction<ProtectionDomain>)() -> clazz.getProtectionDomain());
            if (domain != null)
            {
                CodeSource source = domain.getCodeSource();
                if (source != null)
                {
                    URL location = source.getLocation();

                    if (location != null)
                    {
                        return location.toURI();
                    }
                }
            }
        }
        catch (URISyntaxException ignored)
        {
        }
        return null;
    }

    public static URI getModuleLocation(Class<?> clazz)
    {
        Module module = clazz.getModule();
        if (module == null)
        {
            return null;
        }

        ModuleLayer layer = module.getLayer();
        if (layer == null)
        {
            return null;
        }

        Configuration configuration = layer.configuration();
        if (configuration == null)
        {
            return null;
        }

        Optional<ResolvedModule> resolvedModule = configuration.findModule(module.getName());
        if ((resolvedModule == null) || !resolvedModule.isPresent())
        {
            return null;
        }

        ModuleReference moduleReference = resolvedModule.get().reference();
        if (moduleReference == null)
        {
            return null;
        }

        Optional<URI> location = moduleReference.location();
        if (location.isPresent())
        {
            return location.get();
        }

        return null;
    }

    public static <T> Iterator<T> concat(Iterator<T> i1, Iterator<T> i2)
    {
        return new Iterator<>()
        {
            @Override
            public boolean hasNext()
            {
                return i1.hasNext() || i2.hasNext();
            }

            @Override
            public T next()
            {
                return i1.hasNext() ? i1.next() : i2.next();
            }
        };
    }

    /**
     * Used on a {@link ServiceLoader#stream()} with {@link Stream#flatMap(Function)},
     * so that in the case a {@link ServiceConfigurationError} is thrown it warns and
     * continues iterating through the service loader.
     * <br>Usage Example:
     * <p>{@code ServiceLoader.load(Service.class).stream().flatMap(TypeUtil::providerMap).collect(Collectors.toList());}</p>
     * @param <T> The class of the service type.
     * @param provider The service provider to instantiate.
     * @return a stream of the loaded service providers.
     */
    private static <T> Stream<T> mapToService(ServiceLoader.Provider<T> provider)
    {
        try
        {
            return Stream.of(provider.get());
        }
        catch (ServiceConfigurationError error)
        {
            LOG.warn("Service Provider failed to load", error);
            return Stream.empty();
        }
    }

    /**
     * Utility method to provide a stream of the service type from a {@link ServiceLoader}.
     * Log warnings will be given for any {@link ServiceConfigurationError}s which occur when loading or
     * instantiating the services.
     * @param serviceLoader the ServiceLoader instance to use.
     * @param <T> the type of the service to load.
     * @return a stream of the service type which will not throw {@link ServiceConfigurationError}.
     */
    public static <T> Stream<T> serviceStream(ServiceLoader<T> serviceLoader)
    {
        return serviceProviderStream(serviceLoader).flatMap(TypeUtil::mapToService);
    }

    /**
     * Utility to create a stream which provides the same functionality as {@link ServiceLoader#stream()}.
     * However this also guards the case in which {@link Iterator#hasNext()} throws. Any exceptions
     * from the underlying iterator will be cached until the {@link ServiceLoader.Provider#get()} is called.
     * @param serviceLoader the ServiceLoader instance to use.
     * @param <T> the type of the service to load.
     * @return A stream that lazily loads providers for this loader's service
     */
    public static <T> Stream<ServiceLoader.Provider<T>> serviceProviderStream(ServiceLoader<T> serviceLoader)
    {
        return StreamSupport.stream(new ServiceLoaderSpliterator<>(serviceLoader), false);
    }
}
