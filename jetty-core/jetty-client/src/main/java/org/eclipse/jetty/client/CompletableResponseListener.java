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

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.internal.HttpContentResponse;

/**
 * <p>A {@link BufferingResponseListener} that sends a {@link Request}
 * and returns a {@link CompletableFuture} that is completed when
 * {@link #onComplete(Result)} is called.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * var request = client.newRequest(...)...;
 * CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request).send();
 *
 * // Attach actions that run when the request/response is complete.
 * completable.thenApply(response -> ...)
 *     .whenComplete((response, failure) -> ...);
 *
 * // Possibly even block waiting for the response.
 * ContentResponse response = completable.get(5, TimeUnit.SECONDS);
 * }</pre>
 */
public class CompletableResponseListener extends BufferingResponseListener
{
    private final CompletableFuture<ContentResponse> completable = new CompletableFuture<>();
    private final Request request;

    public CompletableResponseListener(Request request)
    {
        this(request, 2 * 1024 * 1024);
    }

    public CompletableResponseListener(Request request, int maxLength)
    {
        super(maxLength);
        this.request = request;
        this.completable.whenComplete(this::handleExternalFailure);
    }

    public Request getRequest()
    {
        return request;
    }

    private void handleExternalFailure(ContentResponse response, Throwable failure)
    {
        // External failures applied to the CompletableFuture,
        // such as timeouts or cancel(), must abort the request.
        if (failure != null)
            request.abort(failure);
    }

    /**
     * <p>Sends the request asynchronously and returns a {@link CompletableFuture}
     * that is completed when the request/response completes.</p>
     *
     * @return a {@link CompletableFuture} that is completed when the request/response completes
     * @see Request#send(Response.CompleteListener)
     */
    public CompletableFuture<ContentResponse> send()
    {
        request.send(this);
        return completable;
    }

    /**
     * <p>Sends the request asynchronously via the given {@link Destination} and returns
     * a {@link CompletableFuture} that is completed when the request/response completes.</p>
     *
     * @param destination the destination to send the request to
     * @return a {@link CompletableFuture} that is completed when the request/response completes
     * @see Destination#send(Request, Response.CompleteListener)
     */
    public CompletableFuture<ContentResponse> send(Destination destination)
    {
        destination.send(request, this);
        return completable;
    }

    /**
     * <p>Sends the request asynchronously via the given {@link Connection} and returns
     * a {@link CompletableFuture} that is completed when the request/response completes.</p>
     *
     * @param connection the connection to send the request to
     * @return a {@link CompletableFuture} that is completed when the request/response completes
     * @see Connection#send(Request, Response.CompleteListener)
     */
    public CompletableFuture<ContentResponse> send(Connection connection)
    {
        connection.send(request, this);
        return completable;
    }

    @Override
    public void onComplete(Result result)
    {
        if (result.isFailed())
            completable.completeExceptionally(result.getFailure());
        else
            completable.complete(new HttpContentResponse(result.getResponse(), getContent(), getMediaType(), getEncoding()));
    }
}
