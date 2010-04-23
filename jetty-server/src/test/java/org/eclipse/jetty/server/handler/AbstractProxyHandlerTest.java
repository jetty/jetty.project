package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.TestCase;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractProxyHandlerTest extends TestCase
{
    protected Server server;
    protected Connector serverConnector;
    protected Server proxy;
    protected Connector proxyConnector;

    @Override
    protected void setUp() throws Exception
    {
        server = new Server();
        serverConnector = newServerConnector();
        server.addConnector(serverConnector);
        configureServer(server);
        server.start();

        proxy = new Server();
        proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);
        configureProxy(proxy);
        proxy.start();
    }

    protected SelectChannelConnector newServerConnector()
    {
        return new SelectChannelConnector();
    }

    protected void configureServer(Server server)
    {
    }

    protected void configureProxy(Server proxy)
    {
        proxy.setHandler(new ProxyHandler());
    }

    @Override
    protected void tearDown() throws Exception
    {
        proxy.stop();
        proxy.join();

        server.stop();
        server.join();
    }

    protected Response readResponse(BufferedReader reader) throws IOException
    {
        // Simplified parser for HTTP responses
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
            {
                char c = (char)reader.read();
                body.append(c);
            }
        }
        else if ("chunked".equals(headers.get("transfer-encoding")))
        {
            while ((line = reader.readLine()) != null)
            {
                if ("0".equals(line))
                {
                    line = reader.readLine();
                    assertEquals("", line);
                    break;
                }

                int length = Integer.parseInt(line, 16);
                for (int i = 0; i < length; ++i)
                {
                    char c = (char)reader.read();
                    body.append(c);
                }
                line = reader.readLine();
                assertEquals("", line);
            }
        }

        return new Response(code, headers, body.toString().trim());
    }

    protected Socket newSocket() throws IOException
    {
        Socket socket = new Socket("localhost", proxyConnector.getLocalPort());
        socket.setSoTimeout(500000);
        return socket;
    }

    protected class Response
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

        public String getCode()
        {
            return code;
        }

        public Map<String, String> getHeaders()
        {
            return headers;
        }

        public String getBody()
        {
            return body;
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
