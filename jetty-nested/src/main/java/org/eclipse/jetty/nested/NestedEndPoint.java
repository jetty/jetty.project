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
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.bio.StreamEndPoint;

public class NestedEndPoint extends StreamEndPoint
{
    private final HttpServletRequest _outerRequest;

    public NestedEndPoint(HttpServletRequest outerRequest, HttpServletResponse outerResponse)
        throws IOException
    {
        super(outerRequest.getInputStream(),outerResponse.getOutputStream());
        _outerRequest=outerRequest;
    }

    public ServletInputStream getServletInputStream()
    {
        return (ServletInputStream)getInputStream();
    }
    @Override
    public String getLocalAddr()
    {
        return _outerRequest.getLocalAddr();
    }

    @Override
    public String getLocalHost()
    {
        return _outerRequest.getLocalName();
    }

    @Override
    public int getLocalPort()
    {
        return _outerRequest.getLocalPort();
    }

    @Override
    public String getRemoteAddr()
    {
        return _outerRequest.getRemoteAddr();
    }

    @Override
    public String getRemoteHost()
    {
        return _outerRequest.getRemoteHost();
    }
    @Override
    public int getRemotePort()
    {
        return _outerRequest.getRemotePort();
    }
}
