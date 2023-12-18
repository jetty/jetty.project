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

package org.eclipse.jetty.client;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CompletableResponseListenerTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSend(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();
        ContentResponse response = completable.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSendDestination(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        Destination destination = client.resolveDestination(request);

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send(destination);
        ContentResponse response = completable.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSendConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        Destination destination = client.resolveDestination(request);
        Connection connection = destination.newConnection().get();

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send(connection);
        ContentResponse response = completable.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbort(Scenario scenario) throws Exception
    {
        long delay = 1000;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                // Delay the response.
                Thread.sleep(delay);
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();

        // Wait and then abort().
        Thread.sleep(delay / 2);
        Throwable failure = new Throwable();
        CompletableFuture<Boolean> abortCompletable = request.abort(failure);

        CompletableFuture<Void> combinedCompletable = completable.thenCombine(abortCompletable, (response, aborted) -> null);

        // There should be no response.
        ExecutionException executionFailure = assertThrows(ExecutionException.class, () -> combinedCompletable.get(5, TimeUnit.SECONDS));
        assertThat(executionFailure.getCause(), sameInstance(failure));

        // Trying to abort again should return false.
        assertFalse(request.abort(new Throwable()).get(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCompletableFutureTimeout(Scenario scenario) throws Exception
    {
        long delay = 1000;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                // Delay the response.
                Thread.sleep(delay);
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        // Add a timeout to fail the request.
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send()
            .orTimeout(delay / 2, TimeUnit.MILLISECONDS);

        // There should be no response.
        ExecutionException failure = assertThrows(ExecutionException.class, () -> completable.get(5, TimeUnit.SECONDS));
        assertThat(failure.getCause(), instanceOf(TimeoutException.class));

        // Trying to abort again should return false.
        assertFalse(request.abort(new Throwable()).get(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCompletableFutureCancel(Scenario scenario) throws Exception
    {
        long delay = 1000;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                // Delay the response.
                Thread.sleep(delay);
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();

        // Wait and then cancel().
        Thread.sleep(delay / 2);
        assertTrue(completable.cancel(false));

        // There should be no response.
        assertThrows(CancellationException.class, () -> completable.get(5, TimeUnit.SECONDS));

        // Trying to abort again should return false.
        assertFalse(request.abort(new Throwable()).get(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testCompletableFutureCompletedExceptionally(Scenario scenario) throws Exception
    {
        long delay = 1000;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                // Delay the response.
                Thread.sleep(delay);
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme());

        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();

        // Wait and then completeExceptionally().
        Thread.sleep(delay / 2);
        Throwable failure = new Throwable();
        assertTrue(completable.completeExceptionally(failure));

        // There should be no response.
        ExecutionException executionFailure = assertThrows(ExecutionException.class, () -> completable.get(5, TimeUnit.SECONDS));
        assertThat(executionFailure.getCause(), sameInstance(failure));

        // Trying to abort again should return false.
        assertFalse(request.abort(new Throwable()).get(5, TimeUnit.SECONDS));
    }
}
