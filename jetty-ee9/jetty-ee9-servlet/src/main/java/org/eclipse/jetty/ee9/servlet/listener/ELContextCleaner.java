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

package org.eclipse.jetty.ee9.servlet.listener;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.eclipse.jetty.util.Loader;

/**
 * ELContextCleaner
 *
 * Clean up BeanELResolver when the context is going out
 * of service:
 *
 * See http://java.net/jira/browse/GLASSFISH-1649
 * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=353095
 */
@Deprecated
public class ELContextCleaner implements ServletContextListener
{
    // IMPORTANT: This class cannot have a slf4j Logger
    // As it will force this requirement on webapps.
    // private static final Logger LOG = LoggerFactory.getLogger(ELContextCleaner.class);

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        try
        {
            //Check that the BeanELResolver class is on the classpath
            Class<?> beanELResolver = Loader.loadClass("jakarta.el.BeanELResolver");

            //Get a reference via reflection to the properties field which is holding class references
            Field field = beanELResolver.getDeclaredField("properties");

            field.setAccessible(true);

            //Get rid of references
            purgeEntries(sce.getServletContext(), field);
        }
        catch (ClassNotFoundException | NoSuchFieldException e)
        {
            //BeanELResolver not on classpath,or has no .properties field, ignore
        }
        catch (SecurityException | IllegalArgumentException | IllegalAccessException e)
        {
            sce.getServletContext().log("Cannot purge classes from jakarta.el.BeanELResolver", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void purgeEntries(ServletContext context, Field properties)
        throws IllegalArgumentException, IllegalAccessException
    {
        if (properties == null)
            return;

        Map map = (Map)properties.get(null);
        if (map == null)
            return;

        Iterator<Class<?>> itor = map.keySet().iterator();
        while (itor.hasNext())
        {
            Class<?> clazz = itor.next();
            context.log(String.format("Clazz: %s loaded by %s", clazz, clazz.getClassLoader()));
            if (Thread.currentThread().getContextClassLoader().equals(clazz.getClassLoader()))
            {
                itor.remove();
                context.log("removed");
            }
            else
            {
                context.log(String.format("not removed: contextClassLoader=%s class's classLoader=%s",
                    Thread.currentThread().getContextClassLoader(), clazz.getClassLoader()));
            }
        }
    }
}
