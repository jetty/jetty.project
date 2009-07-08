// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test.support.rawhttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.util.log.Log;

/**
 * An HTTPS Socket Impl
 */
public class HttpsSocketImpl implements HttpSocket
{
    private SSLContext sslContext;
    private SSLSocketFactory sslfactory;

    public HttpsSocketImpl() throws Exception
    {
        // Create loose SSL context.
        // Create a trust manager that does not validate certificate
        // chains
        TrustManager[] trustAllCerts = new TrustManager[]
        { new X509TrustManager()
        {
            public java.security.cert.X509Certificate[] getAcceptedIssuers()
            {
                return null;
            }

            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType)
            {
            }

            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType)
            {
            }
        } };

        HostnameVerifier hostnameVerifier = new HostnameVerifier()
        {
            public boolean verify(String urlHostName, SSLSession session)
            {
                Log.warn("Warning: URL Host: " + urlHostName + " vs." + session.getPeerHost());
                return true;
            }
        };

        // Install the all-trusting trust manager
        try
        {
            // TODO real trust manager
            this.sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null,trustAllCerts,new java.security.SecureRandom());
        }
        catch (Exception e)
        {
            throw new IOException("issue ignoring certs");
        }

        sslfactory = sslContext.getSocketFactory();
    }

    public Socket connect(InetAddress host, int port) throws IOException
    {
        Socket sslsock = sslfactory.createSocket();
        SocketAddress address = new InetSocketAddress(host,port);
        sslsock.connect(address);
        return sslsock;
    }
}
