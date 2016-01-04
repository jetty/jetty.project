//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HttpGeneratorServerHTTPTest
{
    @Parameter(value = 0)
    public Run run;
    private String _content;
    private String _reason;

    @Test
    public void testHTTP() throws Exception
    {
        Handler handler = new Handler();

        HttpGenerator gen = new HttpGenerator();

        String t = run.toString();

        run.result.getHttpFields().clear();

        String response = run.result.build(run.httpVersion, gen, "OK\r\nTest", run.connection.val, null, run.chunks);

        HttpParser parser = new HttpParser(handler);
        parser.setHeadResponse(run.result._head);

        parser.parseNext(BufferUtil.toBuffer(response));

        if (run.result._body != null)
            assertEquals(t, run.result._body, this._content);

        if (run.httpVersion == 10)
            assertTrue(t, gen.isPersistent() || run.result._contentLength >= 0 || EnumSet.of(ConnectionType.CLOSE, ConnectionType.KEEP_ALIVE, ConnectionType.NONE).contains(run.connection));
        else
            assertTrue(t, gen.isPersistent() || EnumSet.of(ConnectionType.CLOSE, ConnectionType.TE_CLOSE).contains(run.connection));

        assertEquals("OK??Test", _reason);

        if (_content == null)
            assertTrue(t, run.result._body == null);
        else
            assertThat(t, run.result._contentLength, either(equalTo(_content.length())).or(equalTo(-1)));
    }

    private static class Result
    {
        private HttpFields _fields = new HttpFields();
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

                    case NEED_CHUNK:
                        chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
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

        public HttpFields getHttpFields()
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
        public boolean messageComplete()
        {
            return true;
        }

        @Override
        public void parsedHeader(HttpField field)
        {
        }

        @Override
        public boolean startResponse(HttpVersion version, int status, String reason)
        {
            _reason = reason;
            return false;
        }

        @Override
        public void badMessage(int status, String reason)
        {
            throw new IllegalStateException(reason);
        }

        @Override
        public int getHeaderCacheSize()
        {
            return 256;
        }
    }

    public final static String CONTENT = "The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";

    private static class Run
    {
        public static Run[] as(Result result, int ver, int chunks, ConnectionType connection)
        {
            Run run = new Run();
            run.result = result;
            run.httpVersion = ver;
            run.chunks = chunks;
            run.connection = connection;
            return new Run[]{run};
        }

        private Result result;
        private ConnectionType connection;
        private int httpVersion;
        private int chunks;

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

    @Parameters(name = "{0}")
    public static Collection<Run[]> data()
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

        List<Run[]> data = new ArrayList<>();

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
                            data.add(Run.as(result, v, chunks, connection));
                        }
                    }
                }
            }
        }
        return data;
    }
}
