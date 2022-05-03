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

package org.eclipse.jetty.ee10.webapp;

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
