package com.acme.jmx;

import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.acme.Derived;

@ManagedObject("Derived MBean Wrapper")
public class DerivedMBean extends ObjectMBean
{
    private static final Logger LOG = Log.getLogger(DerivedMBean.class);
    
    public DerivedMBean(Object managedObject)
    {
        super(managedObject);
    }
    
    @ManagedOperation("test of proxy operations")
    public String good()
    {
        return "not " + ((Derived)_managed).bad();
    }
 
    @ManagedAttribute(value="test of proxy attributes", proxied=true)
    public String goop()
    {
        return "goop";
    }
    
}
