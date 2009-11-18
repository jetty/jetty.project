/*
 * Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.jetty.server.ssl;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;

/**
 * @version $Revision$ $Date$
 */
public class SslUploadTest extends TestCase
{
    int _total;
    
    public void test() throws Exception
    {
        Server server = new Server();
        SslConnector connector = new SslSelectChannelConnector();
        server.addConnector(connector);

        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        connector.setKeystore(keystorePath);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");
        connector.setTruststore(keystorePath);
        connector.setTrustPassword("storepwd");

        server.setHandler(new EmptyHandler());

        server.start();
        try
        {
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(new FileInputStream(keystorePath), "storepwd".toCharArray());
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keystore);
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

            _total=0;
            final SSLSocket socket =  (SSLSocket)sslContext.getSocketFactory().createSocket("localhost",connector.getLocalPort());

            // Simulate async close
            /*
            new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        sleep(100);
                        socket.close();
                    }
                    catch (IOException x)
                    {
                        x.printStackTrace();
                    }
                    catch (InterruptedException x)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }.start();
            */

            
            
            long start = System.nanoTime();
            OutputStream out = socket.getOutputStream();
            out.write("POST / HTTP/1.1\r\n".getBytes());
            out.write("Host: localhost\r\n".getBytes());
            out.write("Content-Length: 16777216\r\n".getBytes());
            out.write("Content-Type: bytes\r\n".getBytes());
            out.write("Connection: close\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.flush();

            byte[] requestContent = new byte[16777216];
            Arrays.fill(requestContent, (byte)120);
            out.write(requestContent);
            out.flush();
            
            InputStream in = socket.getInputStream();
            String response = IO.toString(in);
            // System.err.println(response);

            long end = System.nanoTime();
            System.out.println("upload time: " + TimeUnit.NANOSECONDS.toMillis(end - start));
            assertEquals(requestContent.length,_total);
            
        }
        finally
        {
            server.stop();
        }
    }

    private class EmptyHandler extends AbstractHandler
    {
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            // System.out.println("path = " + path);
            request.setHandled(true);
            
            InputStream in = request.getInputStream();
            byte[] b = new byte[4096*4];
            int l;
            
            while((l=in.read(b))>=0)
            {
                // System.out.println("Read "+l);
                _total+=l;
            }
            System.err.println("Read "+_total);
            
        }
    }

    private class EmptyHostnameVerifier implements HostnameVerifier
    {
        public boolean verify(String s, SSLSession sslSession)
        {
            return true;
        }
    }
}
