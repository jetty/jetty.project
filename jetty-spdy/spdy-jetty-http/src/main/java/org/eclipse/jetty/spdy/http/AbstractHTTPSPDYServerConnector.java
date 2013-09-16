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

package org.eclipse.jetty.spdy.http;

import java.io.IOException;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class AbstractHTTPSPDYServerConnector extends SPDYServerConnector
{
    public AbstractHTTPSPDYServerConnector(ServerSessionFrameListener listener, SslContextFactory sslContextFactory)
    {
        super(listener, sslContextFactory);
    }

    @Override
    public void customize(EndPoint endPoint, Request request) throws IOException
    {
        super.customize(endPoint, request);
        if (getSslContextFactory() != null)
            request.setScheme(HttpSchemes.HTTPS);
    }

    @Override
    public boolean isConfidential(Request request)
    {
        if (getSslContextFactory() != null)
        {
            int confidentialPort = getConfidentialPort();
            return confidentialPort == 0 || confidentialPort == request.getServerPort();
        }
        return super.isConfidential(request);
    }

    @Override
    public boolean isIntegral(Request request)
    {
        if (getSslContextFactory() != null)
        {
            int integralPort = getIntegralPort();
            return integralPort == 0 || integralPort == request.getServerPort();
        }
        return super.isIntegral(request);
    }
}
