//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.ssl;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.HttpServerTestBase;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * HttpServer Tester.
 */
public class SelectChannelServerSslTest extends HttpServerTestBase
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

    @Override
    public void testFullMethod() throws Exception
    {
        // Don't run on Windows (buggy JVM)
        Assume.assumeTrue(!OS.IS_WINDOWS);
        
        try
        {
            super.testFullMethod();
        }
        catch (SocketException e)
        {
            Log.getLogger(SslConnection.class).warn("Close overtook 400 response");
        }
    }

    @Override
    public void testFullURI() throws Exception
    {
        // Don't run on Windows (buggy JVM)
        Assume.assumeTrue(!OS.IS_WINDOWS);
        try
        {
            super.testFullURI();
        }
        catch (SocketException e)
        {
            Log.getLogger(SslConnection.class).warn("Close overtook 400 response");
        }
    }

    @Override
    public void testFullHeader() throws Exception
    {
        try
        {
            super.testFullHeader();
        }
        catch (SocketException e)
        {
            Log.getLogger(SslConnection.class).warn("Close overtook 400 response");
        }
    }

    @Before
    public void init() throws Exception
    {
        String keystorePath = System.getProperty("basedir",".") + "/src/test/resources/keystore";
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(keystorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        sslContextFactory.setTrustStorePath(keystorePath);
        sslContextFactory.setTrustStorePassword("storepwd");
        ByteBufferPool pool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());
        
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
        ServerConnector connector = new ServerConnector(_server,(Executor)null,(Scheduler)null,pool, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory,httpConnectionFactory));
        SecureRequestCustomizer secureRequestCustomer = new SecureRequestCustomizer();
        secureRequestCustomer.setSslSessionAttribute("SSL_SESSION");
        httpConnectionFactory.getHttpConfiguration().addCustomizer(secureRequestCustomer);
        
        startServer(connector);

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream stream = sslContextFactory.getKeyStoreResource().getInputStream())
        {
            keystore.load(stream, "storepwd".toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        __sslContext = SSLContext.getInstance("TLS");
        __sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        try
        {
            HttpsURLConnection.setDefaultHostnameVerifier(__hostnameverifier);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        }
        catch(Exception e)
        {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void testBlockingWhileReadingRequestContent() throws Exception
    {
        super.testBlockingWhileReadingRequestContent();
    }

    @Override
    public void testBlockingWhileWritingResponseContent() throws Exception
    {
        super.testBlockingWhileWritingResponseContent();
    }

    @Test
    public void testRequest2FixedFragments() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes=REQUEST2.getBytes();
        int[] points=new int[]{74,325};

        // Sort the list
        Arrays.sort(points);

        URI uri=_server.getURI();
        Socket client=newSocket(uri.getHost(),uri.getPort());
        try
        {
            OutputStream os=client.getOutputStream();

            int last=0;

            // Write out the fragments
            for (int j=0; j<points.length; ++j)
            {
                int point=points[j];
                os.write(bytes,last,point-last);
                last=point;
                os.flush();
                Thread.sleep(PAUSE);

            }

            // Write the last fragment
            os.write(bytes,last,bytes.length-last);
            os.flush();
            Thread.sleep(PAUSE);


            // Read the response
            String response=readResponse(client);

            // Check the response
            assertEquals(RESPONSE2,response);
        }
        finally
        {
            client.close();
        }
    }

    @Override
    @Test
    @Ignore("Override and ignore this test as SSLSocket.shutdownOutput() is not supported, " +
            "but shutdownOutput() is needed by the test.")
    public void testInterruptedRequest(){}

    @Override
    @Ignore
    public void testAvailable() throws Exception
    {
    }

    @Test
    public void testSecureRequestCustomizer() throws Exception
    {
        configureServer(new SecureRequestHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            os.write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            System.err.println(response);
            
            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello world"));
            assertThat(response, containsString("scheme='https'"));
            assertThat(response, containsString("isSecure='true'"));
            assertThat(response, containsString("X509Certificate='null'"));

            Matcher matcher=Pattern.compile("cipher_suite='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(isEmptyOrNullString()),not(is("null"))));
           
            matcher=Pattern.compile("key_size='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(isEmptyOrNullString()),not(is("null"))));
            
            matcher=Pattern.compile("ssl_session_id='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(isEmptyOrNullString()),not(is("null"))));
            
            matcher=Pattern.compile("ssl_session='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(isEmptyOrNullString()),not(is("null"))));
        }
    }

    public static class SecureRequestHandler extends AbstractHandler
    {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().println("Hello world");
            response.getOutputStream().println("scheme='"+request.getScheme()+"'");
            response.getOutputStream().println("isSecure='"+request.isSecure()+"'");
            response.getOutputStream().println("X509Certificate='"+request.getAttribute("javax.servlet.request.X509Certificate")+"'");
            response.getOutputStream().println("cipher_suite='"+request.getAttribute("javax.servlet.request.cipher_suite")+"'");
            response.getOutputStream().println("key_size='"+request.getAttribute("javax.servlet.request.key_size")+"'");
            response.getOutputStream().println("ssl_session_id='"+request.getAttribute("javax.servlet.request.ssl_session_id")+"'");
            SSLSession sslSession=(SSLSession)request.getAttribute("SSL_SESSION");
            response.getOutputStream().println("ssl_session='"+sslSession+"'");
            
        }
        
    }
    
}
