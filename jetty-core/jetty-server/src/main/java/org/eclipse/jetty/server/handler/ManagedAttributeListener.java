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

package org.eclipse.jetty.server.handler;

import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enable Jetty style JMX MBeans from within a Context
 */
public class ManagedAttributeListener implements ServletContextListener, ServletContextAttributeListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ManagedAttributeListener.class);

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
