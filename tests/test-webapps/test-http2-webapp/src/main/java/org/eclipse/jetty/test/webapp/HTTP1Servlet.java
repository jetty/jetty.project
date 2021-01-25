//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.test.webapp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
        http2Client.connect(sslContextFactory, new InetSocketAddress(host, port), new Session.Listener.Adapter(), new Promise<Session>()
        {
            @Override
            public void succeeded(Session session)
            {
                HttpURI uri = new HttpURI(request.getScheme(), host, port, contextPath + "/h2");
                MetaData.Request metaData = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_2, new HttpFields());
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
                }, new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        try
                        {
                            ByteBuffer buffer = frame.getData();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            output.write(bytes);
                            callback.succeeded();
                            if (frame.isEndStream())
                                asyncContext.complete();
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
