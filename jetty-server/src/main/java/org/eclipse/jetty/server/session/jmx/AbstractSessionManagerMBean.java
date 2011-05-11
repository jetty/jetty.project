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

package org.eclipse.jetty.server.session.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;

public class AbstractSessionManagerMBean extends ObjectMBean
{
    public AbstractSessionManagerMBean(Object managedObject)
    {
        super(managedObject);
    }

    /* ------------------------------------------------------------ */
    public String getObjectContextBasis()
    {
        if (_managed != null && _managed instanceof AbstractSessionManager)
        {
            AbstractSessionManager manager = (AbstractSessionManager)_managed;
            
            String basis = null;
            SessionHandler handler = manager.getSessionHandler();
            if (handler != null)
            {
                ContextHandler context = 
                    AbstractHandlerContainer.findContainerOf(handler.getServer(), 
                                                             ContextHandler.class,
                                                             handler);
                if (context != null)
                    basis = context.getName();
            }

            if (basis != null)
                return basis;
        }
        return super.getObjectContextBasis();
    }
}
