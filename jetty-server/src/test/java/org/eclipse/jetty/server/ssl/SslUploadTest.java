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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * @version $Revision$ $Date$
 */
public class SslUploadTest extends TestCase
{
    public void test() throws Exception
    {
        Server server = new Server();
        SslConnector connector = new SslSelectChannelConnector();
        server.addConnector(connector);

        String keystorePath = System.getProperty("basedir") + "/src/test/resources/keystore";
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

            URL url = new URL("https://localhost:" + connector.getLocalPort() + "/");
            final HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setHostnameVerifier(new EmptyHostnameVerifier());
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            connection.connect();

            // 64 MiB
//            byte[] requestContent = new byte[67108864];
            // 16 MiB
            byte[] requestContent = new byte[16777216];
            Arrays.fill(requestContent, (byte)120);

            OutputStream output = connection.getOutputStream();
            output.write(requestContent);
            output.flush();

/*
            // Simulate async close
            new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        sleep(200);
                        connection.disconnect();
                    }
                    catch (InterruptedException x)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
            }.start();
*/

            long start = System.nanoTime();
            InputStream input = connection.getInputStream();
            long end = System.nanoTime();
            System.out.println("upload time: " + TimeUnit.NANOSECONDS.toMillis(end - start));

            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null)
                System.err.println(line);

            connection.disconnect();
        }
        finally
        {
            server.stop();
        }
    }

    private static class EmptyHandler extends AbstractHandler
    {
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            System.out.println("path = " + path);
            request.setHandled(true);
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
