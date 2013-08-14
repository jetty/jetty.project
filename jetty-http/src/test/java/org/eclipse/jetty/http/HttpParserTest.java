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

package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpParser.State;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class HttpParserTest
{
    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until {@link State#END} state.
     * If the parser is already in the END state, then it is {@link HttpParser#reset()} and re-parsed.
     * @param parser The parser to test
     * @throws IllegalStateException If the buffers have already been partially parsed.
     */
    public static void parseAll(HttpParser parser, ByteBuffer buffer)
    {
        if (parser.isState(State.END))
            parser.reset();
        if (!parser.isState(State.START))
            throw new IllegalStateException("!START");

        // continue parsing
        int remaining=buffer.remaining();
        while (!parser.isState(State.END) && remaining>0)
        {
            int was_remaining=remaining;
            parser.parseNext(buffer);
            remaining=buffer.remaining();
            if (remaining==was_remaining)
                break;
        }
    }

    @Test
    public void testLineParse0() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /foo HTTP/1.0\015\012" + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse1() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("GET /999\015\012");

        _versionOrReason= null;
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/999", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse2() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /222  \015\012");

        _versionOrReason= null;
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/222", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse3() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /fo\u0690 HTTP/1.0\015\012" + "\015\012",StringUtil.__UTF8_CHARSET);

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/fo\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse4() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /foo?param=\u0690 HTTP/1.0\015\012" + "\015\012",StringUtil.__UTF8_CHARSET);

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo?param=\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLongURLParse() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("POST /123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/ HTTP/1.0\015\012" + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }
    
    @Test
    public void testConnect() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer("CONNECT 192.168.1.2:80 HTTP/1.1\015\012" + "\015\012");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("CONNECT", _methodOrVersion);
        assertEquals("192.168.1.2:80", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testSimple() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012" +
                "Host: localhost\015\012" +
                "Connection: close\015\012" +
                "\015\012");
        
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
    }

    @Test
    public void testHeaderParseDirect() throws Exception
    {
        ByteBuffer b0= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012" +
                        "Host: localhost\015\012" +
                        "Header1: value1\015\012" +
                        "Header 2  :   value 2a  \015\012" +
                        "    value 2b  \015\012" +
                        "Header3: \015\012" +
                        "Header4 \015\012" +
                        "  value4\015\012" +
                        "Server5 : notServer\015\012" +
                        "Host Header: notHost\015\012" +
                        "Connection: close\015\012" +
                        "Accept-Encoding: gzip, deflated\015\012" +
                        "Accept: unknown\015\012" +
                "\015\012");
        ByteBuffer buffer = BufferUtil.allocateDirect(b0.capacity());
        int pos=BufferUtil.flipToFill(buffer);
        BufferUtil.put(b0,buffer);
        BufferUtil.flipToFlush(buffer,pos);
        
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header 2", _hdr[2]);
        assertEquals("value 2a value 2b", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals(null, _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("Host Header", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }
    
    @Test
    public void testHeaderParseCRLF() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012" +
                        "Host: localhost\015\012" +
                        "Header1: value1\015\012" +
                        "Header 2  :   value 2a  \015\012" +
                        "    value 2b  \015\012" +
                        "Header3: \015\012" +
                        "Header4 \015\012" +
                        "  value4\015\012" +
                        "Server5 : notServer\015\012" +
                        "Host Header: notHost\015\012" +
                        "Connection: close\015\012" +
                        "Accept-Encoding: gzip, deflated\015\012" +
                        "Accept: unknown\015\012" +
                "\015\012");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header 2", _hdr[2]);
        assertEquals("value 2a value 2b", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals(null, _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("Host Header", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    
    
    @Test
    public void testHeaderParseLF() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\n" +
                        "Host: localhost\n" +
                        "Header1: value1\n" +
                        "Header 2  :   value 2a  \n" +
                        "    value 2b  \n" +
                        "Header3: \n" +
                        "Header4 \n" +
                        "  value4\n" +
                        "Server5 : notServer\n" +
                        "Host Header: notHost\n" +
                        "Connection: close\n" +
                        "Accept-Encoding: gzip, deflated\n" +
                        "Accept: unknown\n" +
                "\n");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header 2", _hdr[2]);
        assertEquals("value 2a value 2b", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals(null, _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("Host Header", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @Test
    public void testEncodedHeader() throws Exception
    {
        ByteBuffer buffer=BufferUtil.allocate(4096);
        BufferUtil.flipToFill(buffer); 
        BufferUtil.put(BufferUtil.toBuffer("GET "),buffer);
        buffer.put("/foo/\u0690/".getBytes(StringUtil.__UTF8_CHARSET));
        BufferUtil.put(BufferUtil.toBuffer(" HTTP/1.0\r\n"),buffer);
        BufferUtil.put(BufferUtil.toBuffer("Header1: "),buffer);
        buffer.put("\u00e6 \u00e6".getBytes(StringUtil.__ISO_8859_1_CHARSET));
        BufferUtil.put(BufferUtil.toBuffer("  \r\n\r\n"),buffer);
        BufferUtil.flipToFlush(buffer,0);
                    
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/foo/\u0690/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Header1", _hdr[0]);
        assertEquals("\u00e6 \u00e6", _val[0]);
        assertEquals(0, _headers);
        assertEquals(null,_bad);
    }
    
    

    @Test
    public void testBadMethodEncoding() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
            "G\u00e6T / HTTP/1.0\r\nHeader0: value0\r\n\n\n");
        
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertThat(_bad,Matchers.notNullValue());
    }

    @Test
    public void testBadVersionEncoding() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
            "GET / H\u00e6P/1.0\r\nHeader0: value0\r\n\n\n");
        
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertThat(_bad,Matchers.notNullValue());
    }


    @Test
    public void testBadHeaderEncoding() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\nH\u00e6der0: value0\r\n\n\n");
        
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertThat(_bad,Matchers.notNullValue());
    } 

    @Test
    public void testNonStrict() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "get / http/1.0\015\012" +
                "HOST: localhost\015\012" +
                "cOnNeCtIoN: ClOsE\015\012"+
                "\015\012");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler,-1,false);
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
    }
    
    @Test
    public void testStrict() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "gEt / http/1.0\015\012" +
                "HOST: localhost\015\012" +
                "cOnNeCtIoN: ClOsE\015\012"+
                "\015\012");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler,-1,true);
        parseAll(parser,buffer);

        assertEquals("gEt", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("HOST", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("cOnNeCtIoN", _hdr[1]);
        assertEquals("ClOsE", _val[1]);
        assertEquals(1, _headers);
    }
    
    @Test
    public void testSplitHeaderParse() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "XXXXSPLIT / HTTP/1.0\015\012" +
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
            HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
            HttpParser parser= new HttpParser(handler);

            // System.err.println(BufferUtil.toDetailString(buffer));
            buffer.position(2);
            buffer.limit(2+i);

            if (!parser.parseNext(buffer))
            {
                // consumed all
                assertEquals(0,buffer.remaining());

                // parse the rest
                buffer.limit(buffer.capacity()-2);
                parser.parseNext(buffer);
            }

            assertEquals("SPLIT", _methodOrVersion);
            assertEquals("/", _uriOrStatus);
            assertEquals("HTTP/1.0", _versionOrReason);
            assertEquals("Host", _hdr[0]);
            assertEquals("localhost", _val[0]);
            assertEquals("Header1", _hdr[1]);
            assertEquals("value1", _val[1]);
            assertEquals("Header2", _hdr[2]);
            assertEquals("value 2a value 2b", _val[2]);
            assertEquals("Header3", _hdr[3]);
            assertEquals(null, _val[3]);
            assertEquals("Header4", _hdr[4]);
            assertEquals("value4", _val[4]);
            assertEquals("Server5", _hdr[5]);
            assertEquals("notServer", _val[5]);
            assertEquals(5, _headers);
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
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);
    }

    @Test
    public void testStartEOF() throws Exception
    {
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        assertTrue(_early);
        assertEquals(null,_bad);
    }

    @Test
    public void testEarlyEOF() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET /uri HTTP/1.0\015\012"
                        + "Content-Length: 20\015\012"
                        + "\015\012"
                        + "0123456789");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.atEOF();
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/uri", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("0123456789", _content);
        
        assertTrue(_early);
    }

    @Test
    public void testChunkEarlyEOF() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET /chunk HTTP/1.0\015\012"
                        + "Header1: value1\015\012"
                        + "Transfer-Encoding: chunked\015\012"
                        + "\015\012"
                        + "a;\015\012"
                        + "0123456789\015\012");
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.atEOF();
        parseAll(parser,buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789", _content);
        
        assertTrue(_early);
        
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

                        + "\015\012"

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

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/mp", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        init();
        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header2", _hdr[1]);
        assertEquals("value2", _val[1]);
        assertEquals(null, _content);

        parser.reset();
        init();
        parser.parseNext(buffer);
        parser.atEOF();
        assertEquals("PUT", _methodOrVersion);
        assertEquals("/doodle", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header3", _hdr[1]);
        assertEquals("value3", _val[1]);
        assertEquals("0123456789", _content);
    }
    

    @Test
    public void testMultiParseEarlyEOF() throws Exception
    {
        ByteBuffer buffer0= BufferUtil.toBuffer(
                          "GET /mp HTTP/1.0\015\012"
                        + "Connection: Keep-Alive\015\012");

        ByteBuffer buffer1= BufferUtil.toBuffer("Header1: value1\015\012"
                        + "Transfer-Encoding: chunked\015\012"
                        + "\015\012"
                        + "a;\015\012"
                        + "0123456789\015\012"
                        + "1a\015\012"
                        + "ABCDEFGHIJKLMNOPQRSTUVWXYZ\015\012"
                        + "0\015\012"

                        + "\015\012"

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


        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer0);
        parser.atEOF();
        parser.parseNext(buffer1);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/mp", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        init();
        parser.parseNext(buffer1);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header2", _hdr[1]);
        assertEquals("value2", _val[1]);
        assertEquals(null, _content);

        parser.reset();
        init();
        parser.parseNext(buffer1);
        assertEquals("PUT", _methodOrVersion);
        assertEquals("/doodle", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header3", _hdr[1]);
        assertEquals("value3", _val[1]);
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

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(10,_content.length());
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseParse1() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 304 Not-Modified\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("Not-Modified", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
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

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No-Content", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        parser.reset();
        init();

        parser.parseNext(buffer);
        parser.atEOF();
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
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

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
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

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseEOFContent() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 200 \015\012"
                        + "Content-Type: text/plain\015\012"
                        + "\015\012"
                        + "0123456789\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(buffer);
        
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(12,_content.length());
        assertEquals("0123456789\015\012",_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }    
    
    @Test
    public void testResponse304WithContentLength() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 304 found\015\012"
                        + "Content-Length: 10\015\012"
                        + "\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("found", _versionOrReason);
        assertEquals(null,_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponse101WithTransferEncoding() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 101 switching protocols\015\012"
                        + "Transfer-Encoding: chunked\015\012"
                        + "\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("101", _uriOrStatus);
        assertEquals("switching protocols", _versionOrReason);
        assertEquals(null,_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
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
                        + "HTTP/1.1 400 OK\015\012");  // extra data causes close ??


        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("OK", _versionOrReason);
        assertEquals(null,_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        parser.reset();
        parser.parseNext(buffer);
        assertFalse(buffer.hasRemaining());
        assertTrue(parser.isClosed());
    }
    
    

    @Test
    public void testNoURI() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET\015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("No URI",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }


    @Test
    public void testNoURI2() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET \015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("No URI",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }

    @Test
    public void testUnknownReponseVersion() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HPPT/7.7 200 OK\015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("Unknown Version",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }

    @Test
    public void testNoStatus() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1\015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("No Status",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }

    @Test
    public void testNoStatus2() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "HTTP/1.1 \015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.ResponseHandler<ByteBuffer> handler = new Handler();
        HttpParser parser= new HttpParser(handler);
        
        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("No Status",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }

    @Test
    public void testBadRequestVersion() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HPPT/7.7\015\012"
                        + "Content-Length: 0\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("Unknown Version",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState()); 
        
        buffer= BufferUtil.toBuffer(
            "GET / HTTP/1.01\015\012"
                + "Content-Length: 0\015\012"
                + "Connection: close\015\012"
                + "\015\012");

        handler = new Handler();handler = new Handler();
        parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null,_methodOrVersion);
        assertEquals("Unknown Version",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }
    
    @Test
    public void testBadCR() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\r\n"
                        + "Content-Length: 0\r"
                        + "Connection: close\r"
                        + "\r");

        HttpParser.RequestHandler<ByteBuffer> handler = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("Bad EOL",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());


        buffer= BufferUtil.toBuffer(
            "GET / HTTP/1.0\r"
                + "Content-Length: 0\r"
                + "Connection: close\r"
                + "\r");

        handler = new Handler();
        parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("Bad EOL",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }
    
    
    

    @Test
    public void testBadContentLength0() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012"
                        + "Content-Length: abc\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET",_methodOrVersion);
        assertEquals("Bad Content-Length",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }

    @Test
    public void testBadContentLength1() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012"
                        + "Content-Length: 9999999999999999999999999999999999999999999999\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET",_methodOrVersion);
        assertEquals("Bad Content-Length",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }

    @Test
    public void testBadContentLength2() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.0\015\012"
                        + "Content-Length: 1.5\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET",_methodOrVersion);
        assertEquals("Bad Content-Length",_bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSED,parser.getState());
    }
    
    @Test
    public void testHost() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: host\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("host",_host);
        assertEquals(0,_port);
    }
    
    @Test
    public void testIPHost() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: 192.168.0.1\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1",_host);
        assertEquals(0,_port);
    }
    
    @Test
    public void testIPv6Host() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: [::1]\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("::1",_host);
        assertEquals(0,_port);
    }
    
    @Test
    public void testBadIPv6Host() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: [::1\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("Bad IPv6 Host header",_bad);
    }
    
    @Test
    public void testHostPort() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: myhost:8888\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("myhost",_host);
        assertEquals(8888,_port);
    }
    
    @Test
    public void testHostBadPort() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: myhost:xxx\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("Bad Host header",_bad);
    }

    @Test
    public void testIPHostPort() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: 192.168.0.1:8888\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1",_host);
        assertEquals(8888,_port);
    }

    @Test
    public void testIPv6HostPort() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
                "GET / HTTP/1.1\015\012"
                        + "Host: [::1]:8888\015\012"
                        + "Connection: close\015\012"
                        + "\015\012");

        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("::1",_host);
        assertEquals(8888,_port);
    }

    @Test
    public void testCachedField() throws Exception
    {
        ByteBuffer buffer= BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n"+
            "Host: www.smh.com.au\r\n"+
            "\r\n");
        
        HttpParser.RequestHandler<ByteBuffer> handler  = new Handler();
        HttpParser parser= new HttpParser(handler);
        parseAll(parser,buffer);
        assertEquals("www.smh.com.au",parser.getFieldCache().get("Host: www.smh.com.au").getValue());
        HttpField field=_fields.get(0);
        
        //System.err.println(parser.getFieldCache());
        
        buffer.position(0);
        parseAll(parser,buffer);
        assertTrue(field==_fields.get(0));
        
    }

    @Before
    public void init()
    {
        _bad=null;
        _content=null;
        _methodOrVersion=null;
        _uriOrStatus=null;
        _versionOrReason=null;
        _hdr=null;
        _val=null;
        _headers=0;
        _headerCompleted=false;
        _messageCompleted=false;
    }

    private String _host;
    private int _port;
    private String _bad;
    private String _content;
    private String _methodOrVersion;
    private String _uriOrStatus;
    private String _versionOrReason;
    private List<HttpField> _fields=new ArrayList<>();
    private String[] _hdr;
    private String[] _val;
    private int _headers;
    
    private boolean _early;
    private boolean _headerCompleted;
    private boolean _messageCompleted;

    private class Handler implements HttpParser.RequestHandler<ByteBuffer>, HttpParser.ResponseHandler<ByteBuffer>
    {
        private HttpFields fields;

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
        public boolean startRequest(HttpMethod httpMethod, String method, ByteBuffer uri, HttpVersion version)
        {
            _fields.clear();
            _headers= -1;
            _hdr= new String[10];
            _val= new String[10];
            _methodOrVersion= method;
            _uriOrStatus= BufferUtil.toUTF8String(uri);
            _versionOrReason= version==null?null:version.asString();

            fields=new HttpFields();
            _messageCompleted = false;
            _headerCompleted = false;
            _early=false;
            return false;
        }

        @Override
        public boolean parsedHeader(HttpField field)
        {
            _fields.add(field);
            //System.err.println("header "+name+": "+value);
            _hdr[++_headers]= field.getName();
            _val[_headers]= field.getValue();
            return false;
        }

        @Override
        public boolean parsedHostHeader(String host,int port)
        {
            _host=host;
            _port=port;
            return false;
        }

        @Override
        public boolean headerComplete()
        {
            //System.err.println("headerComplete");
            _content= null;
            String s0=fields.toString();
            String s1=fields.toString();
            if (!s0.equals(s1))
            {
                throw new IllegalStateException();
            }

            _headerCompleted = true;
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            //System.err.println("messageComplete");
            _messageCompleted = true;
            return true;
        }

        @Override
        public void badMessage(int status, String reason)
        {
            _bad=reason==null?(""+status):reason;
        }

        @Override
        public boolean startResponse(HttpVersion version, int status, String reason)
        {
            _fields.clear();
            _methodOrVersion = version.asString();
            _uriOrStatus = Integer.toString(status);
            _versionOrReason = reason==null?null:reason.toString();

            fields=new HttpFields();
            _hdr= new String[9];
            _val= new String[9];

            _messageCompleted = false;
            _headerCompleted = false;
            return false;
        }

        @Override
        public void earlyEOF()
        {
            _early=true;
        }

        @Override
        public int getHeaderCacheSize()
        {
            return 512;
        }
    }
}
