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

package org.eclipse.jetty.ee10.annotations;

import java.util.EventListener;

import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestAttributeListener;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.ee10.servlet.ListenerHolder;
import org.eclipse.jetty.ee10.servlet.Source;
import org.eclipse.jetty.ee10.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.ee10.webapp.MetaData;
import org.eclipse.jetty.ee10.webapp.Origin;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebListenerAnnotation
 */
public class WebListenerAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = LoggerFactory.getLogger(WebListenerAnnotation.class);

    public WebListenerAnnotation(WebAppContext context, String className)
    {
        super(context, className);
    }

    public WebListenerAnnotation(WebAppContext context, String className, Resource resource)
    {
        super(context, className, resource);
    }

    @Override
    public void apply()
    {
        Class<? extends java.util.EventListener> clazz = (Class<? extends EventListener>)getTargetClass();

        if (clazz == null)
        {
            LOG.warn("{} cannot be loaded", _className);
            return;
        }

        try
        {
            if (ServletContextListener.class.isAssignableFrom(clazz) ||
                ServletContextAttributeListener.class.isAssignableFrom(clazz) ||
                ServletRequestListener.class.isAssignableFrom(clazz) ||
                ServletRequestAttributeListener.class.isAssignableFrom(clazz) ||
                HttpSessionListener.class.isAssignableFrom(clazz) ||
                HttpSessionAttributeListener.class.isAssignableFrom(clazz) ||
                HttpSessionIdListener.class.isAssignableFrom(clazz))
            {
                MetaData metaData = _context.getMetaData();
                if (metaData.getOrigin(clazz.getName() + ".listener") == Origin.NotSet)
                {
                    ListenerHolder h = _context.getServletHandler().newListenerHolder(new Source(Source.Origin.ANNOTATION, clazz));
                    h.setHeldClass(clazz);
                    _context.getServletHandler().addListener(h);
                    metaData.setOrigin(clazz.getName() + ".listener", clazz.getAnnotation(WebListener.class), clazz);
                }
            }
            else
                LOG.warn("{} does not implement one of the servlet listener interfaces", clazz.getName());
        }
        catch (Exception e)
        {
            LOG.warn("Unable to add listener {}", clazz, e);
        }
    }
}
