// ========================================================================
// Copyright (c) 2010-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.nested;

import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;


public class NestedConnection extends HttpConnection
{
    protected NestedConnection(final NestedConnector connector, final NestedEndPoint endp, final HttpServletRequest request, HttpServletResponse response,String nestedIn)
        throws IOException
    {
        super(connector,
              endp,
              connector.getServer(),
              new NestedParser(),
              new NestedGenerator(connector.getResponseBuffers(),endp,response,nestedIn),
              new NestedRequest());

        ((NestedRequest)_request).setConnection(this);
        
        // Set the request line
        _request.setScheme(request.getScheme());
        _request.setMethod(request.getMethod());
        String uri=request.getQueryString()==null?request.getRequestURI():(request.getRequestURI()+"?"+request.getQueryString());
        _request.setUri(new HttpURI(uri));
        _request.setPathInfo(request.getRequestURI());
        _request.setQueryString(request.getQueryString());
        _request.setProtocol(request.getProtocol());
        
        // Set the headers
        HttpFields fields = getRequestFields();
        for (Enumeration<String> e=request.getHeaderNames();e.hasMoreElements();)
        {
            String header=e.nextElement();
            String value=request.getHeader(header);
            fields.add(header,value);
        }
        
        _request.setCookies(request.getCookies());
        
        // System.err.println(_request.getMethod()+" "+_request.getUri()+" "+_request.getProtocol());
        // System.err.println(fields.toString());
    }

    void service() throws IOException, ServletException
    {
        setCurrentConnection(this);
        try
        {
            getServer().handle(this);
            completeResponse();
            _generator.flushBuffer();
            _endp.flush();
        }
        finally
        {
            setCurrentConnection(null);
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.HttpConnection#getInputStream()
     */
    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        return ((NestedEndPoint)_endp).getServletInputStream();
    }

    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.HttpConnection#handle()
     */
    @Override
    public Connection handle() throws IOException
    {
        throw new IllegalStateException();
    }

}
