package org.eclipse.jetty.server.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("MBean Wrapper for Connectors")
public class AbstractConnectorMBean extends ObjectMBean
{
    final AbstractConnector _connector;
    public AbstractConnectorMBean(Object managedObject)
    {
        super(managedObject);
        _connector=(AbstractConnector)managedObject;
    }
    @Override
    public String getObjectContextBasis()
    {
        return String.format("%s@%x",_connector.getDefaultProtocol(),_connector.hashCode());
    }
    
    
}
