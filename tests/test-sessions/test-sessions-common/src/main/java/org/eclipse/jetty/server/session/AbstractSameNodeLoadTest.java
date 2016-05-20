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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Test;


/**
 * AbstractSameNodeLoadTest
 * 
 * This test performs multiple concurrent requests for the same session on the same node.
 * 
 */
public abstract class AbstractSameNodeLoadTest extends AbstractTestBase
{
    protected boolean _stress = Boolean.getBoolean( "STRESS" );



    @Test
    public void testLoad() throws Exception
    {
        if ( _stress )
        {
            String contextPath = "";
            String servletMapping = "/server";
            AbstractTestServer server1 = createServer(0, 20000, 4, 1000);
            server1.addContext( contextPath ).addServlet( TestServlet.class, servletMapping );

            try
            {
                server1.start();
                int port1 = server1.getPort();

                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    String url = "http://localhost:" + port1 + contextPath + servletMapping;


                    //create session via first server
                    ContentResponse response1 = client.GET(url + "?action=init");
                    assertEquals(HttpServletResponse.SC_OK,response1.getStatus());
                    String sessionCookie = response1.getHeaders().getStringField( "Set-Cookie" );
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    //simulate 10 clients making 100 requests each
                    ExecutorService executor = Executors.newCachedThreadPool();
                    int clientsCount = 10;
                    CyclicBarrier barrier = new CyclicBarrier( clientsCount + 1 );
                    int requestsCount = 100;
                    Worker[] workers = new Worker[clientsCount];
                    for ( int i = 0; i < clientsCount; ++i )
                    {
                        workers[i] = new Worker(barrier, client, requestsCount, sessionCookie, url);
                        executor.execute( workers[i] );
                    }
                    // Wait for all workers to be ready
                    barrier.await();
                    long start = System.nanoTime();

                    // Wait for all workers to be done
                    barrier.await();
                    long end = System.nanoTime();
                    long elapsed = TimeUnit.NANOSECONDS.toMillis( end - start );
                    System.out.println( "elapsed ms: " + elapsed );

                    executor.shutdownNow();

                    // Perform one request to get the result
                    Request request = client.newRequest( url + "?action=result" );
                    request.header("Cookie", sessionCookie);
                    ContentResponse response2 = request.send();
                    assertEquals(HttpServletResponse.SC_OK,response2.getStatus());
                    String response = response2.getContentAsString();
                    System.out.println( "get = " + response );
                    assertEquals(response.trim(), String.valueOf( clientsCount * requestsCount ) );
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server1.stop();
            }
        }
    }

    public static class Worker implements Runnable
    {
        public static int COUNT = 0;
        
        private final HttpClient client;

        private final CyclicBarrier barrier;

        private final int requestsCount;

        private final String sessionCookie;

        private final String url;
        
        private final String name;


        public Worker(CyclicBarrier barrier, HttpClient client, int requestsCount, String sessionCookie, String url)
        {
            this.client = client;
            this.barrier = barrier;
            this.requestsCount = requestsCount;
            this.sessionCookie = sessionCookie;
            this.url = url;
            this.name = ""+(COUNT++);
        }


        public void run()
        {
            try
            {
                // Wait for all workers to be ready
                barrier.await();

                Random random = new Random( System.nanoTime() );

                for ( int i = 0; i < requestsCount; ++i )
                {
                    int pauseMsec = random.nextInt(1000);

                    //wait a random number of milliseconds between requests up to 1 second
                    if (pauseMsec > 0)
                    {
                        Thread.currentThread().sleep(pauseMsec);
                    }
                    Request request = client.newRequest(url + "?action=increment");
                    request.header("Cookie", sessionCookie);
                    ContentResponse response = request.send();
                    assertEquals(HttpServletResponse.SC_OK,response.getStatus());
                }

                // Wait for all workers to be done
                barrier.await();
            }
            catch ( Exception x )
            {
                throw new RuntimeException( x );
            }
        }
    }

    public static class TestServlet
        extends HttpServlet
    {
        @Override
        protected void doGet( HttpServletRequest request, HttpServletResponse response )
            throws ServletException, IOException
        {
            String action = request.getParameter( "action" );
            if ( "init".equals( action ) )
            {
                HttpSession session = request.getSession( true );
                session.setAttribute( "value", 0 );
            }
            else if ( "increment".equals( action ) )
            {
                HttpSession session = request.getSession( false );
                assertNotNull(session);
                synchronized(session)
                {
                    int value = (Integer) session.getAttribute( "value" );
                    session.setAttribute( "value", value + 1 );
                }
            }
            else if ( "result".equals( action ) )
            {
                HttpSession session = request.getSession( false );
                assertNotNull(session);
                Integer value = null;
                synchronized (session)
                {
                    value = (Integer) session.getAttribute( "value" );
                }
                PrintWriter writer = response.getWriter();
                writer.println( value );
                writer.flush();
            }
        }
    }
}
