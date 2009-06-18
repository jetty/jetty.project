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
import java.io.PrintWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
/**
 * HttpServer Tester.
 */
public class HttpServerTestBase extends TestCase
{
    private static boolean stress=Boolean.getBoolean("STRESS");
    
    // ~ Static fields/initializers
    // ---------------------------------------------

    /** The request. */
    private static final String REQUEST1_HEADER="POST / HTTP/1.0\n"+"Host: localhost\n"+"Content-Type: text/xml; charset=utf-8\n"+"Connection: close\n"+"Content-Length: ";
    private static final String REQUEST1_CONTENT="<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
            +"<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+"        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n"
            +"</nimbus>";
    private static final String REQUEST1=REQUEST1_HEADER+REQUEST1_CONTENT.getBytes().length+"\n\n"+REQUEST1_CONTENT;

    /** The expected response. */
    private static final String RESPONSE1="HTTP/1.1 200 OK\n"+"Connection: close\n"+"Server: Jetty("+Server.getVersion()+")\n"+"\n"+"Hello world\n";

    // Break the request up into three pieces, splitting the header.
    private static final String FRAGMENT1=REQUEST1.substring(0,16);
    private static final String FRAGMENT2=REQUEST1.substring(16,34);
    private static final String FRAGMENT3=REQUEST1.substring(34);

    /** Second test request. */
    private static final String REQUEST2_HEADER=
        "POST / HTTP/1.0\n"+
        "Host: localhost\n"+
        "Content-Type: text/xml\n"+
        "Content-Length: ";

    private static final String REQUEST2_CONTENT=
        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"+
        "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+
        "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n"+
        "    <request requestId=\"1\">\n"+
        "        <getJobDetails>\n"+
        "            <jobId>73</jobId>\n"+
        "        </getJobDetails>\n"+
        "    </request>\n"+
        "</nimbus>";

    private static final String REQUEST2=REQUEST2_HEADER+REQUEST2_CONTENT.getBytes().length+"\n\n"+REQUEST2_CONTENT;

    private static final String RESPONSE2_CONTENT=
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"+
            "<nimbus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"+
            "        xsi:noNamespaceSchemaLocation=\"nimbus.xsd\" version=\"1.0\">\n"+
            "    <request requestId=\"1\">\n"+
            "        <getJobDetails>\n"+
            "            <jobId>73</jobId>\n"+
            "        </getJobDetails>\n"+
            "    </request>\n"
            +"</nimbus>\n";
    private static final String RESPONSE2=
        "HTTP/1.1 200 OK\n"+
        "Content-Type: text/xml;charset=ISO-8859-1\n"+
        "Content-Length: "+RESPONSE2_CONTENT.getBytes().length+"\n"+
        "Server: Jetty("+Server.getVersion()+")\n"+
        "\n"+
        RESPONSE2_CONTENT;

    // Useful constants
    private static final long PAUSE=10L;
    private static final int LOOPS=stress?250:25;
    private static final String HOST="localhost";

    private Connector _connector;
    private int port=0;

    protected void tearDown() throws Exception
    {
        super.tearDown();
        Thread.sleep(100);
    }

    // ~ Methods
    // ----------------------------------------------------------------

    /**
     * Feed the server the entire request at once.
     * 
     * @throws Exception
     * @throws InterruptedException
     */
    public void testRequest1_jetty() throws Exception, InterruptedException
    {
        Server server=startServer(new HelloWorldHandler());
        Socket client=new Socket(HOST,port);
        OutputStream os=client.getOutputStream();

        os.write(REQUEST1.getBytes());
        os.flush();

        // Read the response.
        String response=readResponse(client);

        // Shut down
        client.close();
        server.stop();

        // Check the response
        assertEquals("response",RESPONSE1,response);
    }

