package org.eclipse.jetty.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.After;
import org.junit.Test;

public class ProxyTunnellingTest
{
    private Server server;
    private Connector serverConnector;
    private Server proxy;
    private Connector proxyConnector;
    private int serverConnectTimeout = 1000;

    protected int proxyPort()
    {
        return proxyConnector.getLocalPort();
    }

    protected void startSSLServer(Handler handler) throws Exception
    {
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keyStorePath);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        startServer(connector, handler);
    }

    protected void startServer(Connector connector, Handler handler) throws Exception
    {
        server = new Server();
        serverConnector = connector;
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    protected void startProxy() throws Exception
    {
        proxy = new Server();
        proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);
        ConnectHandler connectHandler = new ConnectHandler();
        // Under Windows, it takes a while to detect that a connection
        // attempt fails, so use an explicit timeout
        connectHandler.setConnectTimeout(serverConnectTimeout);
        proxy.setHandler(connectHandler);
        proxy.start();
    }

    @After
    public void stop() throws Exception
    {
        stopProxy();
        stopServer();
    }

    protected void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    protected void stopProxy() throws Exception
    {
        proxy.stop();
        proxy.join();
    }

    @Test
    public void testOneMessageSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            ContentExchange exchange = new ContentExchange(true);
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            String content = exchange.getResponseContent();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testTwoMessagesSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            ContentExchange exchange = new ContentExchange(true);
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            String content = exchange.getResponseContent();
            assertEquals(body, content);

            exchange = new ContentExchange(true);
            exchange.setMethod(HttpMethods.POST);
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo");
            exchange.setRequestHeader(HttpHeaders.CONTENT_TYPE, MimeTypes.FORM_ENCODED);
            content = "body=" + body;
            exchange.setRequestHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length()));
            exchange.setRequestContent(new ByteArrayBuffer(content, "UTF-8"));

            httpClient.send(exchange);
            assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
            content = exchange.getResponseContent();
            assertEquals(body, content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testProxyDown() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();
        int proxyPort = proxyPort();
        stopProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort));
        httpClient.start();

        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            ContentExchange exchange = new ContentExchange(true)
            {
                @Override
                protected void onConnectionFailed(Throwable x)
                {
                    latch.countDown();
                }
            };
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testServerDown() throws Exception
    {
        startSSLServer(new ServerHandler());
        int serverPort = serverConnector.getLocalPort();
        stopServer();
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyPort()));
        httpClient.start();

        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            ContentExchange exchange = new ContentExchange(true)
            {
                @Override
                protected void onException(Throwable x)
                {
                    latch.countDown();
                }

            };
            exchange.setMethod(HttpMethods.GET);
            String body = "BODY";
            exchange.setURL("https://localhost:" + serverPort + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertTrue(latch.await(serverConnectTimeout * 2, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
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
                String body = httpRequest.getParameter("body");
                ServletOutputStream output = httpResponse.getOutputStream();
                output.print(body);
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
