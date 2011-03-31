package org.eclipse.jetty.nested;


import org.eclipse.jetty.server.Request;

public class NestedRequest extends Request
{
    public NestedRequest()
    {
    }
    
    void setConnection(NestedConnection connection)
    {
        super.setConnection(connection);
    }
}
