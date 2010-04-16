package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;

/**
 * @version $Revision$ $Date$
 */
public class ProxyHandlerConnectTest extends AbstractProxyHandlerTest
{
    @Override
    protected void configureServer(Server server)
    {
        server.setHandler(new ServerHandler());
    }

    public void testCONNECT() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
        }
        finally
        {
            socket.close();
        }
    }

    public void testCONNECTAndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    public void testCONNECTAndGETPipelined() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n" +
                "GET /echo" + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            // The pipelined request must have gone up to the server as is
            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    public void testCONNECTAndMultipleGETs() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            for (int i = 0; i < 10; ++i)
            {
                request = "" +
                        "GET /echo" + " HTTP/1.1\r\n" +
                        "Host: " + hostPort + "\r\n" +
                        "\r\n";
                output.write(request.getBytes("UTF-8"));
                output.flush();

                response = readResponse(input);
                System.err.println(response);
                assertEquals("200", response.getCode());
                assertEquals("GET /echo", response.getBody());
            }
        }
        finally
        {
            socket.close();
        }
    }

    public void testCONNECTAndGETServerStop() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());

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

    public void testCONNECTAndGETAndServerSideClose() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            request = "" +
                    "GET /close HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
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

    public void testCONNECTAndPOSTAndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            request = "" +
                    "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: 5\r\n" +
                    "\r\n" +
                    "HELLO";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
            assertEquals("POST /echo\r\nHELLO", response.getBody());

            request = "" +
                    "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
            assertEquals("GET /echo", response.getBody());
        }
        finally
        {
            socket.close();
        }
    }

    public void testCONNECTAndPOSTWithBigBody() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        try
        {
            OutputStream output = socket.getOutputStream();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes("UTF-8"));
            output.flush();

            // Expect 200 OK from the CONNECT request
            Response response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());

            StringBuilder body = new StringBuilder();
            String chunk = "0123456789ABCDEF";
            for (int i = 0; i < 1024; ++i)
                body.append(chunk);

            request = "" +
                    "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;
            output.write(request.getBytes("UTF-8"));
            output.flush();

            response = readResponse(input);
            System.err.println(response);
            assertEquals("200", response.getCode());
            assertEquals("POST /echo\r\n" + body, response.getBody());
        }
        finally
        {
            socket.close();
        }
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
                if (httpRequest.getQueryString() != null)
                    builder.append("?").append(httpRequest.getQueryString());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream input = httpRequest.getInputStream();
                int read = -1;
                while ((read = input.read()) >= 0)
                    baos.write(read);
                baos.close();

                System.err.println("server echoing:\r\n" + builder);
                ServletOutputStream output = httpResponse.getOutputStream();
                output.println(builder.toString());
                output.write(baos.toByteArray());
            }
            else if ("/close".equals(uri))
            {
                request.getConnection().getEndPoint().close();
                System.err.println("server closed");
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
