//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package com.acme.test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import static java.lang.invoke.MethodType.methodType;

public class ClassInfo
{
    private static final MethodHandle[] LOCATION_METHODS;
    private static final ModuleLocation MODULE_LOCATION;

    static
    {
        List<MethodHandle> locationMethods = new ArrayList<>();

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = methodType(URI.class, Class.class);

        try
        {
            locationMethods.add(lookup.findStatic(ClassInfo.class, "getCodeSourceLocation", type));
            ModuleLocation moduleLocation = null;
            try
            {
                moduleLocation = new ModuleLocation();
                locationMethods.add(lookup.findStatic(ClassInfo.class, "getModuleLocation", type));
            }
            catch (UnsupportedOperationException e)
            {
                System.err.println("JVM Runtime does not support Modules");
            }
            MODULE_LOCATION = moduleLocation;
            locationMethods.add(lookup.findStatic(ClassInfo.class, "getClassLoaderLocation", type));
            locationMethods.add(lookup.findStatic(ClassInfo.class, "getSystemClassLoaderLocation", type));
            LOCATION_METHODS = locationMethods.toArray(new MethodHandle[0]);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to establish Location Lookup Handles", e);
        }
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

    public static URI getClassLoaderLocation(Class<?> clazz)
    {
        return getClassLoaderLocation(clazz, clazz.getClassLoader());
    }

    public static URI getSystemClassLoaderLocation(Class<?> clazz)
    {
        return getClassLoaderLocation(clazz, ClassLoader.getSystemClassLoader());
    }

    public static URI getClassLoaderLocation(Class<?> clazz, ClassLoader loader)
    {
        if (loader == null)
        {
            return null;
        }

        try
        {
            String resourceName = clazz.getName().replace('.', '/').concat(".class");
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
        // In Jetty 10, this method can be implemented directly, without reflection
        if (MODULE_LOCATION != null)
        {
            return MODULE_LOCATION.getModuleLocation(clazz);
        }
        return null;
    }

    public static List<Method> findMethods(Class<?> clazz, String methodName)
    {
        List<Method> methods = new ArrayList<>();
        for (Method method : clazz.getMethods())
        {
            if (method.getName().equalsIgnoreCase(methodName))
            {
                methods.add(method);
            }
        }
        return methods;
    }
}
