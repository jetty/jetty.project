package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @version $Revision$ $Date$
 */
public class ConnectHandlerSSLTest extends AbstractConnectHandlerTest
{
    @BeforeClass
    public static void init() throws Exception
    {
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        connector.setMaxIdleTime(3600000); // TODO remove

        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keyStorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");

        startServer(connector, new ServerHandler());
        startProxy();
    }

    @Test
    public void testGETRequest() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = "" +
                "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        Socket socket = newSocket();
        socket.setSoTimeout(3600000); // TODO remove
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

            // Be sure the buffered input does not have anything buffered
            assertFalse(input.ready());

            // Upgrade the socket to SSL
            SSLSocket sslSocket = wrapSocket(socket);
            try
            {
                output = sslSocket.getOutputStream();
                input = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));

                request =
                        "GET /echo HTTP/1.1\r\n" +
                        "Host: " + hostPort + "\r\n" +
                        "\r\n";
                output.write(request.getBytes("UTF-8"));
                output.flush();

                response = readResponse(input);
                assertEquals("200", response.getCode());
                assertEquals("GET /echo", response.getBody());
            }
            finally
            {
                sslSocket.close();
            }
        }
        finally
        {
            socket.close();
        }
    }

    @Test
    public void testPOSTRequests() throws Exception
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
            assertEquals("200", response.getCode());

            // Be sure the buffered input does not have anything buffered
            assertFalse(input.ready());

            // Upgrade the socket to SSL
            SSLSocket sslSocket = wrapSocket(socket);
            try
            {
                output = sslSocket.getOutputStream();
                input = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));

                for (int i = 0; i < 10; ++i)
                {
                    request = "" +
                            "POST /echo?param=" + i + " HTTP/1.1\r\n" +
                            "Host: " + hostPort + "\r\n" +
                            "Content-Length: 5\r\n" +
                            "\r\n" +
                            "HELLO";
                    output.write(request.getBytes("UTF-8"));
                    output.flush();

                    response = readResponse(input);
                    assertEquals("200", response.getCode());
                    assertEquals("POST /echo?param=" + i + "\r\nHELLO", response.getBody());
                }
            }
            finally
            {
                sslSocket.close();
            }
        }
        finally
        {
            socket.close();
        }
    }

    private SSLSocket wrapSocket(Socket socket) throws Exception
    {
        SSLContext sslContext = SSLContext.getInstance("SSLv3");
        sslContext.init(null, new TrustManager[]{new AlwaysTrustManager()}, new SecureRandom());
        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket)socketFactory.createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private class AlwaysTrustManager implements X509TrustManager
    {
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
        {
        }

        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
        {
        }

        public X509Certificate[] getAcceptedIssuers()
        {
            return new X509Certificate[]{};
        }
    }

    private static class ServerHandler extends AbstractHandler
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

                ServletOutputStream output = httpResponse.getOutputStream();
                output.println(builder.toString());
                output.write(baos.toByteArray());
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
