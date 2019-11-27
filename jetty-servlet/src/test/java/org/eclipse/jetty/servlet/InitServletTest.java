//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.array;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InitServletTest
{
    public static class DemoServlet extends HttpServlet
    {
        AtomicInteger initCount = new AtomicInteger();

        @Override
        public void init() throws ServletException
        {
            super.init();
            try
            {
                //Make the initialization last a little while so
                //other request can run
                Thread.sleep(2000);
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
            initCount.addAndGet(1);
        }

        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            //Check that the init() method has been totally finished (by another request) before
            //the servlet service() method is called.
            if (initCount.get() != 1)
            {
                resp.sendError(500, "Servlet not initialized!");
                System.err.println("NOT INIT");
            }
        }
    }
    
    private static class AsyncResponseListener implements Response.CompleteListener
    {

        private CountDownLatch resultsLatch;
        private Integer[] results;
        private AtomicInteger index = new AtomicInteger();
        
        public AsyncResponseListener(CountDownLatch resultsLatch, Integer[] results)
        {
           this.resultsLatch = resultsLatch;
           this.results = results;
        }
        
        public void onComplete(Result result)
        {
            results[index.getAndAdd(1)] = result.getResponse().getStatus();
            resultsLatch.countDown();
        }
        
        public void awaitCompletion() throws InterruptedException
        {
            assertTrue(resultsLatch.await(60L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testServletInitialization() throws Exception
    {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        server.setHandler(context);
        //add a lazily instantiated servlet
        context.addServlet(new ServletHolder(DemoServlet.class), "/*");
        server.start();
        int port = ((NetworkConnector)server.getConnectors()[0]).getLocalPort();
        HttpClient client = new HttpClient();
        try
        {
            client.start();

            //Expect 2 responses
            CountDownLatch resultsLatch = new CountDownLatch(2);
            Integer[] results = new Integer[2];
            AsyncResponseListener l = new AsyncResponseListener(resultsLatch, results);
            
            //req1: should initialize servlet
            Request r1 = client.newRequest("http://localhost:" + port + "/r1");
            r1.method(HttpMethod.GET).send(l);

            //Need to give 1st request a head start before request2
            Thread.sleep(500);
            
            //req2: should see servlet fully initialized by request1
            Request r2 = client.newRequest("http://localhost:" + port + "/r2");
            r2.method(HttpMethod.GET).send(l);

            l.awaitCompletion();
            
            assertThat(results, is(array(equalTo(HttpStatus.OK_200), equalTo(HttpStatus.OK_200))));
        }
        finally
        {
            client.stop();
            server.stop();
        }
    }
}
