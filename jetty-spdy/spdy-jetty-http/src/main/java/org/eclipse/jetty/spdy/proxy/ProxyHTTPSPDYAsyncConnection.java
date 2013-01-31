//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.server.AsyncHttpConnection;
import org.eclipse.jetty.spdy.ISession;
import org.eclipse.jetty.spdy.IStream;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.spdy.StandardStream;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Handler;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.SessionStatus;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;

public class ProxyHTTPSPDYAsyncConnection extends AsyncHttpConnection
{
    private final Headers headers = new Headers();
    private final short version;
    private final ProxyEngineSelector proxyEngineSelector;
    private final HttpGenerator generator;
    private final ISession session;
    private HTTPStream stream;
    private Buffer content;

    public ProxyHTTPSPDYAsyncConnection(SPDYServerConnector connector, EndPoint endPoint, short version, ProxyEngineSelector proxyEngineSelector)
    {
        super(connector, endPoint, connector.getServer());
        this.version = version;
        this.proxyEngineSelector = proxyEngineSelector;
        this.generator = (HttpGenerator)_generator;
        this.session = new HTTPSession(version, connector);
        this.session.setAttribute("org.eclipse.jetty.spdy.remoteAddress", endPoint.getRemoteAddr());
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint)super.getEndPoint();
    }

    @Override
    protected void startRequest(Buffer method, Buffer uri, Buffer httpVersion) throws IOException
    {
        SPDYServerConnector connector = (SPDYServerConnector)getConnector();
        String scheme = connector.getSslContextFactory() != null ? "https" : "http";
        headers.put(HTTPSPDYHeader.SCHEME.name(version), scheme);
        headers.put(HTTPSPDYHeader.METHOD.name(version), method.toString("UTF-8"));
        headers.put(HTTPSPDYHeader.URI.name(version), uri.toString("UTF-8"));
        headers.put(HTTPSPDYHeader.VERSION.name(version), httpVersion.toString("UTF-8"));
    }

    @Override
    protected void parsedHeader(Buffer name, Buffer value) throws IOException
    {
        String headerName = name.toString("UTF-8").toLowerCase(Locale.ENGLISH);
        String headerValue = value.toString("UTF-8");
        switch (headerName)
        {
            case "host":
                headers.put(HTTPSPDYHeader.HOST.name(version), headerValue);
                break;
            default:
                headers.put(headerName, headerValue);
                break;
        }
    }

    @Override
    protected void headerComplete() throws IOException
    {
    }

    @Override
    protected void content(Buffer buffer) throws IOException
    {
        if (content == null)
        {
            stream = syn(false);
            content = buffer;
        }
        else
        {
            stream.getStreamFrameListener().onData(stream, toDataInfo(buffer, false));
        }
    }

    @Override
    public void messageComplete(long contentLength) throws IOException
    {
        if (stream == null)
        {
            assert content == null;
            if (headers.isEmpty())
                proxyEngineSelector.onGoAway(session, new GoAwayInfo(0, SessionStatus.OK));
            else
                syn(true);
        }
        else
        {
            stream.getStreamFrameListener().onData(stream, toDataInfo(content, true));
        }
        headers.clear();
        stream = null;
        content = null;
    }

    private HTTPStream syn(boolean close)
    {
        HTTPStream stream = new HTTPStream(1, (byte)0, session, null);
        StreamFrameListener streamFrameListener = proxyEngineSelector.onSyn(stream, new SynInfo(headers, close));
        stream.setStreamFrameListener(streamFrameListener);
        return stream;
    }

    private DataInfo toDataInfo(Buffer buffer, boolean close)
    {
        if (buffer instanceof ByteArrayBuffer)
            return new BytesDataInfo(buffer.array(), buffer.getIndex(), buffer.length(), close);

        if (buffer instanceof NIOBuffer)
        {
            ByteBuffer byteBuffer = ((NIOBuffer)buffer).getByteBuffer();
            byteBuffer.limit(buffer.putIndex());
            byteBuffer.position(buffer.getIndex());
            return new ByteBufferDataInfo(byteBuffer, close);
        }

        return new BytesDataInfo(buffer.asArray(), close);
    }

    private class HTTPSession extends StandardSession
    {
        private HTTPSession(short version, SPDYServerConnector connector)
        {
            super(version, connector.getByteBufferPool(), connector.getExecutor(), connector.getScheduler(), null, null, 1, proxyEngineSelector, null, null);
        }

        @Override
        public void rst(RstInfo rstInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            // Not much we can do in HTTP land: just close the connection
            goAway(timeout, unit, handler);
        }

        @Override
        public void goAway(long timeout, TimeUnit unit, Handler<Void> handler)
        {
            try
            {
                getEndPoint().close();
                handler.completed(null);
            }
            catch (IOException x)
            {
                handler.failed(null, x);
            }
        }
    }

    /**
     * <p>This stream will convert the SPDY invocations performed by the proxy into HTTP to be sent to the client.</p>
     */
    private class HTTPStream extends StandardStream
    {
        private final Pattern statusRegexp = Pattern.compile("(\\d{3})\\s*(.*)");

        private HTTPStream(int id, byte priority, ISession session, IStream associatedStream)
        {
            super(id, priority, session, associatedStream);
        }

        @Override
        public void syn(SynInfo synInfo, long timeout, TimeUnit unit, Handler<Stream> handler)
        {
            // HTTP does not support pushed streams
            handler.completed(new HTTPPushStream(2, getPriority(), getSession(), this));
        }

        @Override
        public void headers(HeadersInfo headersInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            // TODO
            throw new UnsupportedOperationException("Not Yet Implemented");
        }

        @Override
        public void reply(ReplyInfo replyInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            try
            {
                Headers headers = new Headers(replyInfo.getHeaders(), false);

                headers.remove(HTTPSPDYHeader.SCHEME.name(version));

                String status = headers.remove(HTTPSPDYHeader.STATUS.name(version)).value();
                Matcher matcher = statusRegexp.matcher(status);
                matcher.matches();
                int code = Integer.parseInt(matcher.group(1));
                String reason = matcher.group(2);
                generator.setResponse(code, reason);

                String httpVersion = headers.remove(HTTPSPDYHeader.VERSION.name(version)).value();
                generator.setVersion(Integer.parseInt(httpVersion.replaceAll("\\D", "")));

                Headers.Header host = headers.remove(HTTPSPDYHeader.HOST.name(version));
                if (host != null)
                    headers.put("host", host.value());

                HttpFields fields = new HttpFields();
                for (Headers.Header header : headers)
                {
                    String name = camelize(header.name());
                    fields.put(name, header.value());
                }
                generator.completeHeader(fields, replyInfo.isClose());

                if (replyInfo.isClose())
                    complete();

                handler.completed(null);
            }
            catch (IOException x)
            {
                handler.failed(null, x);
            }
        }

        private String camelize(String name)
        {
            char[] chars = name.toCharArray();
            chars[0] = Character.toUpperCase(chars[0]);

            for (int i = 0; i < chars.length; ++i)
            {
                char c = chars[i];
                int j = i + 1;
                if (c == '-' && j < chars.length)
                    chars[j] = Character.toUpperCase(chars[j]);
            }
            return new String(chars);
        }

        @Override
        public void data(DataInfo dataInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            try
            {
                // Data buffer must be copied, as the ByteBuffer is pooled
                ByteBuffer byteBuffer = dataInfo.asByteBuffer(false);

                Buffer buffer = byteBuffer.isDirect() ?
                        new DirectNIOBuffer(byteBuffer, false) :
                        new IndirectNIOBuffer(byteBuffer, false);

                generator.addContent(buffer, dataInfo.isClose());
                generator.flush(unit.toMillis(timeout));

                if (dataInfo.isClose())
                    complete();

                handler.completed(null);
            }
            catch (IOException x)
            {
                handler.failed(null, x);
            }
        }

        private void complete() throws IOException
        {
            generator.complete();
            // We need to call asyncDispatch() as if the HTTP request
            // has been suspended and now we complete the response
            getEndPoint().asyncDispatch();
        }
    }

    private class HTTPPushStream extends StandardStream
    {
        private HTTPPushStream(int id, byte priority, ISession session, IStream associatedStream)
        {
            super(id, priority, session, associatedStream);
        }

        @Override
        public void headers(HeadersInfo headersInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            // Ignore pushed headers
            handler.completed(null);
        }

        @Override
        public void data(DataInfo dataInfo, long timeout, TimeUnit unit, Handler<Void> handler)
        {
            // Ignore pushed data
            handler.completed(null);
        }
    }
}
