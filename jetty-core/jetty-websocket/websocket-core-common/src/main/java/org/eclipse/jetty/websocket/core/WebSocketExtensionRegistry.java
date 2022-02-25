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

package org.eclipse.jetty.websocket.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

public class WebSocketExtensionRegistry implements Iterable<Class<? extends Extension>>
{
    private final Map<String, Class<? extends Extension>> availableExtensions = new HashMap<>();

    public WebSocketExtensionRegistry()
    {
        // Load extensions from container loader.
        TypeUtil.serviceStream(ServiceLoader.load(Extension.class, this.getClass().getClassLoader()))
            .forEach(ext -> availableExtensions.put(ext.getName(), ext.getClass()));
    }

    public Map<String, Class<? extends Extension>> getAvailableExtensions()
    {
        return availableExtensions;
    }

    public Class<? extends Extension> getExtension(String name)
    {
        return availableExtensions.get(name);
    }

    public Set<String> getAvailableExtensionNames()
    {
        return availableExtensions.keySet();
    }

    public boolean isAvailable(String name)
    {
        return availableExtensions.containsKey(name);
    }

    @Override
    public Iterator<Class<? extends Extension>> iterator()
    {
        return availableExtensions.values().iterator();
    }

    public Extension newInstance(ExtensionConfig config, WebSocketComponents components)
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

        Class<? extends Extension> extClass = getExtension(name);
        if (extClass == null)
        {
            return null;
        }

        try
        {
            Extension ext = components.getObjectFactory().createInstance(extClass);
            ext.init(config, components);

            return ext;
        }
        catch (Throwable t)
        {
            throw new BadMessageException("Cannot instantiate extension: " + extClass, t);
        }
    }

    public void register(String name, Class<? extends Extension> extension)
    {
        availableExtensions.put(name, extension);
    }

    public void unregister(String name)
    {
        availableExtensions.remove(name);
    }
}
