package com.acme;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject(value="Managed Object", wrapper="com.acme.jmx.ManagedMBean")
public class Managed
{
    String managed = "foo";
    
    @ManagedAttribute("Managed Attribute")
    public String getManaged()
    {
        return managed;
    }

    public void setManaged(String managed)
    {
        this.managed = managed;
    }
       
    
    public String bad()
    {
        return "bad";
    }
    
}
