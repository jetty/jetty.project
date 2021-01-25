//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.webapp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.util.Arrays;
import java.util.Optional;

public final class URLStreamHandlerUtil
{
    public static void setFactory(URLStreamHandlerFactory factory)
    {
        try
        {
            // First, reset the factory field
            Field factoryField = getURLStreamHandlerFactoryField();
            factoryField.setAccessible(true);
            factoryField.set(null, null);

            if (factory != null)
            {
                // Next, set the factory
                URL.setURLStreamHandlerFactory(factory);
            }
        }
        catch (Throwable ignore)
        {
            // ignore.printStackTrace(System.err);
        }
    }

    public static URLStreamHandlerFactory getFactory()
    {
        try
        {
            // First, reset the factory field
            Field factoryField = getURLStreamHandlerFactoryField();
            factoryField.setAccessible(true);
            return (URLStreamHandlerFactory)factoryField.get(null);
        }
        catch (Throwable ignore)
        {
            return null;
        }
    }

    private static Field getURLStreamHandlerFactoryField()
    {
        Optional<Field> optFactoryField = Arrays.stream(URL.class.getDeclaredFields())
            .filter((f) -> Modifier.isStatic(f.getModifiers()) &&
                f.getType().equals(URLStreamHandlerFactory.class))
            .findFirst();

        if (optFactoryField.isPresent())
            return optFactoryField.get();

        throw new RuntimeException("Cannot find URLStreamHandlerFactory field in " + URL.class.getName());
    }
}
