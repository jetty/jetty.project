package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

public class ProxyServletTest
{
    private Server server;
    private Connector connector;
    private HttpClient client;

    public void init(HttpServlet servlet) throws Exception
    {
        server = new Server();

        connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

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

        server.start();

        client = new HttpClient();
        client.start();
    }

    @After
    public void destroy() throws Exception
    {
        if (client != null)
            client.stop();

        if (server != null)
        {
            server.stop();
            server.join();
        }
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

        String url = "http://localhost:" + connector.getLocalPort() + "/proxy" + "/test";
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
        client.send(exchange);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        long elapsed = System.nanoTime() - start;
        Assert.assertEquals(HttpStatus.OK_200, exchange.getResponseStatus());
        Assert.assertEquals(file.length(), exchange.getResponseContentBytes().length);
        long millis = TimeUnit.NANOSECONDS.toMillis(elapsed);
        long rate = file.length() / 1024 * 1000 / millis;
        System.out.printf("download rate = %d KiB/s%n", rate);
    }
}
