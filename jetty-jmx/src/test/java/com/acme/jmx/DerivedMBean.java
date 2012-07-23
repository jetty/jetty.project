package com.acme.jmx;

import org.eclipse.jetty.util.annotation.Managed;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.acme.Derived;

@Managed("Derived MBean")
public class DerivedMBean
{
    private static final Logger LOG = Log.getLogger(DerivedMBean.class);

    Derived managedObject;
    
    public DerivedMBean(Object managedObject)
    {
        this.managedObject = (Derived)managedObject;
    }
    
    @Managed(value="test of proxy", attribute=true, managed=true, getter="good" )
    public String good()
    {
        return "not " + managedObject.bad();
    }
    
}
