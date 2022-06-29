//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.test.support.rawhttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTTPS Socket Impl
 */
public class HttpsSocketImpl implements HttpSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpsSocketImpl.class);

    private SSLContext sslContext;
    private SSLSocketFactory sslfactory;

    public HttpsSocketImpl() throws Exception
    {
        try
        {
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
        SocketAddress address = new InetSocketAddress(host, port);
        sslsock.connect(address);
        return sslsock;
    }
}
