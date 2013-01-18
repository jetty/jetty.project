//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StatisticsHandlerTest
{
    private Server _server;
    private ConnectorStatistics _statistics;
    private LocalConnector _connector;
    private LatchHandler _latchHandler;
    private StatisticsHandler _statsHandler;

    @Before
    public void init() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _statistics=new ConnectorStatistics();
        _connector.addBean(_statistics);
        _server.addConnector(_connector);

        _latchHandler = new LatchHandler();
        _statsHandler = new StatisticsHandler();

        _server.setHandler(_latchHandler);
        _latchHandler.setHandler(_statsHandler);
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testRequest() throws Exception
    {
        final CyclicBarrier barrier[] = {new CyclicBarrier(2), new CyclicBarrier(2)};

        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);
                try
                {
                    barrier[0].await();
                    barrier[1].await();

                }
                catch (Exception x)
                {
                    Thread.currentThread().interrupt();
                    throw (IOException)new IOException().initCause(x);
                }
            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(1, _statistics.getConnectionsOpen());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());


        barrier[1].await();
        assertTrue(_latchHandler.await());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();

        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(2, _statistics.getConnectionsOpen());

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());


        barrier[1].await();
        assertTrue(_latchHandler.await());

        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());

        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(2, _statsHandler.getResponses2xx());

        _latchHandler.reset(2);
        barrier[0]=new CyclicBarrier(3);
        barrier[1]=new CyclicBarrier(3);

        _connector.executeRequest(request);
        _connector.executeRequest(request);

        barrier[0].await();

        assertEquals(4, _statistics.getConnectionsOpen());

        assertEquals(4, _statsHandler.getRequests());
        assertEquals(2, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());

        assertEquals(4, _statsHandler.getDispatched());
        assertEquals(2, _statsHandler.getDispatchedActive());
        assertEquals(2, _statsHandler.getDispatchedActiveMax());


        barrier[1].await();
        assertTrue(_latchHandler.await());

        assertEquals(4, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());

        assertEquals(4, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(2, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(4, _statsHandler.getResponses2xx());


    }

    @Test
    public void testSuspendResume() throws Exception
    {
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);

                try
                {
                    barrier[0].await();

                    Thread.sleep(10);
                    
                    if (asyncHolder.get() == null)
                    {
                        asyncHolder.set(request.startAsync());
                    }
                }
                catch (Exception x)
                {
                    Thread.currentThread().interrupt();
                    throw (IOException)new IOException().initCause(x);
                }
                finally
                {
                    try
                    {
                        barrier[1].await();
                    }
                    catch (Exception x)
                    {
                        x.printStackTrace();
                        Thread.currentThread().interrupt();
                        fail();
                    }
                }

            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";
        _connector.executeRequest(request);


        barrier[0].await();

        assertEquals(1, _statistics.getConnectionsOpen());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();

        assertTrue(_latchHandler.await());
        assertNotNull(asyncHolder.get());
        assertTrue(asyncHolder.get()!=null);

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        Thread.sleep(10);
        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();

        Thread.sleep(50);

        asyncHolder.get().addListener(new AsyncListener()
        {
            @Override
            public void onTimeout(AsyncEvent event) throws IOException
            {
            }
            
            @Override
            public void onStartAsync(AsyncEvent event) throws IOException
            {
            }
            
            @Override
            public void onError(AsyncEvent event) throws IOException
            {
            }
            
            @Override
            public void onComplete(AsyncEvent event) throws IOException
            {
                try { barrier[2].await(); } catch(Exception e) {}
            }
        });
        
        asyncHolder.get().dispatch();

        barrier[0].await(); // entered app handler

        assertEquals(1, _statistics.getConnectionsOpen());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await(); // exiting app handler
        assertTrue(_latchHandler.await()); // exited stats handler
        barrier[2].await(); // onComplete called

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());


        assertEquals(1, _statsHandler.getAsyncRequests());
        assertEquals(1, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());


        assertThat(_statsHandler.getRequestTimeTotal(),greaterThanOrEqualTo(50L));
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMean(), 0.01);

        assertTrue(_statsHandler.getDispatchedTimeTotal()>=20);
        assertTrue(_statsHandler.getDispatchedTimeMean()+10<=_statsHandler.getDispatchedTimeTotal());
        assertTrue(_statsHandler.getDispatchedTimeMax()+10<=_statsHandler.getDispatchedTimeTotal());

    }

    @Test
    public void testSuspendExpire() throws Exception
    {
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);

                try
                {
                    barrier[0].await();
                    Thread.sleep(10);
                    if (asyncHolder.get() == null)
                    {
                        AsyncContext async=request.startAsync();
                        async.setTimeout(100);
                        asyncHolder.set(async);
                    }
                }
                catch (Exception x)
                {
                    Thread.currentThread().interrupt();
                    throw (IOException)new IOException().initCause(x);
                }
                finally
                {
                    try
                    {
                        barrier[1].await();
                    }
                    catch (Exception x)
                    {
                        x.printStackTrace();
                        Thread.currentThread().interrupt();
                        fail();
                    }
                }

            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";
        _connector.executeRequest(request);


        barrier[0].await();

        assertEquals(1, _statistics.getConnectionsOpen());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await());
        assertNotNull(asyncHolder.get());
        asyncHolder.get().addListener(new AsyncListener()
        {
            @Override
            public void onTimeout(AsyncEvent event) throws IOException
            {
            }
            
            @Override
            public void onStartAsync(AsyncEvent event) throws IOException
            {
            }
            
            @Override
            public void onError(AsyncEvent event) throws IOException
            {
            }
            
            @Override
            public void onComplete(AsyncEvent event) throws IOException
            {
                try { barrier[2].await(); } catch(Exception e) {}                
            }
        });


        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        
        barrier[2].await();

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        assertEquals(1, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(1, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());


        assertTrue(_statsHandler.getRequestTimeTotal()>=30);
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMean(), 0.01);

        assertThat(_statsHandler.getDispatchedTimeTotal(),greaterThanOrEqualTo(10L));

    }

    @Test
    public void testSuspendComplete() throws Exception
    {
        final AtomicReference<AsyncContext> asyncHolder = new AtomicReference<>();
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);

                try
                {
                    barrier[0].await();
                    Thread.sleep(10);
                    if (asyncHolder.get() == null)
                    {
                        AsyncContext async=request.startAsync();
                        async.setTimeout(1000);
                        asyncHolder.set(async);
                        asyncHolder.get().addListener(new AsyncListener()
                        {
                            
                            @Override
                            public void onTimeout(AsyncEvent event) throws IOException
                            {
                            }
                            
                            @Override
                            public void onStartAsync(AsyncEvent event) throws IOException
                            {
                            }
                            
                            @Override
                            public void onError(AsyncEvent event) throws IOException
                            {
                            }
                            
                            @Override
                            public void onComplete(AsyncEvent event) throws IOException
                            {
                                try { barrier[2].await(); } catch(Exception e) {}                
                            }
                        });
                    }

                }
                catch (Exception x)
                {
                    Thread.currentThread().interrupt();
                    throw (IOException)new IOException().initCause(x);
                }
                finally
                {
                    try
                    {
                        barrier[1].await();
                    }
                    catch (Exception x)
                    {
                        x.printStackTrace();
                        Thread.currentThread().interrupt();
                        fail();
                    }
                }

            }
        });
        _server.start();

        String request = "GET / HTTP/1.1\r\n" +
                         "Host: localhost\r\n" +
                         "\r\n";
        _connector.executeRequest(request);


        barrier[0].await();

        assertEquals(1, _statistics.getConnectionsOpen());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());


        barrier[1].await();
        assertTrue(_latchHandler.await());
        assertNotNull(asyncHolder.get());

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        Thread.sleep(10);
        asyncHolder.get().complete();
        barrier[2].await();

        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        assertEquals(1, _statsHandler.getAsyncRequests());
        assertEquals(0, _statsHandler.getAsyncDispatches());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());

        assertTrue(_statsHandler.getRequestTimeTotal()>=20);
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMean(), 0.01);

        assertTrue(_statsHandler.getDispatchedTimeTotal()>=10);
        assertTrue(_statsHandler.getDispatchedTimeTotal()<_statsHandler.getRequestTimeTotal());
        assertEquals(_statsHandler.getDispatchedTimeTotal(),_statsHandler.getDispatchedTimeMax());
        assertEquals(_statsHandler.getDispatchedTimeTotal(),_statsHandler.getDispatchedTimeMean(), 0.01);
    }


    /**
     * This handler is external to the statistics handler and it is used to ensure that statistics handler's
     * handle() is fully executed before asserting its values in the tests, to avoid race conditions with the
     * tests' code where the test executes but the statistics handler has not finished yet.
     */
    private static class LatchHandler extends HandlerWrapper
    {
        private volatile CountDownLatch _latch = new CountDownLatch(1);

        @Override
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            final CountDownLatch latch=_latch;
            try
            {
                super.handle(path, request, httpRequest, httpResponse);
            }
            finally
            {
                latch.countDown();
            }
        }

        private void reset()
        {
            _latch=new CountDownLatch(1);
        }

        private void reset(int count)
        {
            _latch=new CountDownLatch(count);
        }

        private boolean await() throws InterruptedException
        {
            return _latch.await(10000, TimeUnit.MILLISECONDS);
        }
    }
}
