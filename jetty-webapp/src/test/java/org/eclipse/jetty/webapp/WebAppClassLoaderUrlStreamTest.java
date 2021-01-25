//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.webapp;

import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class WebAppClassLoaderUrlStreamTest extends WebAppClassLoaderTest
{
    public static class URLHandlers implements URLStreamHandlerFactory
    {
        private static final String[] STREAM_HANDLER_PREFIXES;

        static
        {
            STREAM_HANDLER_PREFIXES = new String[]{
                "sun.net.www.protocol",
                "org.apache.harmony.luni.internal.net.www.protocol",
                "javax.net.ssl"
            };
        }

        private Map<String, URLStreamHandler> handlers = new HashMap<>();
        private ClassLoader loader;

        public URLHandlers(ClassLoader loader)
        {
            this.loader = loader;
        }

        private URLStreamHandler getBuiltInHandler(String protocol, ClassLoader classLoader)
        {
            URLStreamHandler handler = handlers.get(protocol);

            if (handler == null)
            {
                for (String prefix : STREAM_HANDLER_PREFIXES)
                {
                    String className = prefix + '.' + protocol + ".Handler";
                    try
                    {
                        Class<?> clazz = Class.forName(className, false, classLoader);
                        handler = (URLStreamHandler)clazz.getDeclaredConstructor().newInstance();
                        break;
                    }
                    catch (Exception ignore)
                    {
                        ignore.printStackTrace(System.err);
                    }
                }

                if (handler != null)
                {
                    handlers.put(protocol, handler);
                }
            }

            if (handler == null)
            {
                throw new RuntimeException("Unable to find handler for protocol [" + protocol + "]");
            }

            return handler;
        }

        @Override
        public URLStreamHandler createURLStreamHandler(String protocol)
        {
            try
            {
                return getBuiltInHandler(protocol, loader);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Unable to create URLStreamHandler for protocol [" + protocol + "]");
            }
        }
    }

    @AfterEach
    public void cleanupURLStreamHandlerFactory()
    {
        URLStreamHandlerUtil.setFactory(null);
    }

    @BeforeEach
    @Override
    public void init() throws Exception
    {
        super.init();
        URLStreamHandlerUtil.setFactory(new URLHandlers(_loader));
    }
}
