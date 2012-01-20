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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Exchanger;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Test;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThat;

/**
 *
 */
public abstract class HttpServerTestBase extends HttpServerTestFixture
{
    /** The request. */
    private static final String REQUEST1_HEADER="POST / HTTP/1.0\n"+"Host: localhost\n"+"Content-Type: text/xml; charset=utf-8\n"+"Connection: close\n"+"Content-Length: ";
    private static final String REQUEST1_CONTENT="<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
            +"<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+"        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n"
            +"</nimbus>";
    private static final String REQUEST1=REQUEST1_HEADER+REQUEST1_CONTENT.getBytes().length+"\n\n"+REQUEST1_CONTENT;

    /** The expected response. */
    private static final String RESPONSE1="HTTP/1.1 200 OK\n"+"Content-Length: 13\n"+"Server: Jetty("+Server.getVersion()+")\n"+"\n"+"Hello world\n";

    // Break the request up into three pieces, splitting the header.
    private static final String FRAGMENT1=REQUEST1.substring(0,16);
    private static final String FRAGMENT2=REQUEST1.substring(16,34);
    private static final String FRAGMENT3=REQUEST1.substring(34);

    /** Second test request. */
    protected static final String REQUEST2_HEADER=
        "POST / HTTP/1.0\n"+
        "Host: localhost\n"+
        "Content-Type: text/xml;charset=ISO-8859-1\n"+
        "Content-Length: ";
    protected static final String REQUEST2_CONTENT=
        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"+
        "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+
        "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n"+
        "    <request requestId=\"1\">\n"+
        "        <getJobDetails>\n"+
        "            <jobId>73</jobId>\n"+
        "        </getJobDetails>\n"+
        "    </request>\n"+
        "</nimbus>";
    protected static final String REQUEST2=REQUEST2_HEADER+REQUEST2_CONTENT.getBytes().length+"\n\n"+REQUEST2_CONTENT;

    /** The second expected response. */
    protected static final String RESPONSE2_CONTENT=
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"+
            "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+
            "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n"+
            "    <request requestId=\"1\">\n"+
            "        <getJobDetails>\n"+
            "            <jobId>73</jobId>\n"+
            "        </getJobDetails>\n"+
            "    </request>\n"
            +"</nimbus>\n";
    protected static final String RESPONSE2=
        "HTTP/1.1 200 OK\n"+
        "Content-Type: text/xml;charset=ISO-8859-1\n"+
        "Content-Length: "+RESPONSE2_CONTENT.getBytes().length+"\n"+
        "Server: Jetty("+Server.getVersion()+")\n"+
        "\n"+
        RESPONSE2_CONTENT;





