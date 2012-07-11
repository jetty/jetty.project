// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.extensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.ExtensionRegistry;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class WebSocketExtensionRegistry implements ExtensionRegistry
{
    private Map<String, Class<? extends Extension>> registry;

    public WebSocketExtensionRegistry()
    {
        registry = new HashMap<String, Class<? extends Extension>>();
    }

    @Override
    public boolean isAvailable(String name)
    {
        synchronized (registry)
        {
            return registry.containsKey(name);
        }
    }

    @Override
    public Iterator<Class<? extends Extension>> iterator()
    {
        List<Class<? extends Extension>> coll = new ArrayList<>();
        synchronized (registry)
        {
            coll.addAll(registry.values());
            return coll.iterator();
        }
    }

    @Override
    public Extension newInstance(ExtensionConfig config)
    {
        if (config == null)
        {
            return null;
        }
        String name = config.getName();
        if (StringUtil.isBlank(name))
        {
            return null;
        }
        Class<? extends Extension> extClass = registry.get(name);
        if (extClass == null)
        {
            return null;
        }

        try
        {
            Extension ext = extClass.newInstance();
            ext.setConfig(config);
            return ext;
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new WebSocketException("Cannot instantiate extension: " + extClass,e);
        }
    }

    @Override
    public void register(String name, Class<? extends Extension> extension)
    {
        synchronized (registry)
        {
            registry.put(name,extension);
        }
    }

    @Override
    public void unregister(String name)
    {
        synchronized (registry)
        {
            registry.remove(name);
        }
    }
}
