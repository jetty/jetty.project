//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.nosql.mongodb.jmx;

import org.eclipse.jetty.nosql.mongodb.MongoSessionManager;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.session.jmx.AbstractSessionManagerMBean;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("Mongo Session Manager MBean")
public class MongoSessionManagerMBean extends AbstractSessionManagerMBean
{

    public MongoSessionManagerMBean(Object managedObject)
    {
        super(managedObject);
    }

    /* ------------------------------------------------------------ */
    public String getObjectContextBasis()
    {
        if (_managed != null && _managed instanceof MongoSessionManager)
        {
            MongoSessionManager manager = (MongoSessionManager)_managed;
            
            String basis = null;
            SessionHandler handler = manager.getSessionHandler();
            if (handler != null)
            {
                ContextHandler context = 
                    AbstractHandlerContainer.findContainerOf(handler.getServer(), 
                                                             ContextHandler.class,
                                                             handler);
                if (context != null)
                    basis = getContextName(context);
            }

            if (basis != null)
                return basis;
        }
        return super.getObjectContextBasis();
    }
    
  
    
}
