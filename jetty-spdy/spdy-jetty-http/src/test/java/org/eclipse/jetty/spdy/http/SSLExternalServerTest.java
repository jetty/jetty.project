package org.eclipse.jetty.spdy.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.spdy.SPDYClient;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class SSLExternalServerTest extends AbstractHTTPSPDYTest
{
    @Override
    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        // Force TLSv1
        sslContextFactory.setIncludeProtocols("TLSv1");
        return new SPDYClient.Factory(threadPool, sslContextFactory);
    }

    @Test
    public void testExternalServer() throws Exception
    {
        String host = "encrypted.google.com";
        int port = 443;
        InetSocketAddress address = new InetSocketAddress(host, port);

        try
        {
            // Test whether there is connectivity to avoid fail the test when offline
            Socket socket = new Socket();
            socket.connect(address, 5000);
            socket.close();
        }
        catch (IOException x)
        {
            Assume.assumeNoException(x);
        }

        final short version = SPDY.V2;
        Session session = startClient(version, address, null);
        Headers headers = new Headers();
        headers.put(HTTPSPDYHeader.SCHEME.name(version), "https");
        headers.put(HTTPSPDYHeader.HOST.name(version), host + ":" + port);
        headers.put(HTTPSPDYHeader.METHOD.name(version), "GET");
        headers.put(HTTPSPDYHeader.URI.name(version), "/");
        headers.put(HTTPSPDYHeader.VERSION.name(version), "HTTP/1.1");
        final CountDownLatch latch = new CountDownLatch(1);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Headers headers = replyInfo.getHeaders();
                Headers.Header versionHeader = headers.get(HTTPSPDYHeader.STATUS.name(version));
                if (versionHeader != null)
                {
                    Matcher matcher = Pattern.compile("(\\d{3}).*").matcher(versionHeader.value());
                    if (matcher.matches() && Integer.parseInt(matcher.group(1)) < 400)
                        latch.countDown();
                }
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
