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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeprecationWarning implements Decorator
{
    private static final Logger LOG = LoggerFactory.getLogger(DeprecationWarning.class);

    @Override
    public <T> T decorate(T o)
    {
        if (o == null)
        {
            return null;
        }

        Class<?> clazz = o.getClass();

        try
        {
            Deprecated depr = clazz.getAnnotation(Deprecated.class);
            if (depr != null)
            {
                LOG.warn("Using @Deprecated Class {}", clazz.getName());
            }
        }
        catch (Throwable t)
        {
            LOG.trace("IGNORED", t);
        }

        verifyIndirectTypes(clazz.getSuperclass(), clazz, "Class");
        for (Class<?> ifaceClazz : clazz.getInterfaces())
        {
            verifyIndirectTypes(ifaceClazz, clazz, "Interface");
        }

        return o;
    }

    private void verifyIndirectTypes(Class<?> superClazz, Class<?> clazz, String typeName)
    {
        try
        {
            // Report on super class deprecation too
            while (superClazz != null && superClazz != Object.class)
            {
                Deprecated supDepr = superClazz.getAnnotation(Deprecated.class);
                if (supDepr != null)
                {
                    LOG.warn("Using indirect @Deprecated {} {} - (seen from {})", typeName, superClazz.getName(), clazz);
                }

                superClazz = superClazz.getSuperclass();
            }
        }
        catch (Throwable t)
        {
            LOG.trace("IGNORED", t);
        }
    }

    @Override
    public void destroy(Object o)
    {
    }
}
