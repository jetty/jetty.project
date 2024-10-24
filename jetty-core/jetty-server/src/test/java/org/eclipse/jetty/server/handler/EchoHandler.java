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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class EchoHandler extends Handler.Abstract
{
    public EchoHandler()
    {
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback)
    {
        response.setStatus(200);

        long contentLength = -1;
        for (HttpField field : request.getHeaders())
        {
            if (field.getHeader() != null)
            {
                switch (field.getHeader())
                {
                    case CONTENT_LENGTH ->
                    {
                        response.getHeaders().add(field);
                        contentLength = field.getLongValue();
                    }
                    case CONTENT_TYPE -> response.getHeaders().add(field);
                    case TRAILER -> response.setTrailersSupplier(HttpFields.build());
                    case TRANSFER_ENCODING -> contentLength = Long.MAX_VALUE;
                }
            }
        }

        if (contentLength > 0)
            copy(request, response, callback);
        else
            callback.succeeded();
        return true;
    }

    protected void copy(Request request, Response response, Callback callback)
    {
        Content.copy(request, response, Response.newTrailersChunkProcessor(response), callback);
    }

    public static class Reactive extends EchoHandler
    {
        @Override
        protected void copy(Request request, Response response, Callback callback)
        {
            Content.Source.asPublisher(request).subscribe(Content.Sink.asSubscriber(response, callback));
        }
    }

    public static class Stream extends EchoHandler
    {
        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.BLOCKING;
        }

        @Override
        protected void copy(Request request, Response response, Callback callback)
        {
            try
            {
                IO.copy(Content.Source.asInputStream(request), Content.Sink.asOutputStream(response));
                callback.succeeded();
            }
            catch (IOException e)
            {
                callback.failed(e);
            }
        }
    }

    public static class Buffered extends EchoHandler
    {
        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.BLOCKING;
        }

        @Override
        protected void copy(Request request, Response response, Callback callback)
        {
            try
            {
                response.write(true, Content.Source.asByteBuffer(request), callback);
            }
            catch (IOException e)
            {
                callback.failed(e);
            }
        }
    }

    public static class BufferedAsync extends EchoHandler
    {
        @Override
        protected void copy(Request request, Response response, Callback callback)
        {
            Content.Source.asByteBuffer(request, new Promise<>()
            {
                @Override
                public void succeeded(ByteBuffer byteBuffer)
                {
                    response.write(true, byteBuffer, callback);
                }

                @Override
                public void failed(Throwable x)
                {
                    callback.failed(x);
                }
            });
        }
    }
}
