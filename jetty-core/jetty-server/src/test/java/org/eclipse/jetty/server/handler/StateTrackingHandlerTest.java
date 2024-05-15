//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StateTrackingHandlerTest
{
    private Server server;
    private LocalConnector connector;

    public void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void destroy()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testHandlerCallbackSucceededThenHandlerReturnTrue() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Request.addCompletionListener(request, x -> latch.countDown());
                callback.succeeded();
                return true;
            }
        });
        start(completionHandler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """));

        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(listener.events(), empty());
    }

    @Test
    public void testHandlerReturnTrueThenHandlerCallbackSucceeded() throws Exception
    {
        long delay = 500;
        CountDownLatch latch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Request.addCompletionListener(request, x -> latch.countDown());
                request.getComponents().getScheduler().schedule(callback::succeeded, delay, TimeUnit.MILLISECONDS);
                return true;
            }
        });
        start(completionHandler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """));

        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(latch.await(2 * delay, TimeUnit.MILLISECONDS));
        assertThat(listener.events(), empty());
    }

    @Test
    public void testHandlerCallbackSucceededThenHandlerReturnFalse() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Request.addCompletionListener(request, x -> latch.countDown());
                callback.succeeded();
                return false;
            }
        });
        start(completionHandler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """));

        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(listener.events(), contains("invalid"));
    }

    @Test
    public void testHandlerReturnsFalseThenHandlerCallbackSucceeded() throws Exception
    {
        long delay = 500;
        String threadName = "cch-test";
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<StateTrackingHandler.ThreadInfo> threadInfoRef = new AtomicReference<>();
        EventsListener listener = new EventsListener()
        {
            @Override
            public void onInvalidHandlerReturnValue(Request request, StateTrackingHandler.ThreadInfo completionThreadInfo)
            {
                super.onInvalidHandlerReturnValue(request, completionThreadInfo);
                threadInfoRef.set(completionThreadInfo);
            }
        };
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Request.addCompletionListener(request, x -> latch.countDown());
                request.getComponents().getScheduler().schedule(() -> new Thread(() ->
                {
                    callback.succeeded();
                    latch.countDown();
                }, threadName).start(), delay, TimeUnit.MILLISECONDS);
                return false;
            }
        });
        start(completionHandler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """));

        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());

        assertTrue(latch.await(2 * delay, TimeUnit.MILLISECONDS));
        assertThat(listener.events(), contains("invalid"));
        assertThat(threadInfoRef.get().getInfo(), containsString(threadName));
    }

    @Test
    public void testHandlerReturnsTrueHandlerCallbackNotCompleted() throws Exception
    {
        long timeout = 1000;
        AtomicReference<StateTrackingHandler.ThreadInfo> threadInfoRef = new AtomicReference<>();
        EventsListener listener = new EventsListener()
        {
            @Override
            public void onHandlerCallbackNotCompleted(Request request, StateTrackingHandler.ThreadInfo handlerThreadInfo)
            {
                super.onHandlerCallbackNotCompleted(request, handlerThreadInfo);
                threadInfoRef.set(handlerThreadInfo);
            }
        };
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandlerCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not complete the callback.
                return true;
            }
        });
        start(completionHandler);

        String response = connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """, 2 * timeout, TimeUnit.MILLISECONDS);

        // There should be no response, as the callback was not completed.
        assertNull(response);
        assertThat(listener.events(), contains("handler"));
        assertNull(threadInfoRef.get());
    }

    @Test
    public void testHandlerReturnsTrueHandlerCallbackNotCompletedThenHandlerCallbackIsForcefullyFailed() throws Exception
    {
        long timeout = 1000;
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandlerCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setCompleteHandlerCallbackAtTimeout(true);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not complete the callback.
                return true;
            }
        });
        start(completionHandler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """, 2 * timeout, TimeUnit.MILLISECONDS));

        // There should be an error response.
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
        assertThat(listener.events(), contains("handler"));
    }

    @Test
    public void testHandlerBlocksHandlerCallbackNotCompleted() throws Exception
    {
        long timeout = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadNameRef = new AtomicReference<>();
        AtomicReference<StateTrackingHandler.ThreadInfo> threadInfoRef = new AtomicReference<>();
        EventsListener listener = new EventsListener()
        {
            @Override
            public void onHandlerCallbackNotCompleted(Request request, StateTrackingHandler.ThreadInfo handlerThreadInfo)
            {
                super.onHandlerCallbackNotCompleted(request, handlerThreadInfo);
                threadInfoRef.set(handlerThreadInfo);
            }
        };
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandlerCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                threadNameRef.set(Thread.currentThread().getName());
                // Block.
                latch.await();
                return true;
            }
        });
        start(completionHandler);

        String response = connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """, 2 * timeout, TimeUnit.MILLISECONDS);

        // There should be no response, as the callback was not completed.
        assertNull(response);
        latch.countDown();
        assertThat(listener.events(), contains("handler"));
        assertThat(threadInfoRef.get().getInfo(), containsString(threadNameRef.get()));
    }

    @Test
    public void testDemandCallbackBlocks() throws Exception
    {
        long timeout = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setDemandCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.demand(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        Content.Chunk chunk = request.read();
                        if (chunk != null)
                        {
                            chunk.release();
                            if (chunk.isLast())
                            {
                                try
                                {
                                    // Block.
                                    latch.await();
                                    callback.succeeded();
                                }
                                catch (Throwable x)
                                {
                                    callback.failed(x);
                                }
                                return;
                            }
                        }
                        request.demand(this);
                    }
                });
                return true;
            }
        });
        start(completionHandler);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            
            A"""))
        {
            await().atMost(2 * timeout, TimeUnit.MILLISECONDS).until(listener::events, contains("demand-blocked"));

            // Let the server send the response.
            latch.countDown();

            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, timeout, TimeUnit.MILLISECONDS));
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testWriteBlocks() throws Exception
    {
        // A Handler that blocks in write().
        CountDownLatch writeLatch = new CountDownLatch(1);
        Handler.Wrapper wrapper = new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Response.Wrapper wrapper = new Response.Wrapper(request, response)
                {
                    @Override
                    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
                    {
                        try
                        {
                            // Block.
                            writeLatch.await();
                            super.write(last, byteBuffer, callback);
                        }
                        catch (Throwable x)
                        {
                            callback.failed(x);
                        }
                    }
                };
                return super.handle(request, wrapper, callback);
            }
        };

        long timeout = 1000;
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        wrapper.setHandler(completionHandler);
        completionHandler.setWriteTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, null, callback);
                return true;
            }
        });
        start(wrapper);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET / HTTP/1.1
            Host: localhost
            
            """))
        {
            await().atMost(2 * timeout, TimeUnit.MILLISECONDS).until(listener::events, contains("write-blocked"));

            writeLatch.countDown();
            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, timeout, TimeUnit.MILLISECONDS));
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testWriteCallbackNotCompleted() throws Exception
    {
        // Simulates a Handler with a bug: it does not complete write callbacks.
        Handler.Wrapper wrapper = new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Response wrapped = new Response.Wrapper(request, response)
                {
                    @Override
                    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
                    {
                        // The callback parameter is the write callback from
                        // StateTrackingHandler that will not be completed.
                        super.write(last, byteBuffer, Callback.NOOP);
                    }
                };
                return super.handle(request, wrapped, callback);
            }
        };

        long timeout = 1000;
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setWriteTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandlerCallbackTimeout(Duration.ofMillis(2 * timeout));
        wrapper.setHandler(completionHandler);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, null, callback);
                return true;
            }
        });
        start(wrapper);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET / HTTP/1.1
            Host: localhost
            
            """))
        {
            await().atMost(3 * timeout, TimeUnit.MILLISECONDS).until(listener::events, contains("write-callback", "handler"));

            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, timeout, TimeUnit.MILLISECONDS));
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testWriteCallbackBlocks() throws Exception
    {
        long timeout = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setWriteCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(false, null, Callback.from(() ->
                {
                    try
                    {
                        // Block.
                        latch.await();
                        callback.succeeded();
                    }
                    catch (Throwable x)
                    {
                        callback.failed(x);
                    }
                }, callback::failed));
                return true;
            }
        });
        start(completionHandler);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET / HTTP/1.1
            Host: localhost
            
            """))
        {
            await().atMost(2 * timeout, TimeUnit.MILLISECONDS).until(listener::events, contains("write-callback-blocked"));

            // Let the server send the response.
            latch.countDown();

            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, timeout, TimeUnit.MILLISECONDS));
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testDemandCallbackCallsRequestDemand() throws Exception
    {
        long timeout = 1000;
        CountDownLatch demandLatch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setDemandCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.demand(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            while (true)
                            {
                                Content.Chunk chunk = request.read();
                                if (chunk == null)
                                {
                                    request.demand(this);
                                    // Bad behavior: must not block a demand
                                    // callback because they are serialized.
                                    demandLatch.await();
                                    return;
                                }
                                chunk.release();
                                if (chunk.isLast())
                                {
                                    callback.succeeded();
                                    return;
                                }
                            }
                        }
                        catch (Throwable x)
                        {
                            callback.failed(x);
                        }
                    }
                });
                return true;
            }
        });
        start(completionHandler);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 2
            
            """))
        {
            // Wait to return from handle(), then send the first chunk of content.
            Thread.sleep(500);
            endPoint.addInputAndExecute("A");

            // Wait to detect the blocked demand callback, then add the last chunk of content.
            await().atMost(2 * timeout, TimeUnit.MILLISECONDS).until(listener::events, contains("demand-blocked"));
            assertThat(completionHandler.dump(), containsString("demands size=2"));
            demandLatch.countDown();
            endPoint.addInputAndExecute("B");

            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testWriteCallbackCallsResponseWrite() throws Exception
    {
        long timeout = 1000;
        CountDownLatch writeLatch = new CountDownLatch(1);
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setWriteCallbackTimeout(Duration.ofMillis(timeout));
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // This write should call the callback synchronously.
                Content.Sink.write(response, false, "A", Callback.from(() ->
                {
                    try
                    {
                        Content.Sink.write(response, true, "B", callback);
                        // Bad behavior: must not block a write
                        // callback because they are serialized.
                        writeLatch.await();
                    }
                    catch (Throwable x)
                    {
                        callback.failed(x);
                    }
                }, callback::failed));
                return true;
            }
        });
        start(completionHandler);

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET / HTTP/1.1
            Host: localhost
            
            """))
        {
            // Wait to detect the blocked demand callback, then add the last chunk of content.
            await().atMost(2 * timeout, TimeUnit.MILLISECONDS).until(listener::events, contains("write-callback-blocked"));
            assertThat(completionHandler.dump(), containsString("writes size=2"));
            writeLatch.countDown();
            endPoint.addInputAndExecute("B");

            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse(false, 5, TimeUnit.SECONDS));

            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("AB", response.getContent());
        }
    }

    @Test
    public void testHandlerThrows() throws Exception
    {
        EventsListener listener = new EventsListener();
        StateTrackingHandler completionHandler = new StateTrackingHandler(listener);
        completionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                throw new QuietException.RuntimeException();
            }
        });
        start(completionHandler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET / HTTP/1.1
            Host: localhost
            
            """, 5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
        assertThat(listener.events(), contains("exception"));
    }

    private static class EventsListener implements StateTrackingHandler.Listener
    {
        private final List<String> events = new CopyOnWriteArrayList<>();

        private List<String> events()
        {
            return events;
        }

        @Override
        public void onInvalidHandlerReturnValue(Request request, StateTrackingHandler.ThreadInfo completionThreadInfo)
        {
            events.add("invalid");
        }

        @Override
        public void onHandlerException(Request request, Throwable failure, StateTrackingHandler.ThreadInfo completionThreadInfo)
        {
            events.add("exception");
        }

        @Override
        public void onHandlerCallbackNotCompleted(Request request, StateTrackingHandler.ThreadInfo handlerThreadInfo)
        {
            events.add("handler");
        }

        @Override
        public void onDemandCallbackBlocked(Request request, StateTrackingHandler.ThreadInfo demandThreadInfo, StateTrackingHandler.ThreadInfo runThreadInfo)
        {
            events.add("demand-blocked");
        }

        @Override
        public void onWriteBlocked(Request request, StateTrackingHandler.ThreadInfo writeThreadInfo, StateTrackingHandler.ThreadInfo writingThreadInfo)
        {
            events.add("write-blocked");
        }

        @Override
        public void onWriteCallbackNotCompleted(Request request, Throwable writeFailure, StateTrackingHandler.ThreadInfo writeThreadInfo)
        {
            events.add("write-callback");
        }

        @Override
        public void onWriteCallbackBlocked(Request request, Throwable writeFailure, StateTrackingHandler.ThreadInfo writeThreadInfo, StateTrackingHandler.ThreadInfo callbackThreadInfo)
        {
            events.add("write-callback-blocked");
        }
    }
}