    /*
     * Feed the server the entire request at once.
     */
    @Test
    public void testRequest1() throws Exception
    {
        configureServer(new HelloWorldHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();

            os.write(REQUEST1.getBytes());
            os.flush();

            // Read the response.
            String response=readResponse(client);

            // Check the response
            assertEquals("response",RESPONSE1,response);
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testFragmentedChunk() throws Exception
    {
        configureServer(new EchoHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();

            os.write(("GET /R2 HTTP/1.1\015\012"+
                    "Host: localhost\015\012"+
                    "Transfer-Encoding: chunked\015\012"+
                    "Content-Type: text/plain\015\012"+
                    "Connection: close\015\012"+
                    "\015\012").getBytes());
            os.flush();
            Thread.sleep(PAUSE);
            os.write(("5\015\012").getBytes());
            os.flush();
            Thread.sleep(PAUSE);
            os.write(("ABCDE\015\012"+
                      "0;\015\012\015\012").getBytes());
            os.flush();

            // Read the response.
            String response=readResponse(client);
            assertTrue (response.indexOf("200")>0);
        }
        finally
        {
            client.close();
        }
    }

    /*
     * Feed the server fragmentary headers and see how it copes with it.
     */
    @Test
    public void testRequest1Fragments() throws Exception, InterruptedException
    {
        configureServer(new HelloWorldHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();

            // Write a fragment, flush, sleep, write the next fragment, etc.
            os.write(FRAGMENT1.getBytes());
            os.flush();
            Thread.sleep(PAUSE);
            os.write(FRAGMENT2.getBytes());
            os.flush();
            Thread.sleep(PAUSE);
            os.write(FRAGMENT3.getBytes());
            os.flush();

            // Read the response
            String response = readResponse(client);

            // Check the response
            assertEquals("response",RESPONSE1,response);
        }
        finally
        {
            client.close();
        }

    }

    @Test
    public void testRequest2() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes=REQUEST2.getBytes();
        for (int i=0; i<LOOPS; i++)
        {
            Socket client=newSocket(HOST,_connector.getLocalPort());
            try
            {
                OutputStream os=client.getOutputStream();

                os.write(bytes);
                os.flush();

                // Read the response
                String response=readResponse(client);

                // Check the response
                assertEquals("response "+i,RESPONSE2,response);
            }
            catch(IOException e)
            {
                e.printStackTrace();
                _server.dumpStdErr();
                throw e;
            }
            finally
            {
                client.close();
            }
        }
    }

    @Test
    public void testRequest2Fragments() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes=REQUEST2.getBytes();
        final int pointCount=2;
        Random random=new Random(System.currentTimeMillis());
        for (int i=0; i<LOOPS; i++)
        {
            int[] points=new int[pointCount];
            StringBuilder message=new StringBuilder();

            message.append("iteration #").append(i + 1);

            // Pick fragment points at random
            for (int j=0; j<points.length; ++j)
            {
                points[j]=random.nextInt(bytes.length);
            }

            // Sort the list
            Arrays.sort(points);

            Socket client=newSocket(HOST,_connector.getLocalPort());
            try
            {
                OutputStream os=client.getOutputStream();

                writeFragments(bytes,points,message,os);

                // Read the response
                String response=readResponse(client);

                // Check the response
                assertEquals("response for "+i+" "+message.toString(),RESPONSE2,response);
            }
            finally
            {
                client.close();
            }
        }
    }

