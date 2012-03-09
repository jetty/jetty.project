package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import junit.framework.Assert;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

public class ProxyServletTest
{
    private Server _server;
    private Connector _connector;
    private HttpClient _client;

    public void init(HttpServlet servlet) throws Exception
    {
        _server = new Server();

        _connector = new SelectChannelConnector();
        _server.addConnector(_connector);

        HandlerCollection handlers = new HandlerCollection();
        _server.setHandler(handlers);

        ServletContextHandler proxyCtx = new ServletContextHandler(handlers, "/proxy", ServletContextHandler.NO_SESSIONS);
        ServletHolder proxyServletHolder = new ServletHolder(new ProxyServlet()
        {
            @Override
            protected HttpURI proxyHttpURI(String scheme, String serverName, int serverPort, String uri) throws MalformedURLException
            {
                // Proxies any call to "/proxy" to "/"
                return new HttpURI(scheme + "://" + serverName + ":" + serverPort + uri.substring("/proxy".length()));
            }
        });
        proxyServletHolder.setInitParameter("timeout", String.valueOf(5 * 60 * 1000L));
        proxyCtx.addServlet(proxyServletHolder, "/*");

        ServletContextHandler appCtx = new ServletContextHandler(handlers, "/", ServletContextHandler.SESSIONS);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        handlers.addHandler(proxyCtx);
        handlers.addHandler(appCtx);

        _server.start();

        _client = new HttpClient();
        _client.start();
    }

    @After
    public void destroy() throws Exception
    {
        if (_client != null)
            _client.stop();

        if (_server != null)
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testXForwardedHostHeader() throws Exception
    {
        init(new HttpServlet()
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                PrintWriter writer = resp.getWriter();
                writer.write(req.getHeader("X-Forwarded-Host"));
                writer.flush();
            }
        });

        String url = "http://localhost:" + _connector.getLocalPort() + "/proxy/test";
        ContentExchange exchange = new ContentExchange();
        exchange.setURL(url);
        _client.send(exchange);
        exchange.waitForDone();
        assertThat("Response expected to contain content of X-Forwarded-Host Header from the request",exchange.getResponseContent(),equalTo("localhost:"
                + _connector.getLocalPort()));
    }

    @Test
    public void testBigDownloadWithSlowReader() throws Exception
    {
        // Create a 6 MiB file
        final File file = File.createTempFile("test_", null, MavenTestingUtils.getTargetTestingDir());
        file.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        Arrays.fill(buffer, (byte)'X');
        for (int i = 0; i < 6 * 1024; ++i)
            fos.write(buffer);
        fos.close();

        init(new HttpServlet()
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                FileInputStream fis = new FileInputStream(file);
                ServletOutputStream output = response.getOutputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = fis.read(buffer)) >= 0)
                    output.write(buffer, 0, read);
                fis.close();
            }
        });

        String url = "http://localhost:" + _connector.getLocalPort() + "/proxy/test";
        ContentExchange exchange = new ContentExchange(true)
        {
            @Override
            protected void onResponseContent(Buffer content) throws IOException
            {
                try
                {
                    // Slow down the reader
                    TimeUnit.MILLISECONDS.sleep(10);
                    super.onResponseContent(content);
                }
                catch (InterruptedException x)
                {
                    throw (IOException)new IOException().initCause(x);
                }
            }
        };
        exchange.setURL(url);
        long start = System.nanoTime();
        _client.send(exchange);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        long elapsed = System.nanoTime() - start;
        Assert.assertEquals(HttpStatus.OK_200, exchange.getResponseStatus());
        Assert.assertEquals(file.length(), exchange.getResponseContentBytes().length);
        long millis = TimeUnit.NANOSECONDS.toMillis(elapsed);
        long rate = file.length() / 1024 * 1000 / millis;
        System.out.printf("download rate = %d KiB/s%n", rate);
    }
}
