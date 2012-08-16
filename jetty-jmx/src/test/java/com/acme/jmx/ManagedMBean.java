package com.acme.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

import com.acme.Derived;
import com.acme.Managed;

@ManagedObject("Managed MBean Wrapper")
public class ManagedMBean extends ObjectMBean
{
    public ManagedMBean(Object managedObject)
    {
        super(managedObject);
    }
    
    @ManagedOperation("test of proxy operations")
    public String good()
    {
        return "not managed " + ((Managed)_managed).bad();
    }
 
    @ManagedAttribute(value="test of proxy attributes", proxied=true)
    public String goop()
    {
        return "goop";
    }
}
