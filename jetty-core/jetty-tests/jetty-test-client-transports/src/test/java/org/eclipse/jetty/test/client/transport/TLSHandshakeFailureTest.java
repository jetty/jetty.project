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

package org.eclipse.jetty.test.client.transport;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TLSHandshakeFailureTest extends AbstractTest
{
    private StacklessLogging stackless;

    @BeforeEach
    public void prepare()
    {
        stackless = new StacklessLogging(SelectorManager.class);
    }

    @AfterEach
    public void dispose()
    {
        IO.close(stackless);
    }

    @ParameterizedTest
    @MethodSource("transportsTLS")
    public void testTLSWrapAbruptSSLEngineClose(TransportType transportType) throws Exception
    {
        TLSHandshakeAction action = SSLEngine::closeOutbound;
        testTLSWrapFailure(transportType, action, 1);
        stop();
        testTLSWrapFailure(transportType, action, 2);
    }

    @ParameterizedTest
    @MethodSource("transportsTLS")
    public void testTLSWrapAbruptSSLEngineFailure(TransportType transportType) throws Exception
    {
        TLSHandshakeAction action = sslEngine ->
        {
            throw new SSLException("test");
        };
        testTLSWrapFailure(transportType, action, 1);
        stop();
        testTLSWrapFailure(transportType, action, 2);
    }

    private void testTLSWrapFailure(TransportType transportType, TLSHandshakeAction action, int wrapCount) throws Exception
    {
        start(transportType, new EmptyServerHandler());
        client.stop();
        client = new HttpClient(client.getHttpClientTransport())
        {
            @Override
            public ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory.Client sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(getByteBufferPool(), getExecutor(), getSslContextFactory(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                        {
                            private final AtomicInteger wraps = new AtomicInteger();

                            @Override
                            protected SSLEngineResult wrap(SSLEngine sslEngine, ByteBuffer[] input, ByteBuffer output) throws SSLException
                            {
                                if (wraps.incrementAndGet() == wrapCount)
                                    action.accept(sslEngine);
                                return super.wrap(sslEngine, input, output);
                            }
                        };
                    }
                };
            }
        };
        ScheduledThreadPoolExecutor schedulerService = new ScheduledThreadPoolExecutor(1);
        schedulerService.setRemoveOnCancelPolicy(true);
        client.setScheduler(new ScheduledExecutorScheduler(schedulerService));
        client.start();

        int count = 10;
        for (int i = 0; i < count; ++i)
        {
            assertThrows(ExecutionException.class, () -> client.newRequest(newURI(transportType))
                .timeout(5, TimeUnit.SECONDS)
                .send()
            );
        }

        // There should be a task scheduled by HttpDestination
        // to expire HttpExchanges while they are queued.
        // There may be also a task for the idle timeout of the connection.
        List<Runnable> tasks = schedulerService.shutdownNow();
        assertThat(tasks.toString(), tasks.size(), allOf(greaterThan(0), lessThan(count + 1)));
    }

    @ParameterizedTest
    @MethodSource("transportsTLS")
    public void testTLSUnwrapAbruptSSLEngineClose(TransportType transportType) throws Exception
    {
        TLSHandshakeAction action = SSLEngine::closeInbound;
        testTLSUnwrapFailure(transportType, action, 1);
        stop();
        testTLSUnwrapFailure(transportType, action, 2);
    }

    @ParameterizedTest
    @MethodSource("transportsTLS")
    public void testTLSUnwrapAbruptSSLEngineFailure(TransportType transportType) throws Exception
    {
        TLSHandshakeAction action = sslEngine ->
        {
            throw new SSLException("test");
        };
        testTLSUnwrapFailure(transportType, action, 1);
        stop();
        testTLSUnwrapFailure(transportType, action, 2);
    }

    private void testTLSUnwrapFailure(TransportType transportType, TLSHandshakeAction action, int unwrapCount) throws Exception
    {
        start(transportType, new EmptyServerHandler());
        client.stop();
        client = new HttpClient(client.getHttpClientTransport())
        {
            @Override
            public ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory.Client sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(getByteBufferPool(), getExecutor(), getSslContextFactory(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                        {
                            private final AtomicInteger unwraps = new AtomicInteger();

                            @Override
                            protected SSLEngineResult unwrap(SSLEngine sslEngine, ByteBuffer input, ByteBuffer output) throws SSLException
                            {
                                if (unwraps.incrementAndGet() == unwrapCount)
                                    action.accept(sslEngine);
                                return super.unwrap(sslEngine, input, output);
                            }
                        };
                    }
                };
            }
        };
        ScheduledThreadPoolExecutor schedulerService = new ScheduledThreadPoolExecutor(1);
        schedulerService.setRemoveOnCancelPolicy(true);
        client.setScheduler(new ScheduledExecutorScheduler(schedulerService));
        client.start();

        int count = 10;
        for (int i = 0; i < count; ++i)
        {
            assertThrows(ExecutionException.class, () -> client.newRequest(newURI(transportType))
                .timeout(5, TimeUnit.SECONDS)
                .send()
            );
        }

        // There should be a task scheduled by HttpDestination
        // to expire HttpExchanges while they are queued.
        // There may be also a task for the idle timeout of the connection.
        List<Runnable> tasks = schedulerService.shutdownNow();
        assertThat(tasks.toString(), tasks.size(), allOf(greaterThan(0), lessThan(count + 1)));
    }

    @FunctionalInterface
    private interface TLSHandshakeAction
    {
        void accept(SSLEngine sslEngine) throws SSLException;
    }
}
