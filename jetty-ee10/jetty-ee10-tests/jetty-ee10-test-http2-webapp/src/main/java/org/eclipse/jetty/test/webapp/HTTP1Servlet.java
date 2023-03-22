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

package org.eclipse.jetty.test.webapp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTP1Servlet extends HttpServlet
{
    private SslContextFactory sslContextFactory;
    private HTTP2Client http2Client;

    @Override
    public void init() throws ServletException
    {
        try
        {
            sslContextFactory = new SslContextFactory.Client(true);
            http2Client = new HTTP2Client();
            http2Client.addBean(sslContextFactory);
            http2Client.start();
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    @Override
    public void destroy()
    {
        try
        {
            http2Client.stop();
        }
        catch (Exception x)
        {
            x.printStackTrace();
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String host = "localhost";
        int port = request.getServerPort();
        String contextPath = request.getContextPath();
        ServletOutputStream output = response.getOutputStream();
        AsyncContext asyncContext = request.startAsync();
        http2Client.connect(sslContextFactory, new InetSocketAddress(host, port), new Session.Listener() {}, new Promise<Session>()
        {
            @Override
            public void succeeded(Session session)
            {
                HttpURI uri = HttpURI.from(request.getScheme(), host, port, contextPath + "/h2");
                MetaData.Request metaData = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_2, HttpFields.EMPTY);
                HeadersFrame frame = new HeadersFrame(metaData, null, true);
                session.newStream(frame, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        response.setHeader("X-Failure", "stream");
                        asyncContext.complete();
                    }
                }, new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        try
                        {
                            // Read a chunk of the content.
                            Stream.Data data = stream.readData();
                            if (data == null)
                            {
                                // No data available now, demand to be called back.
                                stream.demand();
                            }
                            else
                            {
                                // Process the content.
                                ByteBuffer buffer = data.frame().getByteBuffer();
                                byte[] bytes = new byte[buffer.remaining()];
                                buffer.get(bytes);
                                output.write(bytes);
                                // Notify that the content has been consumed.
                                data.release();
                                if (!data.frame().isEndStream())
                                {
                                    // Demand to be called back.
                                    stream.demand();
                                }
                                else
                                    asyncContext.complete();
                            }
                        }
                        catch (IOException x)
                        {
                            asyncContext.complete();
                        }
                    }
                });
            }

            @Override
            public void failed(Throwable x)
            {
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                response.setHeader("X-Failure", "session");
                asyncContext.complete();
            }
        });
    }
}
