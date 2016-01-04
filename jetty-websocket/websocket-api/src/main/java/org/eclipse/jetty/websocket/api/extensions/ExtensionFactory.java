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

package org.eclipse.jetty.websocket.api.extensions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

public abstract class ExtensionFactory implements Iterable<Class<? extends Extension>>
{
    private ServiceLoader<Extension> extensionLoader = ServiceLoader.load(Extension.class);
    private Map<String, Class<? extends Extension>> availableExtensions;

    public ExtensionFactory()
    {
        availableExtensions = new HashMap<>();
        for (Extension ext : extensionLoader)
        {
            if (ext != null)
            {
                availableExtensions.put(ext.getName(),ext.getClass());
            }
        }
    }

    public Map<String, Class<? extends Extension>> getAvailableExtensions()
    {
        return availableExtensions;
    }

    public Class<? extends Extension> getExtension(String name)
    {
        return availableExtensions.get(name);
    }

    public Set<String> getExtensionNames()
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

    public abstract Extension newInstance(ExtensionConfig config);

    public void register(String name, Class<? extends Extension> extension)
    {
        availableExtensions.put(name,extension);
    }

    public void unregister(String name)
    {
        availableExtensions.remove(name);
    }
}
