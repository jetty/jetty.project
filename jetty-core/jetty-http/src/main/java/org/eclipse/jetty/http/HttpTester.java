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

package org.eclipse.jetty.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;

/**
 * <p>HTTP Testing helper class.</p>
 * <p>Example usage:</p>
 * <pre>{@code
 * try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("www.google.com",80)))
 * {
 *     HttpTester.Request request = HttpTester.newRequest();
 *     request.setMethod("POST");
 *     request.setURI("/search");
 *     request.setVersion(HttpVersion.HTTP_1_0);
 *     request.put(HttpHeader.HOST, "www.google.com");
 *     request.put("Content-Type", "application/x-www-form-urlencoded");
 *     request.setContent("q=jetty%20server");
 *
 *     ByteBuffer output = request.generate();
 *     channel.write(output);

 *     HttpTester.Response response = HttpTester.parseResponse(channel);
 *     System.err.printf("%s %s %s%n", response.getVersion(), response.getStatus(), response.getReason());
 *     for (HttpField field : response)
 *     {
 *         System.err.printf("%s: %s%n", field.getName(), field.getValue());
 *     }
 *     System.err.printf("%n%s%n", response.getContent());
 * }
 * }</pre>
 */
public class HttpTester
{
    public abstract static class Input
    {
        protected final ByteBuffer _buffer;
        protected boolean _eof = false;
        protected HttpParser _parser;

        public Input()
        {
            this(BufferUtil.allocate(8192));
        }

        Input(ByteBuffer buffer)
        {
            _buffer = buffer;
        }

        public ByteBuffer getBuffer()
        {
            return _buffer;
        }

        public void setHttpParser(HttpParser parser)
        {
            _parser = parser;
        }

        public HttpParser takeHttpParser()
        {
            HttpParser p = _parser;
            _parser = null;
            return p;
        }

        public boolean isEOF()
        {
            return BufferUtil.isEmpty(_buffer) && _eof;
        }

        public abstract int fillBuffer() throws IOException;
    }

    public static Input from(String string)
    {
        return from(BufferUtil.toBuffer(string));
    }

    public static Input from(ByteBuffer data)
    {
        return new Input(data)
        {
            @Override
            public int fillBuffer()
            {
                _eof = true;
                return -1;
            }
        };
    }

    public static Input from(InputStream stream)
    {
        return new Input()
        {
            @Override
            public int fillBuffer() throws IOException
            {
                BufferUtil.compact(_buffer);
                int len = stream.read(_buffer.array(), _buffer.arrayOffset() + _buffer.limit(), BufferUtil.space(_buffer));
                if (len < 0)
                    _eof = true;
                else
                    _buffer.limit(_buffer.limit() + len);
                return len;
            }
        };
    }

    public static Input from(ReadableByteChannel channel)
    {
        return new Input()
        {
            @Override
            public int fillBuffer() throws IOException
            {
                BufferUtil.compact(_buffer);
                int pos = BufferUtil.flipToFill(_buffer);
                int len = channel.read(_buffer);
                if (len < 0)
                    _eof = true;
                BufferUtil.flipToFlush(_buffer, pos);
                return len;
            }
        };
    }

    public static Request newRequest()
    {
        Request r = new Request();
        r.setMethod(HttpMethod.GET.asString());
        r.setURI("/");
        r.setVersion(HttpVersion.HTTP_1_1);
        r.setHeader("Host", "localhost");
        return r;
    }

    public static Request parseRequest(String request)
    {
        return parseRequest(BufferUtil.toBuffer(request));
    }

