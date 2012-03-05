// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.ssl;
import java.io.FileInputStream;
import java.net.Socket;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.server.HttpServerTestBase;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * HttpServer Tester.
 */
public class SslSocketServerTest extends HttpServerTestBase
{
    static SSLContext __sslContext;
    {
        _scheme="https";
    }
    
    @Override
    protected Socket newSocket(String host, int port) throws Exception
    {
        SSLSocket socket = (SSLSocket)__sslContext.getSocketFactory().createSocket(host,port);
        socket.setEnabledProtocols(new String[] {"TLSv1"});
        return socket;
    }
    

    @BeforeClass
    public static void init() throws Exception
    {   
        SslSocketConnector connector = new SslSocketConnector();
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keystorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStore(keystorePath);
        cf.setTrustStorePassword("storepwd");
        startServer(connector);
        

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        keystore.load(new FileInputStream(connector.getKeystore()), "storepwd".toCharArray());
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        __sslContext = SSLContext.getInstance("TLSv1");
        __sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        

    }

    @Override
    @Test
    public void testFlush() throws Exception
    {
        // TODO this test uses URL, so noop for now
    }


    @Override
    @Ignore
    public void testAvailable() throws Exception
    {
    }
}
