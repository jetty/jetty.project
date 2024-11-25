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

import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectListenerTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsTCP")
    public void testBeginSuccessBlocking(Transport transport) throws Exception
    {
        testBeginSuccess(transport, true);
    }

    @ParameterizedTest
    @MethodSource("transportsTCP")
    public void testBeginSuccessNonBlocking(Transport transport) throws Exception
    {
        testBeginSuccess(transport, false);
    }

    private void testBeginSuccess(Transport transport, boolean blocking) throws Exception
    {
        startServer(transport, new EmptyServerHandler());
        prepareClient(transport);
        client.setConnectBlocking(blocking);
        CountDownLatch beginLatch = new CountDownLatch(1);
        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        clientConnector.addEventListener(new ClientConnector.ConnectListener()
        {
            @Override
            public void onConnectBegin(SocketChannel socketChannel, SocketAddress socketAddress)
            {
                beginLatch.countDown();
            }

            @Override
            public void onConnectSuccess(SocketChannel socketChannel)
            {
                successLatch.countDown();
            }

            @Override
            public void onConnectFailure(SocketChannel socketChannel, SocketAddress socketAddress, Throwable failure)
            {
                failureLatch.countDown();
            }
        });
        client.start();

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, failureLatch.getCount());
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsTCP")
    public void testBeginFailureBlocking(Transport transport) throws Exception
    {
        testBeginFailure(transport, true);
    }

    @ParameterizedTest
    @MethodSource("transportsTCP")
    public void testBeginFailureNonBlocking(Transport transport) throws Exception
    {
        testBeginFailure(transport, false);
    }

    public void testBeginFailure(Transport transport, boolean blocking) throws Exception
    {
        startServer(transport, new EmptyServerHandler());
        prepareClient(transport);
        client.setConnectBlocking(blocking);
        CountDownLatch beginLatch = new CountDownLatch(1);
        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        clientConnector.addEventListener(new ClientConnector.ConnectListener()
        {
            @Override
            public void onConnectBegin(SocketChannel socketChannel, SocketAddress socketAddress)
            {
                beginLatch.countDown();
            }

            @Override
            public void onConnectSuccess(SocketChannel socketChannel)
            {
                successLatch.countDown();
            }

            @Override
            public void onConnectFailure(SocketChannel socketChannel, SocketAddress socketAddress, Throwable failure)
            {
                failureLatch.countDown();
            }
        });
        client.start();

        URI uri = newURI(transport);
        // Use a different port to fail the TCP connect.
        URI badURI = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), freePort(), uri.getPath(), uri.getQuery(), uri.getFragment());
        ExecutionException failure = assertThrows(ExecutionException.class, () -> client.newRequest(badURI)
            .timeout(5, TimeUnit.SECONDS)
            .send());
        assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, successLatch.getCount());
        assertThat(failure.getCause(), Matchers.instanceOf(ConnectException.class));
    }
}
