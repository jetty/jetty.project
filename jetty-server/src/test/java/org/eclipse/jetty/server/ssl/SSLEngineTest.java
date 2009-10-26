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

// JettyTest.java --
//
// Junit test that shows the Jetty SSL bug.
//

package org.eclipse.jetty.server.ssl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * HttpServer Tester.
 */
public class SSLEngineTest extends TestCase
{
    // ~ Static fields/initializers
    // ---------------------------------------------

    // Useful constants
    private static final String HELLO_WORLD="Hello world. The quick brown fox jumped over the lazy dog. How now brown cow. The rain in spain falls mainly on the plain.\n";
    private static final String JETTY_VERSION=Server.getVersion();
    private static final String PROTOCOL_VERSION="2.0";

    /** The request. */
    private static final String REQUEST0_HEADER="POST /r0 HTTP/1.1\n"+"Host: localhost\n"+"Content-Type: text/xml\n"+"Content-Length: ";
    private static final String REQUEST1_HEADER="POST /r1 HTTP/1.1\n"+"Host: localhost\n"+"Content-Type: text/xml\n"+"Connection: close\n"+"Content-Length: ";
    private static final String REQUEST_CONTENT="<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
            +"<requests xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+"        xsi:noNamespaceSchemaLocation=\"commander.xsd\" version=\""
            +PROTOCOL_VERSION+"\">\n"+"</requests>";
    
    private static final String REQUEST0=REQUEST0_HEADER+REQUEST_CONTENT.getBytes().length+"\n\n"+REQUEST_CONTENT;
    private static final String REQUEST1=REQUEST1_HEADER+REQUEST_CONTENT.getBytes().length+"\n\n"+REQUEST_CONTENT;

    /** The expected response. */
    private static final String RESPONSE0="HTTP/1.1 200 OK\n"+"Content-Length: "+HELLO_WORLD.length()+"\n"+"Server: Jetty("+JETTY_VERSION+")\n"+'\n'+HELLO_WORLD;
    private static final String RESPONSE1="HTTP/1.1 200 OK\n"+"Connection: close\n"+"Server: Jetty("+JETTY_VERSION+")\n"+'\n'+HELLO_WORLD;

    private static final TrustManager[] s_dummyTrustManagers=new TrustManager[]
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

    Server server;
    SslSelectChannelConnector connector;
    
    // ~ Methods
    // ----------------------------------------------------------------

    @Override
    public void setUp() throws Exception
    {
        super.setUp();

        server=new Server();
        connector=new SslSelectChannelConnector();

        String keystore = System.getProperty("user.dir")+File.separator+"src"+File.separator+"test"+File.separator+"resources"+File.separator+"keystore";
        
        connector.setPort(0);
        connector.setKeystore(keystore);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");

        server.setConnectors(new Connector[]
        { connector });
        server.setHandler(new HelloWorldHandler());
        server.start();
        Thread.sleep(100);
    }
    
    
    @Override
    public void tearDown() throws Exception
    {
        Thread.sleep(2000);
        server.stop();
        super.tearDown();
    }
    
    public void testNothing() throws Exception
    {
    	
    }
    
    /**
     * Feed the server the entire request at once.
     * 
     * @throws Exception
     */
    public void /*test*/RequestJettyHttps() throws Exception
    {
        final int loops=100;
        final int numConns=100;

        Socket[] client=new Socket[numConns];

        SSLContext ctx=SSLContext.getInstance("SSLv3");
        ctx.init(null,s_dummyTrustManagers,new java.security.SecureRandom());

        int port=connector.getLocalPort();

        try
        {
            for (int l=0;l<loops;l++)
            {
                System.err.print('.');
                try
                {
                    for (int i=0; i<numConns; ++i)
                    {
                        // System.err.println("write:"+i);
                        client[i]=ctx.getSocketFactory().createSocket("localhost",port);
                        OutputStream os=client[i].getOutputStream();

                        os.write(REQUEST0.getBytes());
                        os.write(REQUEST0.getBytes());
                        os.flush();
                    }

                    for (int i=0; i<numConns; ++i)
                    {
                        // System.err.println("flush:"+i);
                        OutputStream os=client[i].getOutputStream();
                        os.write(REQUEST1.getBytes());
                        os.flush();
                    }

                    for (int i=0; i<numConns; ++i)
                    {
                        // System.err.println("read:"+i);
                        // Read the response.
                        String responses=readResponse(client[i]);
                        // Check the response
                        assertEquals(String.format("responses %d %d",l,i),RESPONSE0+RESPONSE0+RESPONSE1,responses);
                    }
                }
                finally
                {
                    for (int i=0; i<numConns; ++i)
                    {
                        if (client[i]!=null)
                        {
                            client[i].close();
                        }
                    }


                }
            }
        }
        finally
        {
            System.err.println();
        }
    }
    
