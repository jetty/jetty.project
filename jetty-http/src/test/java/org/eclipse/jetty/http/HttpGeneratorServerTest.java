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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;

public class HttpGeneratorServerTest
{
    private class Handler implements HttpParser.ResponseHandler<ByteBuffer>
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
                // System.err.printf("content %d %s%n",c,BufferUtil.toDetailString(content));
            }
            ByteBuffer header = null;
            ByteBuffer chunk = null;
            HttpGenerator.ResponseInfo info = null;

            loop:
            while (true)
            {
                // if we have unwritten content
                if (source != null && content != null && content.remaining() == 0 && c < nchunks)
                {
                    content = chunks[c++];
                    // System.err.printf("content %d %s%n",c,BufferUtil.toDetailString(content));
                }

                // Generate
                boolean last = !BufferUtil.hasContent(content);

                HttpGenerator.Result result = gen.generateResponse(info, header, chunk, content, last);

                switch (result)
                {
                    case NEED_INFO:
                        info = new HttpGenerator.ResponseInfo(HttpVersion.fromVersion(version), _fields, _contentLength, _code, reason, _head);
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

    public final static String[] connections = {null, "keep-alive", "close", "TE, close"};
    public final static String CONTENT = "The quick brown fox jumped over the lazy dog.\nNow is the time for all good men to come to the aid of the party\nThe moon is blue to a fish in love.\n";

    private final List<String> _hdr = new ArrayList<>();
    private final List<String> _val = new ArrayList<>();
    private String _content;
    private String _reason;
    private int _status;
    private HttpVersion _version;
    private final TR[] tr =
            {
                    /* 0 */  new TR(200, null, -1, null, false),
                    /* 1 */  new TR(200, null, -1, CONTENT, false),
                    /* 2 */  new TR(200, null, CONTENT.length(), null, true),
                    /* 3 */  new TR(200, null, CONTENT.length(), CONTENT, false),
                    /* 4 */  new TR(200, "text/html", -1, null, true),
                    /* 5 */  new TR(200, "text/html", -1, CONTENT, false),
                    /* 6 */  new TR(200, "text/html", CONTENT.length(), null, true),
                    /* 7 */  new TR(200, "text/html", CONTENT.length(), CONTENT, false),
            };

    @Test
    public void testHTTP() throws Exception
    {
        Handler handler = new Handler();

        // Loop over HTTP versions
        for (int v = 9; v <= 11; v++)
        {
            // For each test result
            for (int r = 0; r < tr.length; r++)
            {
                HttpGenerator gen = new HttpGenerator();

                // Loop over chunks
                for (int chunks = 1; chunks <= 6; chunks++)
                {
                    // Loop over Connection values
                    for (int c = 0; c < (v == 11 ? connections.length : (connections.length - 1)); c++)
                    {
                        String t = "v=" + v + ",chunks=" + chunks + ",connection=" + connections[c] + ",tr=" + r + "=" + tr[r];

                        gen.reset();
                        tr[r].getHttpFields().clear();

                        String response = tr[r].build(v, gen, "OK\r\nTest", connections[c], null, chunks);

                        if (v == 9)
                        {
                            assertFalse(t, gen.isPersistent());
                            if (tr[r]._body != null)
                                assertEquals(t, tr[r]._body, response);
                            continue;
                        }

                        HttpParser parser = new HttpParser(handler);
                        parser.setHeadResponse(tr[r]._head);

                        parser.parseNext(BufferUtil.toBuffer(response));

                        if (tr[r]._body != null)
                            assertEquals(t, tr[r]._body, this._content);

                        if (v == 10)
                            assertTrue(t, gen.isPersistent() || tr[r]._contentLength >= 0 || c == 2 || c == 0);
                        else
                            assertTrue(t, gen.isPersistent() || c == 2 || c == 3);

                        if (v > 9)
                            assertEquals("OK??Test", _reason);

                        if (_content == null)
                            assertTrue(t, tr[r]._body == null);
                        else
                            assertThat(t, tr[r]._contentLength, either(equalTo(_content.length())).or(equalTo(-1)));
                    }
                }
            }
        }
    }
    
    @Test
    public void testSendServerXPoweredBy() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(8096);
        ResponseInfo info = new ResponseInfo(HttpVersion.HTTP_1_1, new HttpFields(), -1, 200, null, false);
        HttpFields fields = new HttpFields();
        fields.add(HttpHeader.SERVER,"SomeServer");
        fields.add(HttpHeader.X_POWERED_BY,"SomePower");
        ResponseInfo infoF = new ResponseInfo(HttpVersion.HTTP_1_1, fields, -1, 200, null, false);
        String head;
        
        HttpGenerator gen = new HttpGenerator(true,true);
        gen.generateResponse(info, header, null, null, true);
        head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        assertThat(head, containsString("HTTP/1.1 200 OK"));
        assertThat(head, containsString("Server: Jetty(9.x.x)"));
        assertThat(head, containsString("X-Powered-By: Jetty(9.x.x)"));
        gen.reset();
        gen.generateResponse(infoF, header, null, null, true);
        head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        assertThat(head, containsString("HTTP/1.1 200 OK"));
        assertThat(head, not(containsString("Server: Jetty(9.x.x)")));
        assertThat(head, containsString("Server: SomeServer"));
        assertThat(head, containsString("X-Powered-By: Jetty(9.x.x)"));
        assertThat(head, containsString("X-Powered-By: SomePower"));
        gen.reset();
        
        gen = new HttpGenerator(false,false);
        gen.generateResponse(info, header, null, null, true);
        head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        assertThat(head, containsString("HTTP/1.1 200 OK"));
        assertThat(head, not(containsString("Server: Jetty(9.x.x)")));
        assertThat(head, not(containsString("X-Powered-By: Jetty(9.x.x)")));
        gen.reset();
        gen.generateResponse(infoF, header, null, null, true);
        head = BufferUtil.toString(header);
        BufferUtil.clear(header);
        assertThat(head, containsString("HTTP/1.1 200 OK"));
        assertThat(head, not(containsString("Server: Jetty(9.x.x)")));
        assertThat(head, containsString("Server: SomeServer"));
        assertThat(head, not(containsString("X-Powered-By: Jetty(9.x.x)")));
        assertThat(head, containsString("X-Powered-By: SomePower"));
        gen.reset(); 
    }

    @Test
    public void testResponseNoContent() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(8096);

        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
                result = gen.generateResponse(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        ResponseInfo info = new ResponseInfo(HttpVersion.HTTP_1_1, new HttpFields(), -1, 200, null, false);
        info.getHttpFields().add("Last-Modified", DateGenerator.__01Jan1970);

        result = gen.generateResponse(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);

        result = gen.generateResponse(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);

        result = gen.generateResponse(null, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());

        assertEquals(0, gen.getContentPrepared());
        assertThat(head, containsString("HTTP/1.1 200 OK"));
        assertThat(head, containsString("Last-Modified: Thu, 01 Jan 1970 00:00:00 GMT"));
        assertThat(head, containsString("Content-Length: 0"));
    }

    @Test
    public void testResponseUpgrade() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(8096);

        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
                result = gen.generateResponse(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        ResponseInfo info = new ResponseInfo(HttpVersion.HTTP_1_1, new HttpFields(), -1, 101, null, false);
        info.getHttpFields().add("Upgrade", "WebSocket");
        info.getHttpFields().add("Connection", "Upgrade");
        info.getHttpFields().add("Sec-WebSocket-Accept", "123456789==");

        result = gen.generateResponse(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        String head = BufferUtil.toString(header);
        BufferUtil.clear(header);

        result = gen.generateResponse(info, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());

        assertEquals(0, gen.getContentPrepared());

        assertThat(head, startsWith("HTTP/1.1 101 Switching Protocols"));
        assertThat(head, containsString("Upgrade: WebSocket\r\n"));
        assertThat(head, containsString("Connection: Upgrade\r\n"));
    }

    @Test
    public void testResponseWithChunkedContent() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(4096);
        ByteBuffer chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        ByteBuffer content0 = BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1 = BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result result = gen.generateResponse(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        ResponseInfo info = new ResponseInfo(HttpVersion.HTTP_1_1, new HttpFields(), -1, 200, null, false);
        info.getHttpFields().add("Last-Modified", DateGenerator.__01Jan1970);
        result = gen.generateResponse(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateResponse(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());

        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        out += BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result = gen.generateResponse(null,null,chunk, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        out += BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);
        out += BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result = gen.generateResponse(null,null,chunk, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());

        result = gen.generateResponse(null,null,chunk, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        out += BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);

        result = gen.generateResponse(null,null,chunk, null, true);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());

        assertThat(out, containsString("HTTP/1.1 200 OK"));
        assertThat(out, containsString("Last-Modified: Thu, 01 Jan 1970 00:00:00 GMT"));
        assertThat(out, not(containsString("Content-Length")));
        assertThat(out, containsString("Transfer-Encoding: chunked"));
        assertThat(out, containsString("\r\n\r\nD\r\n"));
        assertThat(out, containsString("\r\nHello World! \r\n"));
        assertThat(out, containsString("\r\n2E\r\n"));
        assertThat(out, containsString("\r\nThe quick brown fox jumped over the lazy dog. \r\n"));
        assertThat(out, containsString("\r\n0\r\n"));
    }

    @Test
    public void testResponseWithKnownContent() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(4096);
        ByteBuffer content0 = BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1 = BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result

                result = gen.generateResponse(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        ResponseInfo info = new ResponseInfo(HttpVersion.HTTP_1_1, new HttpFields(), 59, 200, null, false);
        info.getHttpFields().add("Last-Modified", DateGenerator.__01Jan1970);
        result = gen.generateResponse(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateResponse(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());

        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        out += BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result = gen.generateResponse(null, null, null, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        out += BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result = gen.generateResponse(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());

        result = gen.generateResponse(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());

        assertThat(out, containsString("HTTP/1.1 200 OK"));
        assertThat(out, containsString("Last-Modified: Thu, 01 Jan 1970 00:00:00 GMT"));
        assertThat(out, not(containsString("chunked")));
        assertThat(out, containsString("Content-Length: 59"));
        assertThat(out, containsString("\r\n\r\nHello World! The quick brown fox jumped over the lazy dog. "));
    }

    @Test
    public void test100ThenResponseWithContent() throws Exception
    {

        ByteBuffer header = BufferUtil.allocate(4096);
        ByteBuffer content0 = BufferUtil.toBuffer("Hello World! ");
        ByteBuffer content1 = BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog. ");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result

                result = gen.generateResponse(HttpGenerator.CONTINUE_100_INFO, null, null, null, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateResponse(HttpGenerator.CONTINUE_100_INFO, header, null, null, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING_1XX, gen.getState());
        String out = BufferUtil.toString(header);

        result = gen.generateResponse(null, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        assertThat(out, containsString("HTTP/1.1 100 Continue"));


        result = gen.generateResponse(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        ResponseInfo info = new ResponseInfo(HttpVersion.HTTP_1_1, new HttpFields(), 59, 200, null, false);
        info.getHttpFields().add("Last-Modified", DateGenerator.__01Jan1970);
        result = gen.generateResponse(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateResponse(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());

        out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        out += BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result = gen.generateResponse(null, null, null, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        out += BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result = gen.generateResponse(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());

        result = gen.generateResponse(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());

        assertThat(out, containsString("HTTP/1.1 200 OK"));
        assertThat(out, containsString("Last-Modified: Thu, 01 Jan 1970 00:00:00 GMT"));
        assertThat(out, not(containsString("chunked")));
        assertThat(out, containsString("Content-Length: 59"));
        assertThat(out, containsString("\r\n\r\nHello World! The quick brown fox jumped over the lazy dog. "));
    }
}
