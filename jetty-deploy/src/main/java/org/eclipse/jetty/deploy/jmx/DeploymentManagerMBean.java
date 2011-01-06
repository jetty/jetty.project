package org.eclipse.jetty.deploy.jmx;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.jmx.ObjectMBean;

public class DeploymentManagerMBean extends ObjectMBean
{
    private final DeploymentManager _manager;
    
    public DeploymentManagerMBean(Object managedObject)
    {
        super(managedObject);
        _manager=(DeploymentManager)managedObject;
    }
    
    

}
