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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpGeneratorServerHTTPTest
{
    private String _content;
    private String _reason;

    @ParameterizedTest
    @MethodSource("data")
    public void testHTTP(Run run) throws Exception
    {
        Handler handler = new Handler();

        HttpGenerator gen = new HttpGenerator();

        String msg = run.toString();

        run.result.getHttpFields().clear();

        String response = run.result.build(run.httpVersion, gen, "OK\r\nTest", run.connection.val, null, run.chunks);

        HttpParser parser = new HttpParser(handler);
        parser.setHeadResponse(run.result._head);

        parser.parseNext(BufferUtil.toBuffer(response));

        if (run.result._body != null)
            assertEquals(run.result._body, this._content, msg);

        // TODO: Break down rationale more clearly, these should be separate checks and/or assertions
        if (run.httpVersion == 10)
            assertTrue(gen.isPersistent() || run.result._contentLength >= 0 || EnumSet.of(ConnectionType.CLOSE, ConnectionType.KEEP_ALIVE, ConnectionType.NONE).contains(run.connection), msg);
        else
            assertTrue(gen.isPersistent() || EnumSet.of(ConnectionType.CLOSE, ConnectionType.TE_CLOSE).contains(run.connection), msg);

        assertEquals("OK??Test", _reason);

        if (_content == null)
            assertNull(run.result._body, msg);
        else
            assertThat(msg, run.result._contentLength, either(equalTo(_content.length())).or(equalTo(-1)));
    }

    private static class Result
    {
        private HttpFields.Mutable _fields = HttpFields.build();
        private final String _body;
        private final int _code;
        private String _connection;
        private int _contentLength;
        private String _contentType;
        private final boolean _head;
        private String _other;
        private String _te;

        private Result(int code, String contentType, int contentLength, String content, boolean head)
        {
            _code = code;
            _contentType = contentType;
            _contentLength = contentLength;
            _other = "value";
            _body = content;
            _head = head;
        }

        private String build(int version, HttpGenerator gen, String reason, String connection, String te, int nchunks) throws Exception
        {
            String response = "";
            _connection = connection;
            _te = te;

            if (_contentType != null)
                _fields.put("Content-Type", _contentType);
            if (_contentLength >= 0)
                _fields.put("Content-Length", "" + _contentLength);
            if (_connection != null)
                _fields.put("Connection", _connection);
            if (_te != null)
                _fields.put("Transfer-Encoding", _te);
            if (_other != null)
                _fields.put("Other", _other);

            ByteBuffer source = _body == null ? null : BufferUtil.toBuffer(_body);
            ByteBuffer[] chunks = new ByteBuffer[nchunks];
            ByteBuffer content = null;
            int c = 0;
            if (source != null)
            {
                for (int i = 0; i < nchunks; i++)
                {
                    chunks[i] = source.duplicate();
                    chunks[i].position(i * (source.capacity() / nchunks));
                    if (i > 0)
                        chunks[i - 1].limit(chunks[i].position());
                }
                content = chunks[c++];
            }
            ByteBuffer header = null;
            ByteBuffer chunk = null;
            MetaData.Response info = null;

            loop:
            while (true)
            {
                // if we have unwritten content
                if (source != null && content != null && content.remaining() == 0 && c < nchunks)
                    content = chunks[c++];

                // Generate
                boolean last = !BufferUtil.hasContent(content);

                HttpGenerator.Result result = gen.generateResponse(info, _head, header, chunk, content, last);

                switch (result)
                {
                    case NEED_INFO:
                        info = new MetaData.Response(HttpVersion.fromVersion(version), _code, reason, _fields, _contentLength);
                        continue;

                    case NEED_HEADER:
                        header = BufferUtil.allocate(2048);
                        continue;

                    case HEADER_OVERFLOW:
                        if (header.capacity() >= 8192)
                            throw new BadMessageException(500, "Header too large");
                        header = BufferUtil.allocate(8192);
                        continue;

                    case NEED_CHUNK:
                        chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                        continue;

                    case NEED_CHUNK_TRAILER:
                        chunk = BufferUtil.allocate(2048);
                        continue;

                    case FLUSH:
                        if (BufferUtil.hasContent(header))
                        {
                            response += BufferUtil.toString(header);
                            header.position(header.limit());
                        }
                        if (BufferUtil.hasContent(chunk))
                        {
                            response += BufferUtil.toString(chunk);
                            chunk.position(chunk.limit());
                        }
                        if (BufferUtil.hasContent(content))
                        {
                            response += BufferUtil.toString(content);
                            content.position(content.limit());
                        }
                        break;

                    case CONTINUE:
                        continue;

                    case SHUTDOWN_OUT:
                        break;

                    case DONE:
                        break loop;
                }
            }
            return response;
        }

        @Override
        public String toString()
        {
            return "[" + _code + "," + _contentType + "," + _contentLength + "," + (_body == null ? "null" : "content") + "]";
        }

        public HttpFields.Mutable getHttpFields()
        {
            return _fields;
        }
    }

    private class Handler implements HttpParser.ResponseHandler
    {
        @Override
        public boolean content(ByteBuffer ref)
        {
            if (_content == null)
                _content = "";
            _content += BufferUtil.toString(ref);
            ref.position(ref.limit());
            return false;
        }

        @Override
        public void earlyEOF()
        {
        }

        @Override
        public boolean headerComplete()
        {
            _content = null;
            return false;
        }

        @Override
        public boolean contentComplete()
        {
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            return true;
        }

        @Override
        public void parsedHeader(HttpField field)
        {
        }

        @Override
        public void startResponse(HttpVersion version, int status, String reason)
        {
            _reason = reason;
        }

        @Override
        public void badMessage(BadMessageException failure)
        {
            throw failure;
        }
    }

    public static final String CONTENT = "The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";

    private static class Run
    {
        private Result result;
        private ConnectionType connection;
        private int httpVersion;
        private int chunks;

        public Run(Result result, int ver, int chunks, ConnectionType connection)
        {
            this.result = result;
            this.httpVersion = ver;
            this.chunks = chunks;
            this.connection = connection;
        }

        @Override
        public String toString()
        {
            return String.format("result=%s,version=%d,chunks=%d,connection=%s", result, httpVersion, chunks, connection.name());
        }
    }

    private enum ConnectionType
    {
        NONE(null, 9, 10, 11),
        KEEP_ALIVE("keep-alive", 9, 10, 11),
        CLOSE("close", 9, 10, 11),
        TE_CLOSE("TE, close", 11);

        private String val;
        private int[] supportedHttpVersions;

        private ConnectionType(String val, int... supportedHttpVersions)
        {
            this.val = val;
            this.supportedHttpVersions = supportedHttpVersions;
        }

        public boolean isSupportedByHttp(int version)
        {
            for (int supported : supportedHttpVersions)
            {
                if (supported == version)
                {
                    return true;
                }
            }
            return false;
        }
    }

    public static Stream<Arguments> data()
    {
        Result[] results = {
            new Result(200, null, -1, null, false),
            new Result(200, null, -1, CONTENT, false),
            new Result(200, null, CONTENT.length(), null, true),
            new Result(200, null, CONTENT.length(), CONTENT, false),
            new Result(200, "text/html", -1, null, true),
            new Result(200, "text/html", -1, CONTENT, false),
            new Result(200, "text/html", CONTENT.length(), null, true),
            new Result(200, "text/html", CONTENT.length(), CONTENT, false)
        };

        ArrayList<Arguments> data = new ArrayList<>();

        // For each test result
        for (Result result : results)
        {
            // Loop over HTTP versions
            for (int v = 10; v <= 11; v++)
            {
                // Loop over chunks
                for (int chunks = 1; chunks <= 6; chunks++)
                {
                    // Loop over Connection values
                    for (ConnectionType connection : ConnectionType.values())
                    {
                        if (connection.isSupportedByHttp(v))
                        {
                            data.add(Arguments.of(new Run(result, v, chunks, connection)));
                        }
                    }
                }
            }
        }

        return data.stream();
    }
}
