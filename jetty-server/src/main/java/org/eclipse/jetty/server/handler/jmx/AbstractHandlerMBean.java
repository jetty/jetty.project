//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.server.handler.jmx;

import java.io.File;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;

public class AbstractHandlerMBean extends ObjectMBean
{
    public AbstractHandlerMBean(Object managedObject)
    {
        super(managedObject);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getObjectContextBasis()
    {
        if (_managed != null )
        {
            String basis = null;
            if (_managed instanceof ContextHandler)
            {
                return null;
            }
            else if (_managed instanceof AbstractHandler)
            {
                AbstractHandler handler = (AbstractHandler)_managed;
                Server server = handler.getServer();
                if (server != null)
                {
                    ContextHandler context = 
                        AbstractHandlerContainer.findContainerOf(server,
                                ContextHandler.class, handler);
                    
                    if (context != null)
                        basis = context.getName();
                }
            }
            if (basis != null)
                return basis;
        }
        return super.getObjectContextBasis();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String getObjectNameBasis()
    {
        if (_managed != null )
        {
            String name = null;
            if (_managed instanceof ContextHandler)
            {
                ContextHandler context = (ContextHandler)_managed;
                name = context.getName();
            }
            
            if (name != null)
                return name;
        }
        
        return super.getObjectNameBasis();
    }
}
