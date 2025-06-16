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
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.TypeUtil;

public class HttpResponse implements MutableResponse
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

    @Override
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

    @Override
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

    @Override
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

    @Override
    public HttpResponse addHeader(HttpField header)
    {
        headers.add(header);
        return this;
    }

    @Override
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

    @Override
    public HttpResponse addTrailer(HttpField trailer)
    {
        if (trailers == null)
            trailers = HttpFields.build();
        trailers.add(trailer);
        return this;
    }

    @Override
    public HttpResponse trailers(Consumer<HttpFields.Mutable> consumer)
    {
        if (trailers == null)
            trailers = HttpFields.build();
        consumer.accept(trailers);
        return this;
    }

    @Override
    public CompletableFuture<Boolean> abort(Throwable cause)
    {
        return getRequest().abort(cause);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %d %s]@%x", TypeUtil.toShortName(HttpResponse.class), getVersion(), getStatus(), getReason(), hashCode());
    }

    static class Wrapper implements MutableResponse
    {
        private final Request request;
        private final MutableResponse response;

        Wrapper(Request request, MutableResponse response)
        {
            this.request = request;
            this.response = response;
        }

        @Override
        public Request getRequest()
        {
            return request;
        }

        @Override
        public HttpVersion getVersion()
        {
            return response.getVersion();
        }

        @Override
        public MutableResponse version(HttpVersion version)
        {
            return response.version(version);
        }

        @Override
        public int getStatus()
        {
            return response.getStatus();
        }

        @Override
        public MutableResponse status(int status)
        {
            return response.status(status);
        }

        @Override
        public String getReason()
        {
            return response.getReason();
        }

        @Override
        public MutableResponse reason(String reason)
        {
            return response.reason(reason);
        }

        @Override
        public HttpFields getHeaders()
        {
            return response.getHeaders();
        }

        @Override
        public MutableResponse addHeader(HttpField header)
        {
            return response.addHeader(header);
        }

        @Override
        public MutableResponse headers(Consumer<HttpFields.Mutable> consumer)
        {
            return response.headers(consumer);
        }

        @Override
        public HttpFields getTrailers()
        {
            return response.getTrailers();
        }

        @Override
        public MutableResponse addTrailer(HttpField trailer)
        {
            return response.addTrailer(trailer);
        }

        @Override
        public MutableResponse trailers(Consumer<HttpFields.Mutable> consumer)
        {
            return response.trailers(consumer);
        }

        @Override
        public CompletableFuture<Boolean> abort(Throwable cause)
        {
            return response.abort(cause);
        }
    }
}
