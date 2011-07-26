package org.eclipse.jetty.mongodb.jmx;

import org.eclipse.jetty.mongodb.MongoSessionManager;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.session.jmx.AbstractSessionManagerMBean;

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
