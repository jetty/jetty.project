//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.test.support.rawhttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * An HTTPS Socket Impl
 */
public class HttpsSocketImpl implements HttpSocket
{
    private static final Logger LOG = Log.getLogger(HttpsSocketImpl.class);

    private SSLContext sslContext;
    private SSLSocketFactory sslfactory;

    public HttpsSocketImpl() throws Exception
    {
        @SuppressWarnings("unused")
        HostnameVerifier hostnameVerifier = new HostnameVerifier()
        {
            @Override
            public boolean verify(String urlHostName, SSLSession session)
            {
                LOG.warn("Warning: URL Host: " + urlHostName + " vs." + session.getPeerHost());
                return true;
            }
        };

        // Install the all-trusting trust manager
        try
        {
            // TODO real trust manager
            this.sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());
        }
        catch (Exception e)
        {
            throw new IOException("issue ignoring certs");
        }

        sslfactory = sslContext.getSocketFactory();
    }

    @Override
    public Socket connect(InetAddress host, int port) throws IOException
    {
        SSLSocket sslsock = (SSLSocket)sslfactory.createSocket();
        sslsock.setEnabledProtocols(new String[]{"TLSv1"});
        SocketAddress address = new InetSocketAddress(host, port);
        sslsock.connect(address);
        return sslsock;
    }
}
