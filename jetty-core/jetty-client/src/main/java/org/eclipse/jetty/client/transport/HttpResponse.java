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

package org.eclipse.jetty.client.transport;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class HttpResponse implements Response
{
    private final HttpFields.Mutable headers = HttpFields.build();
    private final Request request;
    private HttpVersion version;
    private int status;
    private String reason;
    private HttpFields.Mutable trailers;

    public HttpResponse(Request request)
    {
        this.request = request;
    }

    @Override
    public Request getRequest()
    {
        return request;
    }

    @Override
    public HttpVersion getVersion()
    {
        return version;
    }

    public HttpResponse version(HttpVersion version)
    {
        this.version = version;
        return this;
    }

    @Override
    public int getStatus()
    {
        return status;
    }

    public HttpResponse status(int status)
    {
        this.status = status;
        return this;
    }

    @Override
    public String getReason()
    {
        return reason;
    }

    public HttpResponse reason(String reason)
    {
        this.reason = reason;
        return this;
    }

    @Override
    public HttpFields getHeaders()
    {
        return headers.asImmutable();
    }

    public void clearHeaders()
    {
        headers.clear();
    }

    public HttpResponse addHeader(HttpField header)
    {
        headers.add(header);
        return this;
    }

    public HttpResponse headers(Consumer<HttpFields.Mutable> consumer)
    {
        consumer.accept(headers);
        return this;
    }

    @Override
    public HttpFields getTrailers()
    {
        return trailers == null ? null : trailers.asImmutable();
    }

    public HttpResponse trailer(HttpField trailer)
    {
        if (trailers == null)
            trailers = HttpFields.build();
        trailers.add(trailer);
        return this;
    }

    @Override
    public CompletableFuture<Boolean> abort(Throwable cause)
    {
        return request.abort(cause);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %d %s]@%x", HttpResponse.class.getSimpleName(), getVersion(), getStatus(), getReason(), hashCode());
    }
}
