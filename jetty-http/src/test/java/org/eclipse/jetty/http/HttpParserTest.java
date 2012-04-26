// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Test;

/**
 *
 */
public class HttpParserTest
{
    @Test
    public void testLineParse0() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /foo HTTP/1.0\015\012" + "\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);
        assertEquals("POST", f0);
        assertEquals("/foo", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(-1, h);
    }

    @Test
    public void testLineParse1() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("GET /999\015\012");

        f2= null;
        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);
        assertEquals("GET", f0);
        assertEquals("/999", f1);
        assertEquals(null, f2);
        assertEquals(-1, h);
    }

    @Test
    public void testLineParse2() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /222  \015\012");

        f2= null;
        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);
        assertEquals("POST", f0);
        assertEquals("/222", f1);
        assertEquals(null, f2);
        assertEquals(-1, h);
    }

    @Test
    public void testLineParse3() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /fo\u0690 HTTP/1.0\015\012" + "\015\012",StringUtil.__UTF8_CHARSET);

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);
        assertEquals("POST", f0);
        assertEquals("/fo\u0690", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(-1, h);
    }

    @Test
    public void testLineParse4() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /foo?param=\u0690 HTTP/1.0\015\012" + "\015\012",StringUtil.__UTF8_CHARSET);

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);
        assertEquals("POST", f0);
        assertEquals("/foo?param=\u0690", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(-1, h);
    }

    @Test
    public void testConnect() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("CONNECT 192.168.1.2:80 HTTP/1.1\015\012" + "\015\012");
        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);
        assertTrue(handler.request);
        assertEquals("CONNECT", f0);
        assertEquals("192.168.1.2:80", f1);
        assertEquals("HTTP/1.1", f2);
        assertEquals(-1, h);
    }

    @Test
    public void testHeaderParse() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012" +
                        "Host: localhost\015\012" +
                        "Header1: value1\015\012" +
                        "Header2  :   value 2a  \015\012" +
                        "                    value 2b  \015\012" +
                        "Header3: \015\012" +
                        "Header4 \015\012" +
                        "  value4\015\012" +
                        "Server5: notServer\015\012" +
                "\015\012");
        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);

        assertEquals("GET", f0);
        assertEquals("/", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals("Host", hdr[0]);
        assertEquals("localhost", val[0]);
        assertEquals("Header1", hdr[1]);
        assertEquals("value1", val[1]);
        assertEquals("Header2", hdr[2]);
        assertEquals("value 2a value 2b", val[2]);
        assertEquals("Header3", hdr[3]);
        assertEquals(null, val[3]);
        assertEquals("Header4", hdr[4]);
        assertEquals("value4", val[4]);
        assertEquals("Server5", hdr[5]);
        assertEquals("notServer", val[5]);
        assertEquals(5, h);
    }

    @Test
    public void testSplitHeaderParse() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "XXXXGET / HTTP/1.0\015\012" +
                        "Host: localhost\015\012" +
                        "Header1: value1\015\012" +
                        "Header2  :   value 2a  \015\012" +
                        "                    value 2b  \015\012" +
                        "Header3: \015\012" +
                        "Header4 \015\012" +
                        "  value4\015\012" +
                        "Server5: notServer\015\012" +
                "\015\012ZZZZ");
        buffer.position(2);
        buffer.limit(buffer.capacity()-2);
        buffer=buffer.slice();

        for (int i=0;i<buffer.capacity()-4;i++)
        {
            Handler handler = new Handler();
            HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);

            buffer.position(2);
            buffer.limit(2+i);

            if (!parser.parseNext(buffer))
            {
                buffer.limit(buffer.capacity()-2);
                parser.parseNext(buffer);
            }

            assertEquals("GET", f0);
            assertEquals("/", f1);
            assertEquals("HTTP/1.0", f2);
            assertEquals("Host", hdr[0]);
            assertEquals("localhost", val[0]);
            assertEquals("Header1", hdr[1]);
            assertEquals("value1", val[1]);
            assertEquals("Header2", hdr[2]);
            assertEquals("value 2a value 2b", val[2]);
            assertEquals("Header3", hdr[3]);
            assertEquals(null, val[3]);
            assertEquals("Header4", hdr[4]);
            assertEquals("value4", val[4]);
            assertEquals("Server5", hdr[5]);
            assertEquals("notServer", val[5]);
            assertEquals(5, h);
        }
    }


    @Test
    public void testChunkParse() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET /chunk HTTP/1.0\015\012"
                        + "Header1: value1\015\012"
                        + "Transfer-Encoding: chunked\015\012"
                        + "\015\012"
                        + "a;\015\012"
                        + "0123456789\015\012"
                        + "1a\015\012"
                        + "ABCDEFGHIJKLMNOPQRSTUVWXYZ\015\012"
                        + "0\015\012");
        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseAll(buffer);

        assertEquals("GET", f0);
        assertEquals("/chunk", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(1, h);
        assertEquals("Header1", hdr[0]);
        assertEquals("value1", val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);
    }

    @Test
    public void testMultiParse() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET /mp HTTP/1.0\015\012"
                        + "Connection: Keep-Alive\015\012"
                        + "Header1: value1\015\012"
                        + "Transfer-Encoding: chunked\015\012"
                        + "\015\012"
                        + "a;\015\012"
                        + "0123456789\015\012"
                        + "1a\015\012"
                        + "ABCDEFGHIJKLMNOPQRSTUVWXYZ\015\012"
                        + "0\015\012"
                        + "POST /foo HTTP/1.0\015\012"
                        + "Connection: Keep-Alive\015\012"
                        + "Header2: value2\015\012"
                        + "Content-Length: 0\015\012"
                        + "\015\012"
                        + "PUT /doodle HTTP/1.0\015\012"
                        + "Connection: close\015\012"
                        + "Header3: value3\015\012"
                        + "Content-Length: 10\015\012"
                        + "\015\012"
                        + "0123456789\015\012");


        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.RequestHandler)handler);
        parser.parseNext(buffer);
        assertEquals("GET", f0);
        assertEquals("/mp", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(2, h);
        assertEquals("Header1", hdr[1]);
        assertEquals("value1", val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        parser.parseNext(buffer);
        assertEquals("POST", f0);
        assertEquals("/foo", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(2, h);
        assertEquals("Header2", hdr[1]);
        assertEquals("value2", val[1]);
        assertEquals(null, _content);

        parser.reset();
        parser.parseNext(buffer);
        parser.onEOF();
        assertEquals("PUT", f0);
        assertEquals("/doodle", f1);
        assertEquals("HTTP/1.0", f2);
        assertEquals(2, h);
        assertEquals("Header3", hdr[1]);
        assertEquals("value3", val[1]);
        assertEquals("0123456789", _content);
    }

    @Test
    public void testResponseParse0() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 200 Correct\015\012"
                        + "Content-Length: 10\015\012"
                        + "Content-Type: text/plain\015\012"
                        + "\015\012"
                        + "0123456789\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("200", f1);
        assertEquals("Correct", f2);
        assertEquals(10,_content.length());
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);
    }

    @Test
    public void testResponseParse1() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 304 Not-Modified\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("304", f1);
        assertEquals("Not-Modified", f2);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);
    }

    @Test
    public void testResponseParse2() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 204 No-Content\015\012"
                        + "Header: value\015\012"
                        + "\015\012"
                        + "HTTP/1.1 200 Correct\015\012"
                        + "Content-Length: 10\015\012"
                        + "Content-Type: text/plain\015\012"
                        + "\015\012"
                        + "0123456789\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("204", f1);
        assertEquals("No-Content", f2);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);


        parser.setPersistent(true);
        parser.reset();
        parser.parseNext(buffer);
        parser.onEOF();
        assertEquals("HTTP/1.1", f0);
        assertEquals("200", f1);
        assertEquals("Correct", f2);
        assertEquals(_content.length(), 10);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);
    }


    @Test
    public void testResponseParse3() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 200\015\012"
                        + "Content-Length: 10\015\012"
                        + "Content-Type: text/plain\015\012"
                        + "\015\012"
                        + "0123456789\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("200", f1);
        assertEquals(null, f2);
        assertEquals(_content.length(), 10);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);
    }

    @Test
    public void testResponseParse4() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 200 \015\012"
                        + "Content-Length: 10\015\012"
                        + "Content-Type: text/plain\015\012"
                        + "\015\012"
                        + "0123456789\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("200", f1);
        assertEquals(null, f2);
        assertEquals(_content.length(), 10);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);
    }

    @Test
    public void testResponse304WithContentLength() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 304 found\015\012"
                        + "Content-Length: 10\015\012"
                        + "\015\012");

        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("304", f1);
        assertEquals("found", f2);
        assertEquals(null,_content);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);
    }

    @Test
    public void testSeekEOF() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 200 OK\015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012"
                        + "\015\012" // extra CRLF ignored
                        + "HTTP/1.1 400 OK\015\012");  // extra data causes close


        Handler handler = new Handler();
        HttpParser parser= new HttpParser((HttpParser.ResponseHandler)handler);

        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", f0);
        assertEquals("200", f1);
        assertEquals("OK", f2);
        assertEquals(null,_content);
        assertTrue(headerCompleted);
        assertTrue(messageCompleted);


    }

    private String _content;
    private String f0;
    private String f1;
    private String f2;
    private String[] hdr;
    private String[] val;
    private int h;

    private boolean headerCompleted;
    private boolean messageCompleted;

    private class Handler implements HttpParser.RequestHandler, HttpParser.ResponseHandler
    {
        private HttpFields fields;
        private boolean request;

        @Override
        public boolean content(ByteBuffer ref)
        {
            if (_content==null)
                _content="";
            String c = BufferUtil.toString(ref,StringUtil.__UTF8_CHARSET);
            //System.err.println("content '"+c+"'");
            _content= _content + c;
            ref.position(ref.limit());
            return false;
        }

        @Override
        public boolean startRequest(String method, String uri, String version)
        {
            request=true;
            h= -1;
            hdr= new String[9];
            val= new String[9];
            f0= method;
            f1= uri;
            f2= version;

            fields=new HttpFields();
            messageCompleted = false;
            headerCompleted = false;
            return false;
        }

        @Override
        public boolean parsedHeader(HttpHeader header, String name, String value)
        {
            //System.err.println("header "+name+": "+value);
            hdr[++h]= name;
            val[h]= value;
            return false;
        }

        @Override
        public boolean headerComplete(boolean hasBody,boolean persistent)
        {
            //System.err.println("headerComplete");
            _content= null;
            String s0=fields.toString();
            String s1=fields.toString();
            if (!s0.equals(s1))
            {
                //System.err.println(s0);
                //System.err.println(s1);
                throw new IllegalStateException();
            }

            headerCompleted = true;
            return false;
        }

        @Override
        public boolean messageComplete(long contentLength)
        {
            //System.err.println("messageComplete");
            messageCompleted = true;
            return true;
        }

        @Override
        public boolean startResponse(String version, int status, String reason)
        {
            request=false;
            f0 = version.toString();
            f1 = Integer.toString(status);
            f2 = reason==null?null:reason.toString();

            fields=new HttpFields();
            hdr= new String[9];
            val= new String[9];

            messageCompleted = false;
            headerCompleted = false;
            return false;
        }

        @Override
        public boolean earlyEOF()
        {
            return true;
        }
    }
}
