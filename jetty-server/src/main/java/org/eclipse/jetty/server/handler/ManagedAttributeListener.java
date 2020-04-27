//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Enable Jetty style JMX MBeans from within a Context
 */
public class ManagedAttributeListener implements ServletContextListener, ServletContextAttributeListener
{
    private static final Logger LOG = Log.getLogger(ManagedAttributeListener.class);

    final Set<String> _managedAttributes = new HashSet<>();
    final ContextHandler _context;

    public ManagedAttributeListener(ContextHandler context, String... managedAttributes)
    {
        _context = context;

        for (String attr : managedAttributes)
        {
            _managedAttributes.add(attr);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("managedAttributes {}", _managedAttributes);
    }

    @Override
    public void attributeReplaced(ServletContextAttributeEvent event)
    {
        if (_managedAttributes.contains(event.getName()))
            updateBean(event.getName(), event.getValue(), event.getServletContext().getAttribute(event.getName()));
    }

    @Override
    public void attributeRemoved(ServletContextAttributeEvent event)
    {
        if (_managedAttributes.contains(event.getName()))
            updateBean(event.getName(), event.getValue(), null);
    }

    @Override
    public void attributeAdded(ServletContextAttributeEvent event)
    {
        if (_managedAttributes.contains(event.getName()))
            updateBean(event.getName(), null, event.getValue());
    }

    @Override
    public void contextInitialized(ServletContextEvent event)
    {
        // Update existing attributes
        for (String name : _context.getServletContext().getAttributeNameSet())
        {
            if (_managedAttributes.contains(name))
                updateBean(name, null, event.getServletContext().getAttribute(name));
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event)
    {
        for (String name : _context.getServletContext().getAttributeNameSet())
        {
            if (_managedAttributes.contains(name))
                updateBean(name, event.getServletContext().getAttribute(name), null);
        }
    }

    protected void updateBean(String name, Object oldBean, Object newBean)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("update {} {}->{} on {}", name, oldBean, newBean, _context);
        _context.updateBean(oldBean, newBean, false);
    }
}
