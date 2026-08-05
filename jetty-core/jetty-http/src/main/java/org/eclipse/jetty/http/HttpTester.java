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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.Aggregator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

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
        protected boolean _eof = false;
        protected HttpParser _parser;

        public Input()
        {
        }

        public abstract boolean parse(HttpParser parser);

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
            return _eof;
        }

        public abstract int fillBuffer() throws IOException;
    }

    public static Input from(String string)
    {
        return from(BufferUtil.toReadableBuffer(string));
    }

    public static Input from(RetainableByteBuffer data)
    {
        return new Input()
        {
            @Override
            public boolean parse(HttpParser parser)
            {
                return parser.parseNext(data);
            }

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
            private final RetainableByteBuffer.Mutable _buffer = RetainableByteBuffer.Mutable.allocate(IO.DEFAULT_BUFFER_SIZE, false);

            @Override
            public boolean parse(HttpParser parser)
            {
                return parser.parseNext(_buffer);
            }

            @Override
            public int fillBuffer() throws IOException
            {
                int len = (int)_buffer.compact().readFrom(output ->
                {
                    int read = stream.read(output.array(), output.arrayOffset(), output.remaining());
                    if (read > 0)
                        output.position(output.position() + read);
                    return read;
                });
                if (len < 0)
                    _eof = true;
                return len;
            }
        };
    }

    public static Input from(ReadableByteChannel channel)
    {
        return new Input()
        {
            private final RetainableByteBuffer.Mutable _buffer = RetainableByteBuffer.Mutable.allocate(IO.DEFAULT_BUFFER_SIZE, false);

            @Override
            public boolean parse(HttpParser parser)
            {
                return parser.parseNext(_buffer);
            }

            @Override
            public int fillBuffer() throws IOException
            {
                int len = (int)_buffer.compact().readFrom(channel::read);
                if (len < 0)
                    _eof = true;
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
        return parseRequest(BufferUtil.toReadableBuffer(request));
    }

    public static Request parseRequest(RetainableByteBuffer buffer)
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
        parser.parseNext(RetainableByteBuffer.wrap(response, ISO_8859_1));
        return r;
    }

    private static Response parseResponse(String response, boolean head)
    {
        return parseResponse(BufferUtil.toReadableBuffer(response), head);
    }

    public static Response parseHeadResponse(RetainableByteBuffer response)
    {
        return parseResponse(response, true);
    }

    public static Response parseResponse(RetainableByteBuffer response)
    {
        return parseResponse(response, false);
    }

    public static Response parseResponse(RetainableByteBuffer response, boolean head)
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

    public static Response parseResponse(Input input, Response response) throws IOException
    {
        return parseResponse(input, response, false);
    }

    public static Response parseResponse(Input input, boolean head) throws IOException
    {
        return parseResponse(input, new Response(), head);
    }

    public static Response parseResponse(Input input, Response response, boolean head) throws IOException
    {
        HttpParser parser = input.takeHttpParser();
        if (parser != null)
            response = (Response)parser.getHandler();
        else
            parser = new HttpParser(response);
        parser.setHeadResponse(head);
        parse(input, parser);
        if (response.isComplete())
            return response;
        input.setHttpParser(parser);
        return null;
    }

    private static void parse(Input input, HttpParser parser) throws IOException
    {
        while (true)
        {
            if (input.parse(parser))
                break;
            int len = input.fillBuffer();
            if (len == 0)
                break;
            if (len < 0)
            {
                parser.atEOF();
                parser.parseNext(RetainableByteBuffer.empty());
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
            Charset charset = encoding == null ? UTF_8 : Charset.forName(encoding);

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
        public boolean content(RetainableByteBuffer ref)
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

        public RetainableByteBuffer generate()
        {
            HttpGenerator generator = new HttpGenerator();
            MetaData info = getMetaData();
            RetainableByteBuffer.Mutable header = null;
            RetainableByteBuffer.Mutable chunk = null;
            RetainableByteBuffer content = _content == null ? null : RetainableByteBuffer.wrap(_content.toByteArray());

            try (Aggregator aggregator = new Aggregator(true, 1024))
            {
                boolean complete = false;
                while (!generator.isEnd() && !complete)
                {
                    HttpGenerator.Result result = info instanceof MetaData.Request
                        ? generator.generateRequest((MetaData.Request)info, header, chunk, content, true)
                        : generator.generateResponse((MetaData.Response)info, false, header, chunk, content, true);
                    switch (result)
                    {
                        case NEED_INFO ->
                            throw new IllegalStateException();

                        case NEED_HEADER ->
                            header = RetainableByteBuffer.Mutable.allocate(IO.DEFAULT_BUFFER_SIZE, false);

                        case HEADER_OVERFLOW ->
                        {
                            int max = 32 * 1024;
                            if (header != null && header.capacity() >= max)
                            {
                                throw new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Header too large");
                            }
                            header = RetainableByteBuffer.Mutable.allocate(max, false);
                        }

                        case NEED_CHUNK ->
                            chunk = RetainableByteBuffer.Mutable.allocate(HttpGenerator.CHUNK_SIZE, false);

                        case NEED_CHUNK_TRAILER ->
                            chunk = RetainableByteBuffer.Mutable.allocate(IO.DEFAULT_BUFFER_SIZE, false);

                        case FLUSH ->
                        {
                            if (header != null && header.hasRemaining())
                                aggregator.append(header);
                            if (chunk != null && chunk.hasRemaining())
                                aggregator.append(chunk);
                            if (content != null && content.hasRemaining())
                            {
                                int chunkMaxLength = generator.getChunkMaxLength();
                                if (generator.isChunking() && content.remaining() > chunkMaxLength)
                                    content = content.sliceAndConsume(chunkMaxLength);
                                aggregator.append(content);
                            }
                        }

                        case CONTINUE ->
                        {
                        }

                        case SHUTDOWN_OUT, DONE ->
                        {
                            complete = true;
                        }
                    }
                }
                return aggregator.take();
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
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
