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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.server.HttpServerTestBase;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * HttpServer Tester.
 */
public class SslSelectChannelServerTest extends HttpServerTestBase
{
    static SSLContext __sslContext;
    {
        _scheme="https";
    }
    
    @Override
    protected Socket newSocket(String host, int port) throws Exception
    {
        return __sslContext.getSocketFactory().createSocket(host,port);
    }
    

    @BeforeClass
    public static void init() throws Exception
    {   
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStore(keystorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        cf.setTrustStore(keystorePath);
        cf.setTrustStorePassword("storepwd");
        connector.setUseDirectBuffers(true);
        startServer(connector);
        

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        keystore.load(new FileInputStream(connector.getKeystore()), "storepwd".toCharArray());
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        __sslContext = SSLContext.getInstance("TLS");
        __sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        

        try
        {
            HttpsURLConnection.setDefaultHostnameVerifier(__hostnameverifier);
            SSLContext sc = SSLContext.getInstance("TLS"); 
            sc.init(null, __trustAllCerts, new java.security.SecureRandom()); 
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        }
        catch(Exception e)
        {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        
    }

    @Test
    @Override
    public void testBlockingWhileWritingResponseContent() throws Exception
    {
        super.testBlockingWhileWritingResponseContent();
    }


    @Test
    @Override
    public void testBigBlocks() throws Exception
    {
        super.testBigBlocks();
    }
    
    
}
