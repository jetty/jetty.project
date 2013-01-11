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
import java.util.Enumeration;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractHttpConnection;


public class NestedConnection extends AbstractHttpConnection
{
    protected NestedConnection(final NestedConnector connector, final NestedEndPoint endp, final HttpServletRequest outerRequest, HttpServletResponse outerResponse,String nestedIn)
        throws IOException
    {
        super(connector,
              endp,
              connector.getServer(),
              new NestedParser(),
              new NestedGenerator(connector.getResponseBuffers(),endp,outerResponse,nestedIn),
              new NestedRequest(outerRequest));

        ((NestedRequest)_request).setConnection(this);
        
        // Set the request line
        _request.setDispatcherType(DispatcherType.REQUEST);
        _request.setScheme(outerRequest.getScheme());
        _request.setMethod(outerRequest.getMethod());
        String uri=outerRequest.getQueryString()==null?outerRequest.getRequestURI():(outerRequest.getRequestURI()+"?"+outerRequest.getQueryString());
        _request.setUri(new HttpURI(uri));
        _request.setPathInfo(outerRequest.getRequestURI());
        _request.setQueryString(outerRequest.getQueryString());
        _request.setProtocol(outerRequest.getProtocol());
        
        // Set the headers
        HttpFields fields = getRequestFields();
        for (Enumeration<String> e=outerRequest.getHeaderNames();e.hasMoreElements();)
        {
            String header=e.nextElement();
            String value=outerRequest.getHeader(header);
            fields.add(header,value);
        }
        
        // Let outer parse the cookies
        _request.setCookies(outerRequest.getCookies());
        
        // copy request attributes
        for (Enumeration<String> e=outerRequest.getAttributeNames();e.hasMoreElements();)
        {
            String attr=e.nextElement();
            _request.setAttribute(attr,outerRequest.getAttribute(attr));
        }
        
        // customize the request
        connector.customize(endp,_request);
        
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
            while (!_generator.isComplete() && _endp.isOpen())
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
