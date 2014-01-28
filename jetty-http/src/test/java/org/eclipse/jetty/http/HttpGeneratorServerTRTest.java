//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HttpGeneratorServerTRTest
{
    private static class TR
    {
        private HttpFields _fields = new HttpFields();
        private final String _body;
        private final int _code;
        String _connection;
        int _contentLength;
        String _contentType;
        private final boolean _head;
        String _other;
        String _te;

        private TR(int code, String contentType, int contentLength, String content, boolean head)
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
                _fields.put("Content-Type",_contentType);
            if (_contentLength >= 0)
                _fields.put("Content-Length","" + _contentLength);
            if (_connection != null)
                _fields.put("Connection",_connection);
            if (_te != null)
                _fields.put("Transfer-Encoding",_te);
            if (_other != null)
                _fields.put("Other",_other);

            ByteBuffer source = _body == null?null:BufferUtil.toBuffer(_body);
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
                // System.err.printf("content %d %s%n",c,BufferUtil.toDetailString(content));
            }
            ByteBuffer header = null;
            ByteBuffer chunk = null;
            HttpGenerator.ResponseInfo info = null;

            loop: while (true)
            {
                // if we have unwritten content
                if (source != null && content != null && content.remaining() == 0 && c < nchunks)
                {
                    content = chunks[c++];
                    // System.err.printf("content %d %s%n",c,BufferUtil.toDetailString(content));
                }

                // Generate
                boolean last = !BufferUtil.hasContent(content);

                HttpGenerator.Result result = gen.generateResponse(info,header,chunk,content,last);

                switch (result)
                {
                    case NEED_INFO:
                        info = new HttpGenerator.ResponseInfo(HttpVersion.fromVersion(version),_fields,_contentLength,_code,reason,_head);
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
            return "[" + _code + "," + _contentType + "," + _contentLength + "," + (_body == null?"null":"content") + "]";
        }

        public HttpFields getHttpFields()
        {
            return _fields;
        }
    }

    private class Handler implements HttpParser.ResponseHandler<ByteBuffer>
    {
        private final List<String> _hdr = new ArrayList<>();
        private final List<String> _val = new ArrayList<>();
        private int _status;
        private HttpVersion _version;

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
        public boolean parsedHeader(HttpField field)
        {
            _hdr.add(field.getName());
            _val.add(field.getValue());
            return false;
        }

        @Override
        public boolean startResponse(HttpVersion version, int status, String reason)
        {
            _version = version;
            _status = status;
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
        public static Run[] as(TR tr, int ver, int chunks, ConnectionType connection)
        {
            Run run = new Run();
            run.tr = tr;
            run.httpVersion = ver;
            run.chunks = chunks;
            run.connection = connection;
            return new Run[] { run };
        }

        TR tr;
        ConnectionType connection;
        int httpVersion;
        int chunks;

        @Override
        public String toString()
        {
            return String.format("tr=%s,ver=%d,chunks=%d,connection=%s",tr,httpVersion,chunks,connection.name());
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
        TR[] trs = {
        /* 0 */new TR(200,null,-1,null,false),
        /* 1 */new TR(200,null,-1,CONTENT,false),
        /* 2 */new TR(200,null,CONTENT.length(),null,true),
        /* 3 */new TR(200,null,CONTENT.length(),CONTENT,false),
        /* 4 */new TR(200,"text/html",-1,null,true),
        /* 5 */new TR(200,"text/html",-1,CONTENT,false),
        /* 6 */new TR(200,"text/html",CONTENT.length(),null,true),
        /* 7 */new TR(200,"text/html",CONTENT.length(),CONTENT,false), };

        List<Run[]> data = new ArrayList<>();

        // For each test result
        for (TR tr : trs)
        {
            // Loop over HTTP versions
            for (int v = 9; v <= 11; v++)
            {
                // Loop over chunks
                for (int chunks = 1; chunks <= 6; chunks++)
                {
                    // Loop over Connection values
                    for (ConnectionType connection : ConnectionType.values())
                    {
                        if (connection.isSupportedByHttp(v))
                        {
                            data.add(Run.as(tr,v,chunks,connection));
                        }
                    }
                }
            }
        }

        return data;
    }

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

        run.tr.getHttpFields().clear();

        String response = run.tr.build(run.httpVersion,gen,"OK\r\nTest",run.connection.val,null,run.chunks);

        if (run.httpVersion == 9)
        {
            assertFalse(t,gen.isPersistent());
            if (run.tr._body != null)
                assertEquals(t,run.tr._body,response);
            return;
        }

        HttpParser parser = new HttpParser(handler);
        parser.setHeadResponse(run.tr._head);

        parser.parseNext(BufferUtil.toBuffer(response));

        if (run.tr._body != null)
            assertEquals(t,run.tr._body,this._content);

        if (run.httpVersion == 10)
            assertTrue(t,gen.isPersistent() || run.tr._contentLength >= 0 || run.connection == ConnectionType.CLOSE || run.connection == ConnectionType.NONE);
        else
            assertTrue(t,gen.isPersistent() || run.connection == ConnectionType.CLOSE || run.connection == ConnectionType.TE_CLOSE);

        if (run.httpVersion > 9)
            assertEquals("OK??Test",_reason);

        if (_content == null)
            assertTrue(t,run.tr._body == null);
        else
            assertThat(t,run.tr._contentLength,either(equalTo(_content.length())).or(equalTo(-1)));
    }
}
