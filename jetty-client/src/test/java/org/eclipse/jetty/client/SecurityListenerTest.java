// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.client.security.Realm;
import org.eclipse.jetty.client.security.SimpleRealmResolver;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

/**
 * Functional testing for HttpExchange.
 *
 * 
 * 
 */
public class SecurityListenerTest extends TestCase
{
   
    private Server _server;
    private int _port;
    private HttpClient _httpClient;

    private Realm _jettyRealm;
    private static final String APP_CONTEXT = "localhost /";

    @Override
    protected void setUp() throws Exception
    {
        startServer();
        _httpClient=new HttpClient();
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(2);
        _httpClient.start();
        
        _jettyRealm = new Realm()
        {
            public String getId()
            {
                return "MyRealm";
            }

            public String getPrincipal()
            {
                return "jetty";
            }

            public String getCredentials()
            {
                return "jetty";
            }
        };

        _httpClient.setRealmResolver( new SimpleRealmResolver(_jettyRealm) );
    }

    @Override
    protected void tearDown() throws Exception
    {
        stopServer();
        _httpClient.stop();
    }

    public void xtestPerf() throws Exception
    {
        sender(1);
        Thread.sleep(200);
        sender(10);
        Thread.sleep(200);
        sender(100);
        Thread.sleep(200);
        sender(1000);
        Thread.sleep(200);
        sender(10000);
    }

    public void sender(final int nb) throws Exception
    {
        final CountDownLatch latch=new CountDownLatch(nb);
        long l0=System.currentTimeMillis();
        for (int i=0; i<nb; i++)
        {
            final int n=i;
            if (n%1000==0)
            {
                Thread.sleep(200);
            }

            HttpExchange httpExchange=new HttpExchange()
            {
                @Override
                protected void onRequestCommitted()
                {
                    // System.err.println("Request committed");
                }

                @Override
                protected void onResponseStatus(Buffer version, int status, Buffer reason)
                {
                    // System.err.println("Response Status: " + version+" "+status+" "+reason);
                }

                @Override
                protected void onResponseHeader(Buffer name, Buffer value)
                {
                    // System.err.println("Response header: " + name + " = " + value);
                }

                @Override
                protected void onResponseContent(Buffer content)
                {
                    // System.err.println("Response content:" + content);
                }

                @Override
                protected void onResponseComplete()
                {
                    // System.err.println("Response completed "+n);
                    latch.countDown();
                }

            };

            httpExchange.setURL("http://localhost:"+_port+"/");
            httpExchange.addRequestHeader("arbitrary","value");

            _httpClient.send(httpExchange);
        }

        long last=latch.getCount();
        while(last>0)
        {
            // System.err.println("waiting for "+last+" sent "+(System.currentTimeMillis()-l0)/1000 + "s ago ...");
            latch.await(5,TimeUnit.SECONDS);
            long next=latch.getCount();
            if (last==next)
                break;
            last=next;
        }
        // System.err.println("missed "+latch.getCount()+" sent "+(System.currentTimeMillis()-l0)/1000 + "s ago.");
        assertEquals(0,latch.getCount());
        long l1=System.currentTimeMillis();
    }

    //TODO jaspi hangs ???
//    public void testGetWithContentExchange() throws Exception
//    {
//        int i = 1;
//
//        final CyclicBarrier barrier = new CyclicBarrier(2);
//        ContentExchange httpExchange = new ContentExchange()
//        {
//            protected void onResponseComplete() throws IOException
//            {
//                super.onResponseComplete();
//                try{barrier.await();}catch(Exception e){}
//            }
//        };
//        httpExchange.setURL("http://localhost:" + _port + "/?i=" + i);
//        httpExchange.setMethod(HttpMethods.GET);
//
//        _httpClient.send(httpExchange);
//
//        try{barrier.await();}catch(Exception e){}
//
//    }
    
    
    public void testDestinationSecurityCaching() throws Exception
    {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        
        ContentExchange httpExchange = new ContentExchange()
        {
            @Override
            protected void onResponseComplete() throws IOException
            {
                super.onResponseComplete();
                try{barrier.await();}catch(Exception e){}
            }
        };
        
        httpExchange.setURL("http://localhost:" + _port + "/?i=1");
        httpExchange.setMethod(HttpMethods.GET);
        
        _httpClient.send(httpExchange);

        try{barrier.await();}catch(Exception e){}
        
        
        barrier.reset();
        ContentExchange httpExchange2 = new ContentExchange()
        {
            @Override
            protected void onResponseComplete() throws IOException
            {
                super.onResponseComplete();
                try{barrier.await();}catch(Exception e){}
            }
        };
        
        httpExchange2.setURL("http://localhost:" + _port + "/?i=2");
        httpExchange2.setMethod(HttpMethods.GET);
        
        _httpClient.send(httpExchange2);

        try{barrier.await();}catch(Exception e){}
        
        assertFalse( "exchange was retried", httpExchange2.getRetryStatus() );
        
    }   

    public static void copyStream(InputStream in, OutputStream out)
    {
        try
        {
            byte[] buffer=new byte[1024];
            int len;
            while ((len=in.read(buffer))>=0)
            {
                out.write(buffer,0,len);
            }
        }
        catch (EofException e)
        {
            System.err.println(e);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private void startServer() throws Exception
     {
         _server = new Server();
         _server.setGracefulShutdown(500);
         Connector connector = new SelectChannelConnector();

         connector.setPort(0);
         _server.setConnectors(new Connector[]{connector});

         Constraint constraint = new Constraint();
         constraint.setName("Need User or Admin");
         constraint.setRoles(new String[]{"user", "admin"});
         constraint.setAuthenticate(true);

         ConstraintMapping cm = new ConstraintMapping();
         cm.setConstraint(constraint);
         cm.setPathSpec("/*");

         LoginService loginService = new HashLoginService("MyRealm","src/test/resources/realm.properties");
         ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
         sh.setLoginService(loginService);
         sh.setAuthenticator(new BasicAuthenticator());
         
         //ServerAuthentication serverAuthentication = new BasicServerAuthentication(loginService, "MyRealm");
         //sh.setServerAuthentication(serverAuthentication);
         _server.setHandler(sh);

         Handler testHandler = new AbstractHandler()
         {
             public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
             {
                 // System.out.println("passed authentication!");
                 baseRequest.setHandled(true);
                 response.setStatus(200);
                 if (request.getServerName().equals("jetty.eclipse.org"))
                 {
                     response.getOutputStream().println("Proxy request: "+request.getRequestURL());
                 }
                 else if (request.getMethod().equalsIgnoreCase("GET"))
                 {
                     response.getOutputStream().println("<hello>");
                     for (int i=0; i<100; i++)
                     {
                         response.getOutputStream().println("  <world>"+i+"</world>");
                         if (i%20==0)
                             response.getOutputStream().flush();
                     }
                     response.getOutputStream().println("</hello>");
                 }
                 else
                 {
                     copyStream(request.getInputStream(),response.getOutputStream());
                 }
             }
         };

         sh.setHandler(testHandler);

         _server.start();
         _port = connector.getLocalPort();
     }


    private void stopServer() throws Exception
    {
        _server.stop();
    }
}