    @Test
    public void testRequest2Iterate() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes=REQUEST2.getBytes();
        for (int i=0; i<bytes.length; i+=3)
        {
            int[] points=new int[] { i };
            StringBuilder message=new StringBuilder();

            message.append("iteration #").append(i + 1);

            // Sort the list
            Arrays.sort(points);

            Socket client=newSocket(HOST,_connector.getLocalPort());
            try
            {
                OutputStream os=client.getOutputStream();

                writeFragments(bytes,points,message,os);

                // Read the response
                String response=readResponse(client);

                // Check the response
                assertEquals("response for "+i+" "+message.toString(),RESPONSE2,response);
            }
            finally
            {
                client.close();
            }
        }
    }

    /*
     * After several iterations, I generated some known bad fragment points.
     */
    @Test
    public void testRequest2KnownBad() throws Exception
    {
        configureServer(new EchoHandler());

        byte[] bytes=REQUEST2.getBytes();
        int[][] badPoints=new int[][]
        {
                { 70 }, // beginning here, drops last line of request
                { 71 }, // no response at all
                { 72 }, // again starts drops last line of request
                { 74 }, // again, no response at all
        };
        for (int i=0; i<badPoints.length; ++i)
        {
            Socket client=newSocket(HOST,_connector.getLocalPort());
            try
            {
                OutputStream os=client.getOutputStream();
                StringBuilder message=new StringBuilder();

                message.append("iteration #").append(i + 1);
                writeFragments(bytes,badPoints[i],message,os);

                // Read the response
                String response=readResponse(client);

                // Check the response
                // TODO - change to equals when code gets fixed
                assertNotSame("response for "+message.toString(),RESPONSE2,response);
            }
            finally
            {
                client.close();
            }
        }
    }

    @Test
    public void testFlush() throws Exception
    {
        configureServer(new DataHandler());

        String[] encoding = {"NONE","UTF-8","ISO-8859-1","ISO-8859-2"};
        for (int e =0; e<encoding.length;e++)
        {
            for (int b=1;b<=128;b=b==1?2:b==2?32:b==32?128:129)
            {
                for (int w=41;w<42;w+=4096)
                {
                    for (int c=0;c<1;c++)
                    {
                        String test=encoding[e]+"x"+b+"x"+w+"x"+c;
                        try
                        {
                            URL url=new URL(_scheme+"://"+HOST+":"+_connector.getLocalPort()+"/?writes="+w+"&block="+b+ (e==0?"":("&encoding="+encoding[e]))+(c==0?"&chars=true":""));

                            InputStream in = (InputStream)url.getContent();
                            String response=IO.toString(in,e==0?null:encoding[e]);

                            assertEquals(test,b*w,response.length());
                        }
                        catch(Exception x)
                        {
                            System.err.println(test);
                            x.printStackTrace();
                            throw x;
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testBlockingWhileReadingRequestContent() throws Exception
    {
        configureServer(new DataHandler());

        long start=System.currentTimeMillis();
        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "GET /data?writes=1024&block=256 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "connection: close\r\n"+
                    "content-type: unknown\r\n"+
                    "content-length: 30\r\n"+
                    "\r\n"
            ).getBytes());
            os.flush();
            Thread.sleep(200);
            os.write((
                    "\r\n23456890"
            ).getBytes());
            os.flush();
            Thread.sleep(1000);
            os.write((
                    "abcdefghij"
            ).getBytes());
            os.flush();
            Thread.sleep(1000);
            os.write((
                    "0987654321\r\n"
            ).getBytes());
            os.flush();

            int total=0;
            int len=0;
            byte[] buf=new byte[1024*64];

            while(len>=0)
            {
                Thread.sleep(100);
                len=is.read(buf);
                if (len>0)
                    total+=len;
            }

            assertTrue(total>(1024*256));
            assertTrue(30000L>(System.currentTimeMillis()-start));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testBlockingWhileWritingResponseContent() throws Exception
    {
        configureServer(new DataHandler());

        long start=System.currentTimeMillis();
        Socket client=newSocket(HOST,_connector.getLocalPort());
        int total=0;
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "GET /data?writes=512&block=1024 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "connection: close\r\n"+
                    "content-type: unknown\r\n"+
                    "\r\n"
            ).getBytes());
            os.flush();

            int len=0;
            byte[] buf=new byte[1024*32];

            int i=0;
            while(len>=0)
            {
                if (i++%10==0)
                    Thread.sleep(1000);
                len=is.read(buf);
                if (len>0)
                    total+=len;
            }

            assertTrue(total>(512*1024));
            assertTrue(30000L>(System.currentTimeMillis()-start));
        }
        finally
        {
            //System.err.println("Got "+total+" of "+(512*1024));
            client.close();
        }
    }

    @Test
    public void testBigBlocks() throws Exception
    {
        configureServer(new BigBlockHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(20000);
        try
        {
            OutputStream os=client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            os.write((
                    "GET /r1 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "\r\n"+
                    "GET /r2 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "connection: close\r\n"+
                    "\r\n"
            ).getBytes());
            os.flush();

            // read the chunked response header
            boolean chunked=false;
            boolean closed=false;
            while(true)
            {
                String line=in.readLine();
                if (line==null || line.length()==0)
                    break;

                chunked|="Transfer-Encoding: chunked".equals(line);
                closed|="Connection: close".equals(line);
            }
            Assert.assertTrue(chunked);
            Assert.assertFalse(closed);

            // Read the chunks
            int max=Integer.MIN_VALUE;
            while(true)
            {
                String chunk=in.readLine();
                String line=in.readLine();
                if (line.length()==0)
                    break;
                int len=line.length();
                Assert.assertEquals(Integer.valueOf(chunk,16).intValue(),len);
                if (max<len)
                    max=len;
            }

            // Check that a direct content buffer was used as a chunk
            Assert.assertEquals(128*1024,max);

            // read and check the times are < 999ms
            String[] times=in.readLine().split(",");
            for (String t: times)
               Assert.assertTrue(Integer.valueOf(t).intValue()<999);


            // read the EOF chunk
            String end=in.readLine();
            Assert.assertEquals("0",end);
            end=in.readLine();
            Assert.assertEquals(0,end.length());


            // read the non-chunked response header
            chunked=false;
            closed=false;
            while(true)
            {
                String line=in.readLine();
                if (line==null || line.length()==0)
                    break;

                chunked|="Transfer-Encoding: chunked".equals(line);
                closed|="Connection: close".equals(line);
            }
            Assert.assertFalse(chunked);
            Assert.assertTrue(closed);

            String bigline = in.readLine();
            Assert.assertEquals(10*128*1024,bigline.length());

            // read and check the times are < 999ms
            times=in.readLine().split(",");
            for (String t: times)
                Assert.assertTrue(t,Integer.valueOf(t).intValue()<999);

            // check close
            Assert.assertTrue(in.readLine()==null);
        }
        finally
        {
            client.close();
        }
    }

    // Handler that sends big blocks of data in each of 10 writes, and then sends the time it took for each big block.
    protected static class BigBlockHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            byte[] buf = new byte[128*1024];
            for (int i=0;i<buf.length;i++)
                buf[i]=(byte)("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_".charAt(i%63));

            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("text/plain");
            ServletOutputStream out=response.getOutputStream();
            long[] times=new long[10];
            for (int i=0;i<times.length;i++)
            {
                // System.err.println("\nBLOCK "+request.getRequestURI()+" "+i);
                long start=System.currentTimeMillis();
                out.write(buf);
                long end=System.currentTimeMillis();
                times[i]=end-start;
                // System.err.println("Block "+request.getRequestURI()+" "+i+" "+times[i]);
            }
            out.println();
            for (long t : times)
            {
                out.print(t);
                out.print(",");
            }
            out.close();
        }
    }


    @Test
    public void testPipeline() throws Exception
    {
        configureServer(new HelloWorldHandler());

        //for (int pipeline=1;pipeline<32;pipeline++)
        for (int pipeline=1;pipeline<32;pipeline++)
        {
            Socket client=newSocket(HOST,_connector.getLocalPort());
            try
            {
                client.setSoTimeout(5000);
                OutputStream os=client.getOutputStream();

                String request="";

                for (int i=1;i<pipeline;i++)
                    request+=
                        "GET /data?writes=1&block=16&id="+i+" HTTP/1.1\r\n"+
                        "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                        "user-agent: testharness/1.0 (blah foo/bar)\r\n"+
                        "accept-encoding: nothing\r\n"+
                        "cookie: aaa=1234567890\r\n"+
                        "\r\n";

                request+=
                    "GET /data?writes=1&block=16 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "user-agent: testharness/1.0 (blah foo/bar)\r\n"+
                    "accept-encoding: nothing\r\n"+
                    "cookie: aaa=bbbbbb\r\n"+
                    "Connection: close\r\n"+
                    "\r\n";

                os.write(request.getBytes());
                os.flush();

                LineNumberReader in = new LineNumberReader(new InputStreamReader(client.getInputStream()));

                String line = in.readLine();
                int count=0;
                while (line!=null)
                {
                    if ("HTTP/1.1 200 OK".equals(line))
                        count++;
                    line = in.readLine();
                }
                assertEquals(pipeline,count);
            }
            finally
            {
                client.close();
            }
        }
    }

    @Test
    public void testRecycledWriters() throws Exception
    {
        configureServer(new EchoHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: 10\r\n"+
                    "\r\n").getBytes("iso-8859-1"));

            os.write((
                    "123456789\n"
            ).getBytes("utf-8"));

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: 10\r\n"+
                    "\r\n"
            ).getBytes("iso-8859-1"));

            os.write((
                    "abcdefghZ\n"
            ).getBytes("utf-8"));

            String content="Wibble";
            byte[] contentB=content.getBytes("utf-8");
            os.write((
                    "POST /echo?charset=utf-16 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: "+contentB.length+"\r\n"+
                    "connection: close\r\n"+
                    "\r\n"
            ).getBytes("iso-8859-1"));
            os.write(contentB);

            os.flush();

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            IO.copy(is,bout);
            byte[] b=bout.toByteArray();

            //System.err.println("OUTPUT: "+new String(b));
            int i=0;
            while (b[i]!='Z')
                i++;
            int state=0;
            while(state!=4)
            {
                switch(b[i++])
                {
                    case '\r':
                        if (state==0||state==2)
                            state++;
                        continue;
                    case '\n':
                        if (state==1||state==3)
                            state++;
                        continue;

                    default:
                        state=0;
                }
            }

            String in = new String(b,0,i,"utf-8");
            assertTrue(in.indexOf("123456789")>=0);
            assertTrue(in.indexOf("abcdefghZ")>=0);
            assertTrue(in.indexOf("Wibble")<0);

            in = new String(b,i,b.length-i,"utf-16");
            assertEquals("Wibble\n",in);
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testHead() throws Exception
    {
        configureServer(new EchoHandler(false));

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                "POST /R1 HTTP/1.1\015\012"+
                "Host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "content-type: text/plain; charset=utf-8\r\n"+
                "content-length: 10\r\n"+
                "\015\012"+
                "123456789\n" +

                "HEAD /R1 HTTP/1.1\015\012"+
                "Host: "+HOST+":"+_connector.getLocalPort()+"\015\012"+
                "content-type: text/plain; charset=utf-8\r\n"+
                "content-length: 10\r\n"+
                "\015\012"+
                "123456789\n"+

                "POST /R1 HTTP/1.1\015\012"+
                "Host: "+HOST+":"+_connector.getLocalPort()+"\015\012"+
                "content-type: text/plain; charset=utf-8\r\n"+
                "content-length: 10\r\n"+
                "Connection: close\015\012"+
                "\015\012"+
                "123456789\n"

                ).getBytes("iso-8859-1"));

            String in = IO.toString(is);

            int index=in.indexOf("123456789");
            assertTrue(index>0);
            index=in.indexOf("123456789",index+1);
            assertTrue(index>0);
            index=in.indexOf("123456789",index+1);
            assertTrue(index==-1);

        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testRecycledReaders() throws Exception
    {
        configureServer(new EchoHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: 10\r\n"+
                    "\r\n").getBytes("iso-8859-1"));

            os.write((
                    "123456789\n"
            ).getBytes("utf-8"));

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: 10\r\n"+
                    "\r\n"
            ).getBytes("iso-8859-1"));

            os.write((
                    "abcdefghi\n"
            ).getBytes("utf-8"));

            String content="Wibble";
            byte[] contentB=content.getBytes("utf-16");
            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "content-type: text/plain; charset=utf-16\r\n"+
                    "content-length: "+contentB.length+"\r\n"+
                    "connection: close\r\n"+
                    "\r\n"
            ).getBytes("iso-8859-1"));
            os.write(contentB);

            os.flush();

            String in = IO.toString(is);
            assertTrue(in.indexOf("123456789")>=0);
            assertTrue(in.indexOf("abcdefghi")>=0);
            assertTrue(in.indexOf("Wibble")>=0);
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testBlockedClient() throws Exception
    {
        configureServer(new HelloWorldHandler());

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            // Send a request with chunked input and expect 100
            os.write((
                    "GET / HTTP/1.1\r\n"+
                    "Host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "Transfer-Encoding: chunked\r\n"+
                    "Expect: 100-continue\r\n"+
                    "Connection: Keep-Alive\r\n"+
                    "\r\n"
            ).getBytes());

            // Never send a body.
            // HelloWorldHandler does not read content, so 100 is not sent.
            // So close will have to happen anyway, without reset!

            os.flush();

            client.setSoTimeout(2000);
            long start=System.currentTimeMillis();
            String in = IO.toString(is);
            assertTrue(System.currentTimeMillis()-start<1000);
            assertTrue(in.indexOf("Connection: close")>0);
            assertTrue(in.indexOf("Hello world")>0);

        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testCommittedError() throws Exception
    {
        CommittedErrorHandler handler =new CommittedErrorHandler();
        configureServer(handler);

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            ((StdErrLog)Log.getLogger(AbstractHttpConnection.class)).setHideStacks(true);
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            // Send a request
            os.write((
                    "GET / HTTP/1.1\r\n"+
                    "Host: "+HOST+":"+_connector.getLocalPort()+"\r\n" +
                    "\r\n"
            ).getBytes());
            os.flush();

            client.setSoTimeout(2000);
            String in = IO.toString(is);

            assertEquals(-1,is.read()); // Closed by error!

            assertTrue(in.indexOf("HTTP/1.1 200 OK")>=0);
            assertTrue(in.indexOf("Transfer-Encoding: chunked")>0);
            assertTrue(in.indexOf("Now is the time for all good men to come to the aid of the party")>0);
            assertTrue(in.indexOf("\r\n0\r\n")==-1); // chunking is interrupted by error close

            client.close();
            Thread.sleep(100);
            assertTrue(!handler._endp.isOpen());
        }
        finally
        {
            ((StdErrLog)Log.getLogger(AbstractHttpConnection.class)).setHideStacks(false);

            if (!client.isClosed())
                client.close();
        }
    }

    protected static class CommittedErrorHandler extends AbstractHandler
    {
        public EndPoint _endp;

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            _endp=baseRequest.getConnection().getEndPoint();
            response.setHeader("test","value");
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().println("Now is the time for all good men to come to the aid of the party");
            response.getWriter().flush();
            response.flushBuffer();

            throw new ServletException(new Exception("exception after commit"));
        }
    }

    protected static class AvailableHandler extends AbstractHandler
    {
        public Exchanger<Object> _ex = new Exchanger<Object>();

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.setContentType("text/plain");
            InputStream in = request.getInputStream();
            ServletOutputStream out=response.getOutputStream();

            // should always be some input available, because of deferred dispatch.
            int avail=in.available();
            out.println(avail);

            String buf="";
            for (int i=0;i<avail;i++)
                buf+=(char)in.read();


            avail=in.available();
            out.println(avail);

            try
            {
                _ex.exchange(null);
                _ex.exchange(null);
            }
            catch(InterruptedException e)
            {
                e.printStackTrace();
            }

            avail=in.available();

            if (avail==0)
            {
                // handle blocking channel connectors
                buf+=(char)in.read();
                avail=in.available();
                out.println(avail+1);
            }
            else if (avail==1)
            {
                // handle blocking socket connectors
                buf+=(char)in.read();
                avail=in.available();
                out.println(avail+1);
            }
            else
                out.println(avail);

            for (int i=0;i<avail;i++)
                buf+=(char)in.read();

            avail=in.available();
            out.println(avail);
            out.println(buf);
            out.close();
        }
    }


    @Test
    public void testAvailable() throws Exception
    {
        AvailableHandler ah=new AvailableHandler();
        configureServer(ah);

        Socket client=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "GET /data?writes=1024&block=256 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                    "connection: close\r\n"+
                    "content-type: unknown\r\n"+
                    "content-length: 30\r\n"+
                    "\r\n"
            ).getBytes());
            os.flush();
            Thread.sleep(500);
            os.write((
                    "1234567890"
            ).getBytes());
            os.flush();

            ah._ex.exchange(null);

            os.write((
                    "abcdefghijklmnopqrst"
            ).getBytes());
            os.flush();
            Thread.sleep(500);
            ah._ex.exchange(null);

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            // skip header
            while(reader.readLine().length()>0);
            assertEquals(10,Integer.parseInt(reader.readLine()));
            assertEquals(0,Integer.parseInt(reader.readLine()));
            assertEquals(20,Integer.parseInt(reader.readLine()));
            assertEquals(0,Integer.parseInt(reader.readLine()));
            assertEquals("1234567890abcdefghijklmnopqrst",reader.readLine());

        }
        finally
        {
            client.close();
        }
    }


    @Test
    public void testDualRequest1() throws Exception
    {
        configureServer(new HelloWorldHandler());

        Socket client1=newSocket(HOST,_connector.getLocalPort());
        Socket client2=newSocket(HOST,_connector.getLocalPort());
        try
        {
            OutputStream os1=client1.getOutputStream();
            OutputStream os2=client2.getOutputStream();

            os1.write(REQUEST1.getBytes());
            os2.write(REQUEST1.getBytes());
            os1.flush();
            os2.flush();

            // Read the response.
            String response1=readResponse(client1);
            String response2=readResponse(client2);

            // Check the response
            assertEquals("client1",RESPONSE1,response1);
            assertEquals("client2",RESPONSE1,response2);
        }
        finally
        {
            client1.close();
            client2.close();
        }
    }

    /**
     * Read entire response from the client. Close the output.
     *
     * @param client Open client socket.
     * @return The response string.
     * @throws IOException in case of I/O problems
     */
    protected static String readResponse(Socket client) throws IOException
    {
        BufferedReader br=null;

        StringBuilder sb=new StringBuilder();
        try
        {
            br=new BufferedReader(new InputStreamReader(client.getInputStream()));

            String line;

            while ((line=br.readLine())!=null)
            {
                sb.append(line);
                sb.append('\n');
            }

            return sb.toString();
        }
        catch(IOException e)
        {
            System.err.println(e+" while reading '"+sb+"'");
            throw e;
        }
        finally
        {
            if (br!=null)
            {
                br.close();
            }
        }
    }

    private void writeFragments(byte[] bytes, int[] points, StringBuilder message, OutputStream os) throws IOException, InterruptedException
    {
        int last=0;

        // Write out the fragments
        for (int j=0; j<points.length; ++j)
        {
            int point=points[j];

            os.write(bytes,last,point-last);
            last=point;
            os.flush();
            Thread.sleep(PAUSE);

            // Update the log message
            message.append(" point #").append(j + 1).append(": ").append(point);
        }

        // Write the last fragment
        os.write(bytes,last,bytes.length-last);
        os.flush();
        Thread.sleep(PAUSE);
    }



    @Test
    public void testUnreadInput () throws Exception
    {
        configureServer(new NoopHandler());
        final int REQS=5;
        String content="This is a loooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "ooooooooooooooooooooooooooooooooooooooooooooo"+
        "oooooooooooonnnnnnnnnnnnnnnnggggggggg content"+
        new String(new char[65*1024]);
        final byte[] bytes = content.getBytes();

        Socket client=newSocket(HOST,_connector.getLocalPort());
        final OutputStream out=client.getOutputStream();

        new Thread()
        {
            public void run()
            {
                try
                {
                    for (int i=0; i<REQS; i++)
                    {
                        out.write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StringUtil.__ISO_8859_1));
                        out.write(("Content-Length: "+bytes.length+"\r\n" + "\r\n").getBytes(StringUtil.__ISO_8859_1));
                        out.write(bytes,0,bytes.length);
                    }
                    out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StringUtil.__ISO_8859_1));
                    out.flush();
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
            
        String resps = readResponse(client);
               
        int offset=0;
        for (int i=0;i<(REQS+1);i++)
        {
            int ok=resps.indexOf("HTTP/1.1 200 OK",offset);
            assertThat("resp"+i,ok,greaterThanOrEqualTo(offset));
            offset=ok+15;
        }
    }

    public class NoopHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest,
                HttpServletRequest request, HttpServletResponse response) throws IOException,
                ServletException
        {
           //don't read the input, just send something back
            ((Request)request).setHandled(true);
            response.setStatus(200);
        }
    }
}
