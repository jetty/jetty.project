package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Test;

/**
 * A class that attempts to simulate a client communicating with a slow server. It imposes a delay between each handle() call made to the server's handler.
 * 
 * The client sends a binary blob of data to the server, and this blob is then inspected to verify correct transfer.
 */
public class SluggishServerTest
{

    /** msec to wait between reads in the handler -- may need to adjust based on OS/HW/etc. to reproduce bug */
    private final static int READ_DELAY = 5;

    private final static String URL = "http://localhost:";

    /** Stream providing a binary message to send */
    private static class SluggishStream extends InputStream
    {
        private final byte[] request;
        private int pos;

        public SluggishStream(byte[] request)
        {
            this.request = request;
            this.pos = 0;
        }

        @Override
        public int read() throws IOException
        {
            if (pos < request.length)
            {
                int byteVal = request[pos++] & 0xFF;
                return byteVal;
            }
            else
            {
                return -1;
            }
        }

    }

    /** Sends a message containing random binary content to a SluggishHandler */
    private static class SluggishExchange extends HttpExchange
    {
        private byte[] request;

        public SluggishExchange(int port, int count)
        {
            request = new byte[count];
            for (int i=0;i<count;i++)
                request[i]=(byte)('A'+(i%26));
            setURL(URL+port);
            setRequestContentSource(new SluggishStream(request));
            setRequestContentType("application/octet-stream");
            setRequestHeader(HttpHeaders.TRANSFER_ENCODING,HttpHeaderValues.CHUNKED);
        }

        public byte[] getRequestBody()
        {
            return request;
        }

        @Override
        protected void onRequestComplete()
        {
            //System.err.println("REQUEST COMPLETE " + this);
        }

        @Override
        protected void onResponseComplete()
        {
            //System.err.println("RESPONSE COMPLETE " + this);
        }

        @Override
        protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException
        {
            //System.err.printf("<<< %s %d %s%n",version,status,reason);
            super.onResponseStatus(version,status,reason);
        }

        @Override
        protected void onResponseHeader(Buffer name, Buffer value) throws IOException
        {
            //System.err.printf("<<< %s: %s%n",name,value);
            super.onResponseHeader(name,value);
        }

        @Override
        protected void onResponseHeaderComplete() throws IOException
        {
            //System.err.printf("<<< --%n");
            super.onResponseHeaderComplete();
        }
        
    }

    /** Receives binary message from a SluggishExchange & stores it for validation */
    private static class SluggishHandler extends AbstractHandler
    {

        private final ArrayList<Byte> accumulatedRequest;

        public SluggishHandler(int requestSize)
        {
            accumulatedRequest = new ArrayList<Byte>(requestSize);
        }

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            accumulatedRequest.clear();
            ServletInputStream input = request.getInputStream();
            byte[] buffer = new byte[16384];
            int bytesAvailable;
            while ((bytesAvailable = input.read(buffer,0,buffer.length)) > 0)
            {
                //System.err.println("AVAILABLE FOR READ = " + bytesAvailable);
                for (int n = 0; n < bytesAvailable; ++n)
                {
                    accumulatedRequest.add(buffer[n]);
                }
                try
                {
                    Thread.sleep(READ_DELAY);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            //System.err.println("HANDLED");
        }

        public byte[] getAccumulatedRequest()
        {
            byte[] buffer = new byte[accumulatedRequest.size()];
            int pos = 0;
            for (Byte b : accumulatedRequest)
            {
                buffer[pos++] = b;
            }
            return buffer;
        }
    }

    private static boolean compareBuffers(byte[] sent, byte[] received)
    {
        if (sent.length != received.length)
        {
            System.err.format("Mismatch in sent/received lengths: sent=%d received=%d\n",sent.length,received.length);
            return false;
        }
        else
        {
            for (int n = 0; n < sent.length; ++n)
            {
                if (sent[n] != received[n])
                {
                    System.err.format("Mismatch at offset %d: request=%d response=%d\n",n,sent[n],received[n]);
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    public void test0() throws Exception
    {
        goSlow(20000,10);    
    }
    
    @Test
    public void test1() throws Exception
    {
        goSlow(200000,5);    
    }
    
    @Test
    public void test2() throws Exception
    {
        goSlow(2000000,2);    
    }
    
    void goSlow(int requestSize,int iterations) throws Exception
    {
        Server server = new Server();
        SocketConnector connector = new SocketConnector();
        server.addConnector(connector);
        SluggishHandler handler = new SluggishHandler(requestSize);
        server.setHandler(handler);
        server.start();
        int port = connector.getLocalPort();

        HttpClient client = new HttpClient();
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        client.setConnectTimeout(5000);
        client.setIdleTimeout(60000);
        client.start();

        try
        {
            for (int i = 0; i < iterations; ++i)
            {
                //System.err.format("-------------- ITERATION %d ------------------\n",i);
                SluggishExchange exchange = new SluggishExchange(port,requestSize);
                long startTime = System.currentTimeMillis();
                client.send(exchange);
                exchange.waitForDone();
                long endTime = System.currentTimeMillis();
                //System.err.println("EXCHANGE STATUS = " + exchange);
                //System.err.println("ELAPSED MSEC = " + (endTime - startTime));
                Assert.assertTrue(compareBuffers(exchange.getRequestBody(),handler.getAccumulatedRequest()));
            }
        }
        finally
        {
            server.stop();
            server.join();
        }
    }
}
