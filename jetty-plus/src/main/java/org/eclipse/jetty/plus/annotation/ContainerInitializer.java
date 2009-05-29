package org.eclipse.jetty.plus.annotation;

public class ContainerInitializer
{
    protected Class _target;
    protected Class[] _interestedTypes;

    
    public void setTarget (Class target)
    {
        _target = target;
    }
    
    public Class getTarget ()
    {
        return _target;
    }

    public Class[] getInterestedTypes ()
    {
        return _interestedTypes;
    }
    
    public void setInterestedTypes (Class[] interestedTypes)
    {
        _interestedTypes = interestedTypes;
    }
}