    /* --------------------------------------------------------------- */
    public void testFragmentedChunk() throws Exception
    {

        Server server=startServer(new EchoHandler());
        Socket client=new Socket(HOST,port);
        OutputStream os=client.getOutputStream();

        os.write(("GET /R2 HTTP/1.1\015\012"+"Host: localhost\015\012"+"Transfer-Encoding: chunked\015\012"+"Content-Type: text/plain\015\012"
                +"Connection: close\015\012"+"\015\012").getBytes());
        os.flush();
        Thread.sleep(PAUSE);
        os.write(("5\015\012").getBytes());
        os.flush();
        Thread.sleep(PAUSE);
        os.write(("ABCDE\015\012"+"0;\015\012\015\012").getBytes());
        os.flush();

        // Read the response.
        String response=readResponse(client);

        // Shut down
        client.close();
        server.stop();

        assertTrue(true); // nothing checked yet.

    }

    /**
     * Feed the server fragmentary headers and see how it copes with it.
     * 
     * @throws Exception
     * @throws InterruptedException
     */
    public void testRequest1Fragments_jetty() throws Exception, InterruptedException
    {
        Server server=startServer(new HelloWorldHandler());
        String response;

        try
        {
            Socket client=new Socket(HOST,port);
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
            response=readResponse(client);

            // Shut down
            client.close();
        }
        finally
        {
            server.stop();
        }

        // Check the response
        assertEquals("response",RESPONSE1,response);
    }

    public void testRequest2_jetty() throws Exception
    {
        byte[] bytes=REQUEST2.getBytes();
        Server server=startServer(new EchoHandler());

        try
        {
            for (int i=0; i<LOOPS; i++)
            {
                Socket client=new Socket(HOST,port);
                OutputStream os=client.getOutputStream();

                os.write(bytes);
                os.flush();

                // Read the response
                String response=readResponse(client);
                client.close();

                // Check the response
                assertEquals("response "+i,RESPONSE2,response);
            }
        }
        finally
        {
            // Shut down
            server.stop();
        }
    }

