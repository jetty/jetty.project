package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SslBytesClientTest extends SslBytesTest
{
    private ExecutorService threadPool;
    private HttpClient client;
    private SimpleProxy proxy;
    private SSLServerSocket acceptor;

    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();

        client = new HttpClient();
        client.setMaxConnectionsPerAddress(1);
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        File keyStore = MavenTestingUtils.getTestResourceFile("keystore");
        SslContextFactory cf = client.getSslContextFactory();
        cf.setKeyStorePath(keyStore.getAbsolutePath());
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        client.start();

        SSLContext sslContext = cf.getSslContext();
        acceptor = (SSLServerSocket)sslContext.getServerSocketFactory().createServerSocket(0);

        int serverPort = acceptor.getLocalPort();

        proxy = new SimpleProxy(threadPool, "localhost", serverPort);
        proxy.start();
        logger.debug(":{} <==> :{}", proxy.getPort(), serverPort);
    }

    @After
    public void destroy() throws Exception
    {
        if (acceptor != null)
            acceptor.close();
        if (proxy != null)
            proxy.stop();
        if (client != null)
            client.stop();
        if (threadPool != null)
            threadPool.shutdownNow();
    }

    @Test
    public void testHandshake() throws Exception
    {
        ContentExchange exchange = new ContentExchange(true);
        exchange.setURL("https://localhost:" + proxy.getPort());
        String method = HttpMethods.GET;
        exchange.setMethod(method);
        client.send(exchange);
        Assert.assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));

        final SSLSocket server = (SSLSocket)acceptor.accept();
        server.setUseClientMode(false);

        Future<Object> handshake = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                server.startHandshake();
                return null;
            }
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToServer(record);

        // Client Done
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        // Read request
        BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.startsWith(method));
        while (line.length() > 0)
            line = reader.readLine();
        // Write response
        OutputStream output = server.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n").getBytes("UTF-8"));
        output.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        Assert.assertEquals(HttpStatus.OK_200, exchange.getResponseStatus());
    }
}
