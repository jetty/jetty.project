package org.eclipse.jetty.policy.component;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public abstract class AbstractNode
{
    private boolean isDirty = false;
    private boolean isExpanded = false;
    
    public abstract void expand( PolicyContext context ) throws PolicyException;

    public boolean isDirty()
    {
        return isDirty;
    }

    public void setDirty( boolean isDirty )
    {
        this.isDirty = isDirty;
    }

    public boolean isExpanded()
    {
        return isExpanded;
    }

    public void setExpanded( boolean isExpanded )
    {
        this.isExpanded = isExpanded;
    }
    
    
}
