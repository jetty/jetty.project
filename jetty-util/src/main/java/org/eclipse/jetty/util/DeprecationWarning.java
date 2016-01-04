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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class DeprecationWarning implements Decorator
{
    private static final Logger LOG = Log.getLogger(DeprecationWarning.class);

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
                LOG.warn("Using @Deprecated Class {}",clazz.getName());
            }
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
        }

        verifyIndirectTypes(clazz.getSuperclass(),clazz,"Class");
        for (Class<?> ifaceClazz : clazz.getInterfaces())
        {
            verifyIndirectTypes(ifaceClazz,clazz,"Interface");
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
                    LOG.warn("Using indirect @Deprecated {} {} - (seen from {})",typeName,superClazz.getName(),clazz);
                }

                superClazz = superClazz.getSuperclass();
            }
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
        }
    }

    @Override
    public void destroy(Object o)
    {
    }
}
