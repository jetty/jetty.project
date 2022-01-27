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
import java.io.ObjectInputStream;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * ClassLoadingObjectInputStream
 *
 * For re-inflating serialized objects, this class uses the thread context classloader
 * rather than the jvm's default classloader selection.
 */
public class ClassLoadingObjectInputStream extends ObjectInputStream
{

    protected static class ClassLoaderThreadLocal extends ThreadLocal<ClassLoader>
    {
        protected static final ClassLoader UNSET = new ClassLoader() {};

        @Override
        protected ClassLoader initialValue()
        {
            return UNSET;
        }
    }

    private ThreadLocal<ClassLoader> _classloader = new ClassLoaderThreadLocal();

    public ClassLoadingObjectInputStream(java.io.InputStream in) throws IOException
    {
        super(in);
    }

    public ClassLoadingObjectInputStream() throws IOException
    {
        super();
    }

    public Object readObject(ClassLoader loader)
        throws IOException, ClassNotFoundException
    {
        try
        {
            _classloader.set(loader);
            return readObject();
        }
        finally
        {
            _classloader.set(ClassLoaderThreadLocal.UNSET);
        }
    }

    @Override
    public Class<?> resolveClass(java.io.ObjectStreamClass cl) throws IOException, ClassNotFoundException
    {
        try
        {
            ClassLoader loader = _classloader.get();
            if (ClassLoaderThreadLocal.UNSET == loader)
                loader = Thread.currentThread().getContextClassLoader();

            return Class.forName(cl.getName(), false, loader);
        }
        catch (ClassNotFoundException e)
        {
            return super.resolveClass(cl);
        }
    }

    @Override
    protected Class<?> resolveProxyClass(String[] interfaces)
        throws IOException, ClassNotFoundException
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        ClassLoader nonPublicLoader = null;
        boolean hasNonPublicInterface = false;

        // define proxy in class loader of non-public interface(s), if any
        Class<?>[] classObjs = new Class[interfaces.length];
        for (int i = 0; i < interfaces.length; i++)
        {
            Class<?> cl = Class.forName(interfaces[i], false, loader);
            if ((cl.getModifiers() & Modifier.PUBLIC) == 0)
            {
                if (hasNonPublicInterface)
                {
                    if (nonPublicLoader != cl.getClassLoader())
                    {
                        throw new IllegalAccessError(
                            "conflicting non-public interface class loaders");
                    }
                }
                else
                {
                    nonPublicLoader = cl.getClassLoader();
                    hasNonPublicInterface = true;
                }
            }
            classObjs[i] = cl;
        }
        try
        {
            // TODO: This is @Deprecated and not useable in the new JPMS reality
            return Proxy.getProxyClass(hasNonPublicInterface ? nonPublicLoader : loader, classObjs);
        }
        catch (IllegalArgumentException e)
        {
            throw new ClassNotFoundException(null, e);
        }
    }
}
