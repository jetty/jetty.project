//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
 * 
 */
public class ClassLoadingObjectInputStream extends ObjectInputStream
{
    /* ------------------------------------------------------------ */
    public ClassLoadingObjectInputStream(java.io.InputStream in) throws IOException
    {
        super(in);
    }

    /* ------------------------------------------------------------ */
    public ClassLoadingObjectInputStream () throws IOException
    {
        super();
    }

    /* ------------------------------------------------------------ */
    @Override
    public Class<?> resolveClass (java.io.ObjectStreamClass cl) throws IOException, ClassNotFoundException
    {
        try
        {
            return Class.forName(cl.getName(), false, Thread.currentThread().getContextClassLoader());
        }
        catch (ClassNotFoundException e)
        {
            return super.resolveClass(cl);
        }
    }
    
    /* ------------------------------------------------------------ */
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
            return Proxy.getProxyClass(hasNonPublicInterface ? nonPublicLoader : loader,classObjs);
        } 
        catch (IllegalArgumentException e) 
        {
            throw new ClassNotFoundException(null, e);
        }    
    }
}
