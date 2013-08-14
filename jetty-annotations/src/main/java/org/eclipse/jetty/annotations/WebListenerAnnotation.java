//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebListenerAnnotation
 *
 *
 */
public class WebListenerAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = Log.getLogger(WebListenerAnnotation.class);

    /**
     * @param context
     * @param className
     */
    public WebListenerAnnotation(WebAppContext context, String className)
    {
        super(context, className);
    }
    
    public WebListenerAnnotation(WebAppContext context, String className, Resource resource)
    {
        super(context, className, resource);
    }

    /**
     * @see DiscoveredAnnotation#apply()
     */
    public void apply()
    {
        // TODO check algorithm against ordering rules for descriptors v annotations

        Class clazz = getTargetClass();

        if (clazz == null)
        {
            LOG.warn(_className+" cannot be loaded");
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
                java.util.EventListener listener = (java.util.EventListener)clazz.newInstance();
                MetaData metaData = _context.getMetaData();
                if (metaData.getOrigin(clazz.getName()+".listener") == Origin.NotSet)
                    _context.addEventListener(listener);
            }
            else
                LOG.warn(clazz.getName()+" does not implement one of the servlet listener interfaces");
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }
}
