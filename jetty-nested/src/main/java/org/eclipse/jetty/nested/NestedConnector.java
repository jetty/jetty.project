package org.eclipse.jetty.nested;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;

/**
 * Nested Jetty Connector
 * <p>
 * This Jetty {@link Connector} allows a jetty instance to be nested inside another servlet container.
 * Requests received by the outer servlet container should be passed to jetty using the {@link #service(ServletRequest, ServletResponse)} method of this connector. 
 *
 */
public class NestedConnector extends AbstractConnector
{
    String _serverInfo;
    
    public NestedConnector()
    {
        setAcceptors(0);
    }
    
    public void open() throws IOException
    {
    }

    public void close() throws IOException
    {
    }

    public int getLocalPort()
    {
        return -1;
    }

    public Object getConnection()
    {
        return null;
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        throw new IllegalStateException();
    }
    
    /**
     * Service a request of the outer servlet container by passing it to the nested instance of Jetty.
     * @param outerRequest
     * @param outerResponse
     * @throws IOException
     * @throws ServletException
     */
    public void service(ServletRequest outerRequest, ServletResponse outerResponse) throws IOException, ServletException
    {
        HttpServletRequest request = (HttpServletRequest)outerRequest;
        HttpServletResponse response = (HttpServletResponse)outerResponse;
        NestedConnection connection=new NestedConnection(this,new NestedEndPoint(request,response),request,response,_serverInfo);
        connection.service();
    }

}
