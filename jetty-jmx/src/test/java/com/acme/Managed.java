package com.acme;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject(value="Managed Object", wrapper="com.acme.jmx.ManagedMBean")
public class Managed
{
    @ManagedAttribute("Managed Attribute")
    String managed = "foo";
    
    public String getManaged()
    {
        return managed;
    }

    public void setManaged(String managed)
    {
        this.managed = managed;
    }
       
}
