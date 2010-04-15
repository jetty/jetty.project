package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

/**
 * @version $Revision$ $Date$
 */
public class ProxyHandlerConnectTest extends TestCase
{
    private Server server;
    private Connector serverConnector;
    private Server proxy;
    private Connector proxyConnector;

    @Override
    protected void setUp() throws Exception
    {
        server = new Server();
        serverConnector = new SelectChannelConnector();
        server.addConnector(serverConnector);
        server.setHandler(new ServerHandler());
        server.start();

        proxy = new Server();
        proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);
        proxy.setHandler(new ProxyHandler());
        proxy.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        proxy.stop();
        proxy.join();

        server.stop();
        server.join();
    }

    public void testHttpConnect() throws Exception
    {
        String request = "" +
                "CONNECT localhost:" + serverConnector.getLocalPort() + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        try
        {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            InputStream input = socket.getInputStream();
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);
        }
        finally
        {
            socket.close();
        }
    }

    public void testHttpConnectWithNormalRequest() throws Exception
    {
        String request = "" +
                "CONNECT localhost:" + serverConnector.getLocalPort() + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        try
        {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            InputStream input = socket.getInputStream();
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);

            String echoURI = "GET /echo";
            request = "" +
                    echoURI + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);
            assertEquals(echoURI, response.body);
        }
        finally
        {
            socket.close();
        }
    }

    public void testHttpConnectWithPipelinedRequest() throws Exception
    {
        String pipelinedMethodURI = "GET /echo";
        String request = "" +
                "CONNECT localhost:" + serverConnector.getLocalPort() + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                pipelinedMethodURI + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        try
        {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            InputStream input = socket.getInputStream();
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);

            // The pipelined request must have gone up to the server as is
            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);
            assertEquals(pipelinedMethodURI, response.body);
        }
        finally
        {
            socket.close();
        }
    }

    public void testHttpConnectWithNoRequestServerClose() throws Exception
    {
        String request = "" +
                "CONNECT localhost:" + serverConnector.getLocalPort() + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        try
        {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            InputStream input = socket.getInputStream();
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);

            // Idle server is shut down
            server.stop();
            server.join();

            int read = input.read();
            assertEquals(-1, read);
        }
        finally
        {
            socket.close();
        }
    }

    public void testHttpConnectWithRequestServerClose() throws Exception
    {
        String request = "" +
                "CONNECT localhost:" + serverConnector.getLocalPort() + " HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        try
        {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            InputStream input = socket.getInputStream();
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.code);

            request = "" +
                    "GET /close HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            int read = input.read();
            assertEquals(-1, read);
        }
        finally
        {
            socket.close();
        }
    }

    private Response readResponse(InputStream input) throws IOException
    {
        // Simplified parser for HTTP responses
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line = reader.readLine();
        if (line == null)
            throw new EOFException();
        Matcher responseLine = Pattern.compile("HTTP/1\\.1\\s+(\\d+)").matcher(line);
        assertTrue(responseLine.lookingAt());
        String code = responseLine.group(1);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;

            Matcher header = Pattern.compile("([^:]+):\\s*(.*)").matcher(line);
            assertTrue(header.lookingAt());
            String headerName = header.group(1);
            String headerValue = header.group(2);
            headers.put(headerName.toLowerCase(), headerValue.toLowerCase());
        }

        StringBuilder body = new StringBuilder();
        if (headers.containsKey("content-length"))
        {
            int length = Integer.parseInt(headers.get("content-length"));
            for (int i = 0; i < length; ++i)
                body.append((char)reader.read());
        }
        else if ("chunked".equals(headers.get("transfer-encoding")))
        {
            while ((line = reader.readLine()) != null)
            {
                if ("0".equals(line))
                {
                    reader.readLine();
                    break;
                }

                body.append(reader.readLine());
                reader.readLine();
            }
        }

        return new Response(code, headers, body.toString().trim());
    }

    public class TestServlet extends HttpServlet
    {
    }

    private class ServerHandler extends AbstractHandler
    {
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            if ("/echo".equals(uri))
            {
                StringBuilder builder = new StringBuilder();
                builder.append(httpRequest.getMethod()).append(" ").append(uri);
                System.err.println("server echoing:\r\n" + builder);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.println(builder.toString());
            }
            else if ("/close".equals(uri))
            {
                request.getConnection().getEndPoint().close();
                System.err.println("server closed");
            }
        }
    }

    private class Response
    {
        private final String code;
        private final Map<String, String> headers;
        private final String body;

        private Response(String code, Map<String, String> headers, String body)
        {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(code).append("\r\n");
            for (Map.Entry<String, String> entry : headers.entrySet())
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            builder.append("\r\n");
            builder.append(body);
            return builder.toString();
        }
    }
}
