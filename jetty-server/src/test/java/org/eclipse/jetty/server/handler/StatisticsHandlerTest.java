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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

public class StatisticsHandlerTest extends TestCase
{
    private Server _server;
    private LocalConnector _connector;
    private LatchHandler _latchHandler;
    private StatisticsHandler _statsHandler;

    @Override
    protected void setUp() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector();
        _server.addConnector(_connector);

        _latchHandler = new LatchHandler();
        _statsHandler = new StatisticsHandler();

        _server.setHandler(_latchHandler);
        _latchHandler.setHandler(_statsHandler);
    }

    @Override
    protected void tearDown() throws Exception
    {
        _server.stop();
        _server.join();
    }

    public void testRequest() throws Exception
    {
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2)};
        
        _statsHandler.setHandler(new AbstractHandler()
        {
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
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        
        assertEquals(0, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());


        barrier[1].await();
        boolean passed = _latchHandler.await(1000);
        assertTrue(passed);
        
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getSuspends());
        assertEquals(0, _statsHandler.getResumes());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());
        
        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();
        
        _connector.executeRequest(request);

        barrier[0].await();
        
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());


        barrier[1].await();
        passed = _latchHandler.await(1000);
        assertTrue(passed);
        
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getRequestsActiveMax());
        
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(1, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getSuspends());
        assertEquals(0, _statsHandler.getResumes());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(2, _statsHandler.getResponses2xx());

        _latchHandler.reset(2);
        barrier[0]=new CyclicBarrier(3);
        barrier[1]=new CyclicBarrier(3);
        
        _connector.executeRequest(request);
        _connector.executeRequest(request);

        barrier[0].await();
        
        assertEquals(2, _statsHandler.getRequests());
        assertEquals(2, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());
        
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(2, _statsHandler.getDispatchedActive());
        assertEquals(2, _statsHandler.getDispatchedActiveMax());


        barrier[1].await();
        passed = _latchHandler.await(1000);
        assertTrue(passed);
        
        assertEquals(4, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getRequestsActiveMax());
        
        assertEquals(4, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        assertEquals(2, _statsHandler.getDispatchedActiveMax());

        assertEquals(0, _statsHandler.getSuspends());
        assertEquals(0, _statsHandler.getResumes());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(4, _statsHandler.getResponses2xx());
        
        
    }

    public void testSuspendResume() throws Exception
    {
        final AtomicReference<Continuation> continuationHandle = new AtomicReference<Continuation>();
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);

                try
                {
                    barrier[0].await();
                    Thread.sleep(10);
                    Continuation continuation = ContinuationSupport.getContinuation(httpRequest);
                    if (continuationHandle.get() == null)
                    {
                        continuation.suspend();
                        continuationHandle.set(continuation);
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
                        Thread.currentThread().interrupt();
                        throw (IOException)new IOException().initCause(x);
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
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        
        assertEquals(0, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await(1000));
        assertNotNull(continuationHandle.get());
        assertTrue(continuationHandle.get().isSuspended());
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        Thread.sleep(10);
        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();
        
        continuationHandle.get().addContinuationListener(new ContinuationListener()
        {
            public void onTimeout(Continuation continuation)
            {
            }
            
            public void onComplete(Continuation continuation)
            {
                try { barrier[2].await(); } catch(Exception e) {}
            }
        });
        
        continuationHandle.get().resume();
        

        barrier[0].await();
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await(1000));
        barrier[2].await();
        
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        
        
        assertEquals(1, _statsHandler.getSuspends());
        assertEquals(1, _statsHandler.getResumes());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());
        
        
        assertTrue(_statsHandler.getRequestTimeTotal()>=30);
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeAverage());
        
        assertTrue(_statsHandler.getDispatchedTimeTotal()>=20);
        assertTrue(_statsHandler.getDispatchedTimeAverage()+10<=_statsHandler.getDispatchedTimeTotal());
        assertTrue(_statsHandler.getDispatchedTimeMax()+10<=_statsHandler.getDispatchedTimeTotal());
        
    }

    public void testSuspendExpire() throws Exception
    {
        final AtomicReference<Continuation> continuationHandle = new AtomicReference<Continuation>();
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);

                try
                {
                    barrier[0].await();
                    Thread.sleep(10);
                    Continuation continuation = ContinuationSupport.getContinuation(httpRequest);
                    if (continuationHandle.get() == null)
                    {
                        continuation.setTimeout(10);
                        continuation.suspend();
                        continuationHandle.set(continuation);
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
                        Thread.currentThread().interrupt();
                        throw (IOException)new IOException().initCause(x);
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
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        
        assertEquals(0, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await(1000));
        assertNotNull(continuationHandle.get());
        assertTrue(continuationHandle.get().isSuspended());
        
        continuationHandle.get().addContinuationListener(new ContinuationListener()
        {
            public void onTimeout(Continuation continuation)
            {
            }
            
            public void onComplete(Continuation continuation)
            {
                try { barrier[2].await(); } catch(Exception e) {}
            }
        });
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        _latchHandler.reset();
        barrier[0].reset();
        barrier[1].reset();

        barrier[0].await();
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        barrier[1].await();
        assertTrue(_latchHandler.await(1000));
        barrier[2].await();
        
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(2, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        
        assertEquals(1, _statsHandler.getSuspends());
        assertEquals(1, _statsHandler.getResumes());
        assertEquals(1, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());
        
        
        assertTrue(_statsHandler.getRequestTimeTotal()>=30);
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeAverage());
        
        assertTrue(_statsHandler.getDispatchedTimeTotal()>=20);
        assertTrue(_statsHandler.getDispatchedTimeAverage()+10<=_statsHandler.getDispatchedTimeTotal());
        assertTrue(_statsHandler.getDispatchedTimeMax()+10<=_statsHandler.getDispatchedTimeTotal());
        
    }

    public void testSuspendComplete() throws Exception
    {
        final AtomicReference<Continuation> continuationHandle = new AtomicReference<Continuation>();
        final CyclicBarrier barrier[] = { new CyclicBarrier(2), new CyclicBarrier(2), new CyclicBarrier(2)};
        _statsHandler.setHandler(new AbstractHandler()
        {
            public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);

                try
                {
                    barrier[0].await();
                    Thread.sleep(10);
                    Continuation continuation = ContinuationSupport.getContinuation(httpRequest);
                    if (continuationHandle.get() == null)
                    {
                        continuation.setTimeout(1000);
                        continuation.suspend();
                        continuationHandle.set(continuation);
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
                        Thread.currentThread().interrupt();
                        throw (IOException)new IOException().initCause(x);
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
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        
        assertEquals(0, _statsHandler.getDispatched());
        assertEquals(1, _statsHandler.getDispatchedActive());

        
        barrier[1].await();
        assertTrue(_latchHandler.await(1000));
        assertNotNull(continuationHandle.get());
        assertTrue(continuationHandle.get().isSuspended());
        continuationHandle.get().addContinuationListener(new ContinuationListener()
        {
            public void onTimeout(Continuation continuation)
            {
            }
            
            public void onComplete(Continuation continuation)
            {
                try { barrier[2].await(); } catch(Exception e) {}
            }
        });
        
        assertEquals(0, _statsHandler.getRequests());
        assertEquals(1, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());

        Thread.sleep(10);
        continuationHandle.get().complete();
        barrier[2].await();
        
        assertEquals(1, _statsHandler.getRequests());
        assertEquals(0, _statsHandler.getRequestsActive());
        assertEquals(1, _statsHandler.getDispatched());
        assertEquals(0, _statsHandler.getDispatchedActive());
        
        assertEquals(1, _statsHandler.getSuspends());
        assertEquals(0, _statsHandler.getResumes());
        assertEquals(0, _statsHandler.getExpires());
        assertEquals(1, _statsHandler.getResponses2xx());
        
        assertTrue(_statsHandler.getRequestTimeTotal()>=20);
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeMax());
        assertEquals(_statsHandler.getRequestTimeTotal(),_statsHandler.getRequestTimeAverage());
        
        assertTrue(_statsHandler.getDispatchedTimeTotal()>=10);
        assertTrue(_statsHandler.getDispatchedTimeTotal()<_statsHandler.getRequestTimeTotal());
        assertEquals(_statsHandler.getDispatchedTimeTotal(),_statsHandler.getDispatchedTimeMax());
        assertEquals(_statsHandler.getDispatchedTimeTotal(),_statsHandler.getDispatchedTimeAverage());
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
        
        private boolean await(long ms) throws InterruptedException
        {
            return _latch.await(ms, TimeUnit.MILLISECONDS);
        }
    }
}
