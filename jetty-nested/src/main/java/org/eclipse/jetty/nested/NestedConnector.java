//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.nested;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;

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
        setForwarded(true);
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
        HttpServletRequest outerServletRequest = (HttpServletRequest)outerRequest;
        HttpServletResponse outerServletResponse = (HttpServletResponse)outerResponse;
        NestedConnection connection=new NestedConnection(this,new NestedEndPoint(outerServletRequest,outerServletResponse),outerServletRequest,outerServletResponse,_serverInfo);
        connection.service();
    }

}