    public static Request parseRequest(ByteBuffer buffer)
    {
        try
        {
            return parseRequest(from(buffer));
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    public static Request parseRequest(InputStream stream) throws IOException
    {
        return parseRequest(from(stream));
    }

    public static Request parseRequest(ReadableByteChannel channel) throws IOException
    {
        return parseRequest(from(channel));
    }

    public static Request parseRequest(Input input) throws IOException
    {
        Request request;
        HttpParser parser = input.takeHttpParser();
        if (parser != null)
        {
            request = (Request)parser.getHandler();
        }
        else
        {
            request = newRequest();
            parser = new HttpParser(request);
        }
        parse(input, parser);
        if (request.isComplete())
            return request;
        input.setHttpParser(parser);
        return null;
    }

    public static Response parseHeadResponse(String response)
    {
        return parseResponse(response, true);
    }

    public static Response parseResponse(String response)
    {
        Response r = new Response();
        HttpParser parser = new HttpParser(r);
        parser.parseNext(BufferUtil.toBuffer(response));
        return r;
    }

    private static Response parseResponse(String response, boolean head)
    {
        return parseResponse(BufferUtil.toBuffer(response), head);
    }

    public static Response parseHeadResponse(ByteBuffer response)
    {
        return parseResponse(response, true);
    }

    public static Response parseResponse(ByteBuffer response)
    {
        return parseResponse(response, false);
    }

    private static Response parseResponse(ByteBuffer response, boolean head)
    {
        try
        {
            return parseResponse(from(response), head);
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    public static Response parseResponse(InputStream stream) throws IOException
    {
        return parseResponse(from(stream));
    }

    public static Response parseResponse(ReadableByteChannel channel) throws IOException
    {
        return parseResponse(from(channel));
    }

    public static Response parseResponse(Input input) throws IOException
    {
        return parseResponse(input, false);
    }

    public static Response parseResponse(Input input, boolean head) throws IOException
    {
        Response response;
        HttpParser parser = input.takeHttpParser();
        if (parser != null)
        {
            response = (Response)parser.getHandler();
        }
        else
        {
            response = new Response();
            parser = new HttpParser(response);
        }
        parser.setHeadResponse(head);
        parse(input, parser);
        if (response.isComplete())
            return response;
        input.setHttpParser(parser);
        return null;
    }

    private static void parse(Input input, HttpParser parser) throws IOException
    {
        ByteBuffer buffer = input.getBuffer();

        while (true)
        {
            if (BufferUtil.hasContent(buffer))
            {
                if (parser.parseNext(buffer))
                    break;
            }
            int len = input.fillBuffer();
            if (len == 0)
                break;
            if (len < 0)
            {
                parser.atEOF();
                parser.parseNext(buffer);
                break;
            }
        }
    }

    private HttpTester()
    {
    }

    public abstract static class Message extends MutableHttpFields implements HttpParser.HttpHandler
    {
        boolean _earlyEOF;
        boolean _complete = false;
        ByteArrayOutputStream _content;
        HttpVersion _version = HttpVersion.HTTP_1_0;

        public boolean isComplete()
        {
            return _complete;
        }

        public HttpVersion getVersion()
        {
            return _version;
        }

        public void setVersion(String version)
        {
            setVersion(HttpVersion.CACHE.get(version));
        }

        public void setVersion(HttpVersion version)
        {
            _version = version;
        }

        public void setContent(byte[] bytes)
        {
            try
            {
                _content = new ByteArrayOutputStream();
                _content.write(bytes);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void setContent(String content)
        {
            try
            {
                _content = new ByteArrayOutputStream();
                _content.write(StringUtil.getBytes(content));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void setContent(ByteBuffer content)
        {
            try
            {
                _content = new ByteArrayOutputStream();
                _content.write(BufferUtil.toArray(content));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public byte[] getContentBytes()
        {
            if (_content == null)
                return null;
            return _content.toByteArray();
        }

        public ByteBuffer getContentByteBuffer()
        {
            return ByteBuffer.wrap(getContentBytes());
        }

        public String getContent()
        {
            if (_content == null)
                return null;

            String contentType = get(HttpHeader.CONTENT_TYPE);
            String encoding = MimeTypes.getCharsetFromContentType(contentType);
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);

            return _content.toString(charset);
        }

        @Override
        public void parsedHeader(HttpField field)
        {
            add(field.getName(), field.getValue());
        }

        @Override
        public boolean contentComplete()
        {
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            _complete = true;
            return true;
        }

        @Override
        public boolean headerComplete()
        {
            _content = new ByteArrayOutputStream();
            return false;
        }

        @Override
        public void earlyEOF()
        {
            _earlyEOF = true;
        }

        public boolean isEarlyEOF()
        {
            return _earlyEOF;
        }

        @Override
        public boolean content(ByteBuffer ref)
        {
            try
            {
                _content.write(BufferUtil.toArray(ref));
                return false;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void badMessage(HttpException failure)
        {
            HttpException.throwAsUnchecked(failure);
        }

        public ByteBuffer generate()
        {
            try
            {
                HttpGenerator generator = new HttpGenerator();
                MetaData info = getMetaData();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteBuffer header = null;
                ByteBuffer chunk = null;
                ByteBuffer content = _content == null ? null : ByteBuffer.wrap(_content.toByteArray());

                loop:
                while (!generator.isEnd())
                {
                    HttpGenerator.Result result = info instanceof MetaData.Request
                        ? generator.generateRequest((MetaData.Request)info, header, chunk, content, true)
                        : generator.generateResponse((MetaData.Response)info, false, header, chunk, content, true);
                    switch (result)
                    {
                        case NEED_HEADER:
                            header = BufferUtil.allocate(8192);
                            continue;

                        case HEADER_OVERFLOW:
                            if (header.capacity() >= 32 * 1024)
                                throw new BadMessageException(500, "Header too large");
                            header = BufferUtil.allocate(32 * 1024);
                            continue;

                        case NEED_CHUNK:
                            chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                            continue;

                        case NEED_CHUNK_TRAILER:
                            chunk = BufferUtil.allocate(8192);
                            continue;

                        case NEED_INFO:
                            throw new IllegalStateException();

                        case FLUSH:
                            if (BufferUtil.hasContent(header))
                            {
                                out.write(BufferUtil.toArray(header));
                                BufferUtil.clear(header);
                            }
                            if (BufferUtil.hasContent(chunk))
                            {
                                out.write(BufferUtil.toArray(chunk));
                                BufferUtil.clear(chunk);
                            }
                            if (BufferUtil.hasContent(content))
                            {
                                out.write(BufferUtil.toArray(content));
                                BufferUtil.clear(content);
                            }
                            break;

                        case SHUTDOWN_OUT:
                            break loop;

                        default:
                            break; // TODO verify if this should be ISE
                    }
                }

                return ByteBuffer.wrap(out.toByteArray());
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public abstract MetaData getMetaData();
    }

    public static class Request extends Message implements HttpParser.RequestHandler
    {
        private String _method;
        private String _uri;

        @Override
        public void startRequest(String method, String uri, HttpVersion version)
        {
            _method = method;
            _uri = uri;
            _version = version;
        }

        public String getMethod()
        {
            return _method;
        }

        public String getURI()
        {
            return _uri;
        }

        public void setMethod(String method)
        {
            _method = method;
        }

        public void setURI(String uri)
        {
            _uri = uri;
        }

        @Override
        public MetaData.Request getMetaData()
        {
            return new MetaData.Request(_method, HttpURI.from(_uri), _version, this, _content == null ? 0 : _content.size());
        }

        @Override
        public String toString()
        {
            return String.format("%s %s %s\n%s\n", _method, _uri, _version, super.toString());
        }

        public void setHeader(String name, String value)
        {
            put(name, value);
        }
    }

    public static class Response extends Message implements HttpParser.ResponseHandler
    {
        private int _status;
        private String _reason;

        @Override
        public void startResponse(HttpVersion version, int status, String reason)
        {
            _version = version;
            _status = status;
            _reason = reason;
        }

        public int getStatus()
        {
            return _status;
        }

        public String getReason()
        {
            return _reason;
        }

        @Override
        public MetaData.Response getMetaData()
        {
            return new MetaData.Response(_status, _reason, _version, this, _content == null ? -1 : _content.size());
        }

        @Override
        public String toString()
        {
            return String.format("%s %s %s\n%s\n", _version, _status, _reason, super.toString());
        }
    }
}
