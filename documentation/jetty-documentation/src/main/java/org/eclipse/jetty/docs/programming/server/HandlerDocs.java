//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Flow;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.thread.SerializedInvoker;

@SuppressWarnings("unused")
public class HandlerDocs
{
    public static class HelloHandler0 extends Handler.Abstract
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            return (req, response, callback) ->
            {
                response.setStatus(200);
                response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
                response.write(true, BufferUtil.toBuffer("Hello World\n"), callback);
            };
        }
    }

    public static class HelloHandler1 extends Handler.Abstract
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            return this::process;
        }

        private void process(Request request, Response response, Callback callback)
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
            response.write(true, BufferUtil.toBuffer("Hello World\n"), callback);
        }
    }

    public static class HelloHandler2 extends Handler.Processor.NonBlocking
    {
        @Override
        public void process(Request request, Response response, Callback callback)
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
            response.write(true, BufferUtil.toBuffer("Hello World\n"), callback);
        }
    }

    public static class HelloHandler3 extends Handler.Processor.NonBlocking
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws IOException
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
            Blocker.Shared blocker = new Blocker.Shared();
            try (Blocker.Callback cb = blocker.callback())
            {
                response.write(true, BufferUtil.toBuffer("Hello "), callback);
                cb.block();
            }
            try (Blocker.Callback cb = blocker.callback())
            {
                response.write(true, BufferUtil.toBuffer("World\n"), callback);
                cb.block();
            }
            callback.succeeded();
        }
    }

    public static class HelloHandler4 extends Handler.Processor.Blocking
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws IOException
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
            try (PrintStream out = new PrintStream(Content.Sink.asOutputStream(response)))
            {
                out.print("Hello ");
                out.println("World");
                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }

    public static class HelloHandler5 extends Handler.Processor.NonBlocking
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws IOException
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
            new HelloWorldPublisher().subscribe(Content.Sink.asSubscriber(response, callback));
        }
    }

    public static class HelloWorldPublisher implements Flow.Publisher<Content.Chunk>
    {
        @Override
        public void subscribe(Flow.Subscriber<? super Content.Chunk> subscriber)
        {
            final SerializedInvoker invoker = new SerializedInvoker();
            final Queue<Content.Chunk> chunks = new LinkedList<>(List.of(
                Content.Chunk.from(BufferUtil.toBuffer("Hello "), false),
                Content.Chunk.from(BufferUtil.toBuffer("World "), false),
                Content.Chunk.EOF));

            subscriber.onSubscribe(new Flow.Subscription()
            {
                @Override
                public void request(long n)
                {
                    while (n-- > 0 && !chunks.isEmpty())
                        invoker.run(() -> subscriber.onNext(chunks.poll()));
                }

                @Override
                public void cancel()
                {
                    subscriber.onNext(Content.Chunk.from(new IOException("Cancelled")));
                }
            });
        }
    }

    public static class DescriminatingGreeterHandler extends Handler.Processor.NonBlocking
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            if (HttpMethod.GET.is(request.getMethod()) &&
                "greeting".equals(request.getPathInContext()))
                return this;
            return null;
        }

        @Override
        public void process(Request request, Response response, Callback callback)
        {
            response.setStatus(200);
            response.getHeaders().add(HttpHeader.CONTENT_LENGTH, "text/plain");
            response.write(true, BufferUtil.toBuffer("Hello World\n"), callback);
        }
    }

    public static class EchoHandler extends Handler.Processor.NonBlocking
    {
        @Override
        public void process(Request request, Response response, Callback callback)
        {
            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, request.getHeaders().get(HttpHeader.CONTENT_TYPE));

            long contentLength = request.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            if (contentLength >= 0)
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, contentLength);

            Content.copy(request, response, callback);
        }
    }

    public static class RootHandler extends Handler.Collection
    {
        @Override
        public Request.Processor handle(Request request) throws Exception
        {
            final StringBuilder index = new StringBuilder();
            index.append("<h2>Handler Demos</h2>\n<ul>\n");

            for (Handler handler : getHandlers())
            {
                String name = handler.getClass().getSimpleName().replace("Handler", "");
                String path = "/" + name;
                if (request.getPathInContext().equals(name))
                {
                    Request.Processor processor = handler.handle(request);
                    if (processor != null)
                        return processor;
                }
                index.append("<li><a href=\"")
                    .append(URIUtil.addPaths(request.getContext().getContextPath(), path))
                    .append("\">")
                    .append(name)
                    .append("</a></li>\n");
            }

            index.append("</ul>");
            return (req, res, callback) ->
            {
                res.setStatus(200);
                res.getHeaders().add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_HTML_UTF_8.asString());
                Content.Sink.write(res, true, index.toString(), callback);
            };
        }
    }
}
