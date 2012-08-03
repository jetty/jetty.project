package com.acme.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

import com.acme.Derived;

@ManagedObject("Managed MBean Wrapper")
public class ManagedMBean extends ObjectMBean
{
    public ManagedMBean(Object managedObject)
    {
        super(managedObject);
    }
    
    @ManagedOperation(value="test of proxy operations", managed=true)
    public String good()
    {
        return "not " + ((Derived)_managed).bad();
    }
 
    @ManagedAttribute(value="test of proxy attributes", getter="goop", proxied=true)
    public String goop()
    {
        return "goop";
    }
}
