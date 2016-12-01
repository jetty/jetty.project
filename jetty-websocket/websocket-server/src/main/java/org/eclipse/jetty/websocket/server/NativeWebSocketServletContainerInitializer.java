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

package org.eclipse.jetty.websocket.server;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

public class NativeWebSocketServletContainerInitializer implements ServletContainerInitializer
{
    public static NativeWebSocketConfiguration getDefaultFrom(ServletContext context)
    {
        final String KEY = NativeWebSocketConfiguration.class.getName();
        
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration) context.getAttribute(KEY);
        if (configuration == null)
        {
            configuration = new NativeWebSocketConfiguration(context);
            context.setAttribute(KEY, configuration);
        }
        return configuration;
    }
    
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        // initialize
        getDefaultFrom(ctx);
    }
}
