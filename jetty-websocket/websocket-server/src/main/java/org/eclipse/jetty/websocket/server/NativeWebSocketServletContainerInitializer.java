//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.server.handler.ContextHandler;

public class NativeWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    public static NativeWebSocketConfiguration getDefaultFrom(ServletContext context)
    {
        final String KEY = NativeWebSocketConfiguration.class.getName();
        
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getAttribute(KEY);
        if (configuration == null)
        {
            // Not provided to us, create a new default one.
            configuration = new NativeWebSocketConfiguration(context);
            context.setAttribute(KEY, configuration);

            // Attach default configuration to context lifecycle
            if (context instanceof ContextHandler.Context)
            {
                ContextHandler handler = ((ContextHandler.Context)context).getContextHandler();
                // Let ContextHandler handle configuration lifecycle
                handler.addManaged(configuration);
            }
        }
        return configuration;
    }
    
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx)
    {
        // initialize
        getDefaultFrom(ctx);
    }
}
