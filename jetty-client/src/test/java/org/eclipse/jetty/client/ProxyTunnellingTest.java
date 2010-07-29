package org.eclipse.jetty.client;

import java.io.File;
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
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.ProxyHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.ProxyServlet;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ProxyTunnellingTest
{
    private Server server;
    private Connector serverConnector;
    private Server proxy;
    private Connector proxyConnector;

    private void startSSLServer(Handler handler) throws Exception
    {
        SslSelectChannelConnector connector = new SslSelectChannelConnector();
        String keyStorePath = System.getProperty("basedir");
        assertNotNull(keyStorePath);
        keyStorePath += File.separator + "src" + File.separator + "test" + File.separator + "resources" + File.separator + "keystore";
        connector.setKeystore(keyStorePath);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");
        startServer(connector, handler);
    }

    private void startServer(Connector connector, Handler handler) throws Exception
    {
        server = new Server();
        serverConnector = connector;
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    private void startProxy() throws Exception
    {
        proxy = new Server();
        proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);
        ProxyHandler proxyHandler = new ProxyHandler();
        proxy.setHandler(proxyHandler);
        HandlerCollection handlers = new HandlerCollection();
        proxyHandler.setHandler(handlers);
        ServletContextHandler context = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
        ServletHolder proxyServlet = new ServletHolder(ProxyServlet.class);
        context.addServlet(proxyServlet, "/*");
        proxy.start();
    }

    @After
    public void stop() throws Exception
    {
        stopProxy();
        stopServer();
    }

    private void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    private void stopProxy() throws Exception
    {
        proxy.stop();
        proxy.join();
    }


    @Test
    public void testNoSSL() throws Exception
    {
        startServer(new SelectChannelConnector(), new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyConnector.getLocalPort()));
        httpClient.start();

        try
        {
            ContentExchange exchange = new ContentExchange(true);
            String body = "BODY";
            exchange.setURL("http://localhost:" + serverConnector.getLocalPort() + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));
            exchange.setMethod(HttpMethods.GET);

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
    public void testOneMessageSSL() throws Exception
    {
        startSSLServer(new ServerHandler());
        startProxy();

        HttpClient httpClient = new HttpClient();
        httpClient.setProxy(new Address("localhost", proxyConnector.getLocalPort()));
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
        httpClient.setProxy(new Address("localhost", proxyConnector.getLocalPort()));
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
        int proxyPort = proxyConnector.getLocalPort();
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
        httpClient.setProxy(new Address("localhost", proxyConnector.getLocalPort()));
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
            exchange.setURL("https://localhost:" + serverPort + "/echo?body=" + URLEncoder.encode(body, "UTF-8"));

            httpClient.send(exchange);
            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
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