    /**
     * @throws Exception
     */
    public void testRequest2Fragments_jetty() throws Exception
    {
        Random random=new Random(System.currentTimeMillis());
        byte[] bytes=REQUEST2.getBytes();
        final int pointCount=2;
        Server server=startServer(new EchoHandler());

        try
        {

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

                Socket client=new Socket(HOST,port);
                OutputStream os=client.getOutputStream();

                writeFragments(bytes,points,message,os);

                // Read the response
                String response=readResponse(client);

                // Close the client
                client.close();

                // Check the response
                assertEquals("response for "+i+" "+message.toString(),RESPONSE2,response);
            }
        }
        finally
        {
            // Shut down
            server.stop();
        }
    }

    public void testRequest2Iterate_jetty() throws Exception
    {
        byte[] bytes=REQUEST2.getBytes();
        Server server=startServer(new EchoHandler());

        try
        {
            for (int i=0; i<bytes.length; i+=3)
            {
                int[] points=new int[] { i };
                StringBuilder message=new StringBuilder();

                message.append("iteration #").append(i + 1);

                // Sort the list
                Arrays.sort(points);

                Socket client=new Socket(HOST,port);
                OutputStream os=client.getOutputStream();

                writeFragments(bytes,points,message,os);

                // Read the response
                String response=readResponse(client);

                // Close the client
                client.close();

                // Check the response
                assertEquals("response for "+i+" "+message.toString(),RESPONSE2,response);
            }
        }
        finally
        {
            // Shut down
            server.stop();
        }
    }

    /**
     * After several iterations, I generated some known bad fragment points.
     * 
     * @throws Exception
     */
    public void testRequest2KnownBad_jetty() throws Exception
    {
        byte[] bytes=REQUEST2.getBytes();
        int[][] badPoints=new int[][]
        {
        { 70 }, // beginning here, drops last line of request
                { 71 }, // no response at all
                { 72 }, // again starts drops last line of request
                { 74 }, // again, no response at all
        };
        Server server=startServer(new EchoHandler());

        try
        {
            for (int i=0; i<badPoints.length; ++i)
            {
                Socket client=new Socket(HOST,port);
                OutputStream os=client.getOutputStream();
                StringBuilder message=new StringBuilder();

                message.append("iteration #").append(i + 1);
                writeFragments(bytes,badPoints[i],message,os);

                // Read the response
                String response=readResponse(client);

                // Close the client
                client.close();

                // Check the response
                // TODO - change to equals when code gets fixed
                assertNotSame("response for "+message.toString(),RESPONSE2,response);
            }
        }
        finally
        {
            // Shut down
            server.stop();
        }
    }

    /**
     * After several iterations, I generated some known bad fragment points.
     * 
     * @throws Exception
     */
    public void testFlush() throws Exception
    {
        Server server=startServer(new DataHandler());
        try
        {   
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
                            URL url=new URL("http://"+HOST+":"+port+"/?writes="+w+"&block="+b+ (e==0?"":("&encoding="+encoding[e]))+(c==0?"&chars=true":""));
                            InputStream in = (InputStream)url.getContent();
                            String response=IO.toString(in,e==0?null:encoding[e]);
                            
                            assertEquals(test,b*w,response.length());
                        }
                    }
                }
            }
        }
        finally
        {
            // Shut down
            server.stop();
            Thread.yield();
        }
    }

    /**
     * After several iterations, I generated some known bad fragment points.
     * 
     * @throws Exception
     */
    public void testReadWriteBlocking() throws Exception
    {
        Server server=startServer(new DataHandler());
        try
        {   
            long start=System.currentTimeMillis();
            Socket client=new Socket(HOST,port);
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "GET /data?writes=1024&block=256 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+port+"\r\n"+
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
                Thread.sleep(500);
                len=is.read(buf);
                if (len>0)
                    total+=len;
            }

            assertTrue(total>(1024*256));
            assertTrue(30000L>(System.currentTimeMillis()-start));
        }
        finally
        {
            // Shut down
            server.stop();
            Thread.yield();
        }

    }


    /**
     * After several iterations, I generated some known bad fragment points.
     * 
     * @throws Exception
     */
    public void testPipeline() throws Exception
    {
        Server server=startServer(new HelloWorldHandler());

        try
        {   
            //for (int pipeline=1;pipeline<32;pipeline++)
            for (int pipeline=1;pipeline<32;pipeline++)
            {   
                Socket client=new Socket(HOST,port);
                client.setSoTimeout(5000);
                OutputStream os=client.getOutputStream();

                String request="";

                for (int i=1;i<pipeline;i++)
                    request+=
                        "GET /data?writes=1&block=16&id="+i+" HTTP/1.1\r\n"+
                        "host: "+HOST+":"+port+"\r\n"+
                        "user-agent: testharness/1.0 (blah foo/bar)\r\n"+
                        "accept-encoding: nothing\r\n"+
                        "cookie: aaa=1234567890\r\n"+
                        "\r\n";
                
                request+=
                    "GET /data?writes=1&block=16 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+port+"\r\n"+
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
        }
        finally
        {
            // Shut down
            server.stop();
            Thread.yield();
        }

    }

    public void testStoppable() throws Exception
    {
        Server server=startServer(null);
        server.setThreadPool(new QueuedThreadPool());
        server.start();
        Thread.sleep(1000);
        server.stop();
    }
    

    /**
     */
    public void testRecycledWriters() throws Exception
    {
        Server server=startServer(new EchoHandler());
        
        try
        {   
            long start=System.currentTimeMillis();
            Socket client=new Socket(HOST,port);
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+port+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: 10\r\n"+
                    "\r\n").getBytes("iso-8859-1"));
            
            os.write((
                    "123456789\n"
            ).getBytes("utf-8"));

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+port+"\r\n"+
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
                    "host: "+HOST+":"+port+"\r\n"+
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
            // Shut down
            server.stop();
            Thread.yield();
        }
    }

    /**
     */
    public void testRecycledReaders() throws Exception
    {
        Server server=startServer(new EchoHandler());
        
        try
        {   
            long start=System.currentTimeMillis();
            Socket client=new Socket(HOST,port);
            OutputStream os=client.getOutputStream();
            InputStream is=client.getInputStream();

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+port+"\r\n"+
                    "content-type: text/plain; charset=utf-8\r\n"+
                    "content-length: 10\r\n"+
                    "\r\n").getBytes("iso-8859-1"));
            
            os.write((
                    "123456789\n"
            ).getBytes("utf-8"));

            os.write((
                    "POST /echo?charset=utf-8 HTTP/1.1\r\n"+
                    "host: "+HOST+":"+port+"\r\n"+
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
                    "host: "+HOST+":"+port+"\r\n"+
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
            // Shut down
            server.stop();
            Thread.yield();
        }
    }
    
    /**
     * Read entire response from the client. Close the output.
     * 
     * @param client
     *            Open client socket.
     * 
     * @return The response string.
     * 
     * @throws IOException
     */
    private static String readResponse(Socket client) throws IOException
    {
        BufferedReader br=null;

        try
        {
            br=new BufferedReader(new InputStreamReader(client.getInputStream()));

            StringBuilder sb=new StringBuilder();
            String line;

            while ((line=br.readLine())!=null)
            {
                sb.append(line);
                sb.append('\n');
            }

            return sb.toString();
        }
        finally
        {
            if (br!=null)
            {
                br.close();
            }
        }
    }

    protected HttpServerTestBase(Connector connector)
    {
        _connector=connector;
    }

    /**
     * Create the server.
     * 
     * @param handler
     * 
     * @return Newly created server, ready to start.
     * 
     * @throws Exception
     */
    protected Server startServer(Handler handler) throws Exception
    {
        Server server=new Server();

        _connector.setPort(0);
        server.setConnectors(new Connector[]
        { _connector });
        server.setHandler(handler);
        
        configServer(server);
        
        server.start();
        port=_connector.getLocalPort();
        return server;
    }
    
    protected void configServer(Server server)
    {
        
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

    // ~ Inner Classes
    // ----------------------------------------------------------
    private static class EchoHandler extends AbstractHandler
    {
        // ~ Methods
        // ------------------------------------------------------------
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            if (request.getContentType()!=null)
                response.setContentType(request.getContentType());
            if (request.getParameter("charset")!=null)
                response.setCharacterEncoding(request.getParameter("charset"));
            else if (request.getCharacterEncoding()!=null)
                response.setCharacterEncoding(request.getCharacterEncoding());
            
            PrintWriter writer=response.getWriter();
            BufferedReader reader=request.getReader();
            int count=0;
            String line;
            
            while ((line=reader.readLine())!=null)
            {
                writer.print(line);
                writer.print("\n");
                count+=line.length();
            }

            if (count==0)
                throw new IllegalStateException("no input recieved");
            
            // just to be difficult
            reader.close();
            writer.close();
            
            if (reader.read()>=0)
                throw new IllegalStateException("Not closed");
        }
    }

    // ----------------------------------------------------------
    private static class HelloWorldHandler extends AbstractHandler
    {
        // ------------------------------------------------------------
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().print("Hello world\r\n");
        }
    }

    // ----------------------------------------------------------
    private static class DataHandler extends AbstractHandler
    {
        // ------------------------------------------------------------
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            
            InputStream in = request.getInputStream();
            String input=IO.toString(in);
            
            String tmp = request.getParameter("writes");
            int writes=Integer.parseInt(tmp==null?"10":tmp);
            tmp = request.getParameter("block");
            int block=Integer.parseInt(tmp==null?"10":tmp);
            String encoding=request.getParameter("encoding");
            String chars=request.getParameter("chars");
            
            String chunk = (input+"\u0a870123456789A\u0a87CDEFGHIJKLMNOPQRSTUVWXYZ\u0250bcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
                .substring(0,block);
            response.setContentType("text/plain");
            if (encoding==null)
            {
                byte[] bytes=chunk.getBytes("ISO-8859-1");
                OutputStream out=response.getOutputStream();
                for (int i=0;i<writes;i++)
                    out.write(bytes);
            }
            else if ("true".equals(chars))
            {
                response.setCharacterEncoding(encoding);
                Writer out=response.getWriter();
                char[] c=chunk.toCharArray();
                for (int i=0;i<writes;i++)
                    out.write(c);
            }
            else 
            {
                response.setCharacterEncoding(encoding);
                Writer out=response.getWriter();
                for (int i=0;i<writes;i++)
                    out.write(chunk);
            }
            
        }
    }

}
