//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.Map;
import java.util.ServiceLoader;

public class ExtensionFactory
{
    private static ServiceLoader<Extension> extensionLoader = ServiceLoader.load(Extension.class);
    private static Map<String, Extension> availableExtensions;

    public static Map<String, Extension> getAvailableExtensions()
    {
        synchronized (extensionLoader)
        {
            if (availableExtensions == null)
            {
                availableExtensions = new HashMap<>();
                for (Extension ext : extensionLoader)
                {
                    availableExtensions.put(ext.getName(),ext);
                }
            }

            return availableExtensions;
        }
    }
}