    /**
     * Read entire response from the client. Close the output.
     * 
     * @param client
     *                Open client socket.
     * 
     * @return The response string.
     * 
     * @throws IOException
     */
    private static String readResponse(Socket client) throws IOException
    {
        BufferedReader br=null;
        StringBuilder sb=new StringBuilder(1000);
        
        try
        {
            client.setSoTimeout(5000);
            br=new BufferedReader(new InputStreamReader(client.getInputStream()));

            String line;

            while ((line=br.readLine())!=null)
            {
                sb.append(line);
                sb.append('\n');
            }
        }
        catch(SocketTimeoutException e)
        {
            System.err.println("Test timedout: "+e.toString());
            e.printStackTrace(); // added to see if we can get more info from failures on CI
        }
        finally
        {
            if (br!=null)
            {
                br.close();
            }
        }
        return sb.toString();
    }

    private static class HelloWorldHandler extends AbstractHandler
    {
        // ~ Methods
        // ------------------------------------------------------------

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            // System.err.println("HANDLE "+request.getRequestURI());
            String ssl_id = (String)request.getAttribute("javax.servlet.request.ssl_session_id");
            //assertNotNull(ssl_id);
            PrintWriter out=response.getWriter();

            try
            {
                out.print(HELLO_WORLD);
            }
            finally
            {
                out.close();
            }
        }
    }
    
    
    public final static int BODY_SIZE=300;
    
    public void testServletPost() throws Exception
    {
        Server server=new Server();
        SslSelectChannelConnector connector=new SslSelectChannelConnector();

        String keystore = System.getProperty("user.dir")+File.separator+"src"+File.separator+"test"+File.separator+"resources"+File.separator+"keystore";
        
        connector.setPort(0);
        connector.setKeystore(keystore);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");
        connector.setTruststore(keystore);
        connector.setTrustPassword("storepwd");

        server.setConnectors(new Connector[]
        { connector });

        StreamHandler handler = new StreamHandler();
        server.setHandler(handler);
        
        try
        {
            SSLContext context = SSLContext.getInstance("SSL");
            context.init(null,s_dummyTrustManagers,new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());

            server.start();

            URL url = new URL("https://localhost:"+connector.getLocalPort()+"/test");

            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            if (conn instanceof HttpsURLConnection)
            {
                ((HttpsURLConnection)conn).setHostnameVerifier(new HostnameVerifier()
                {
                    public boolean verify(String urlHostName, SSLSession session)
                    {
                        return true;
                    }
                });
            }

            conn.setConnectTimeout(10000);
            conn.setReadTimeout(100000);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type","text/plain"); //$NON-NLS-1$
            conn.setChunkedStreamingMode(128);
            conn.connect();
            byte[] b = new byte[BODY_SIZE];
            for (int i = 0; i < BODY_SIZE; i++)
            {
                b[i] = 'x';
            }
            OutputStream os = conn.getOutputStream();
            os.write(b);
            os.flush();
            int rc = conn.getResponseCode();

            int len = 0;
            InputStream is = conn.getInputStream();
            int bytes=0;
            while ((len = is.read(b)) > -1)
                bytes+=len;
            is.close();

            assertEquals(BODY_SIZE,handler.bytes);
            assertEquals(BODY_SIZE,bytes);
            
        }
        finally
        {
            server.stop();
        }
    }

    public static class StreamHandler extends AbstractHandler
    {
        public int bytes=0;
        
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.setBufferSize(128);
            byte[] b = new byte[BODY_SIZE];
            int len = 0;
            InputStream is = request.getInputStream();

            while ((len = is.read(b)) > -1)
            {
                bytes+=len;
            }

            OutputStream os = response.getOutputStream();
            for (int i = 0; i < BODY_SIZE; i++)
            {
                b[i] = 'x';
            }
            os.write(b);
            response.flushBuffer();
        }
    }
}
