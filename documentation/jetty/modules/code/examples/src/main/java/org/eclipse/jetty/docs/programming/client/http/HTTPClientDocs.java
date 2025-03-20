//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.client.http;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.AuthenticationStore;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.DigestAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.InputStreamRequestContent;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.PathRequestContent;
import org.eclipse.jetty.client.PathResponseListener;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.RoundRobinConnectionPool;
import org.eclipse.jetty.client.Socks5;
import org.eclipse.jetty.client.Socks5Proxy;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.MemoryConnector;
import org.eclipse.jetty.server.MemoryTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

@SuppressWarnings("unused")
public class HTTPClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Instantiate HttpClient.
        HttpClient httpClient = new HttpClient();

        // Configure HttpClient, for example:
        httpClient.setFollowRedirects(false);

        // Start HttpClient.
        httpClient.start();
        // end::start[]
    }

    public void stop() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        // tag::stop[]
        // Stop HttpClient.
        httpClient.stop();
        // end::stop[]
    }

    public void stopFromOtherThread() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        // tag::stopFromOtherThread[]
        // Stop HttpClient from a new thread.
        // Use LifeCycle.stop(...) to rethrow checked exceptions as unchecked.
        new Thread(() -> LifeCycle.stop(httpClient)).start();
        // end::stopFromOtherThread[]
    }

    public void tlsExplicit() throws Exception
    {
        // tag::tlsExplicit[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
        httpClient.start();
        // end::tlsExplicit[]
    }

    public void tlsNoValidation()
    {
        // tag::tlsNoValidation[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        // Disable the validation of the server host name at the TLS level.
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        // end::tlsNoValidation[]
    }

    public void tlsAppValidation()
    {
        // tag::tlsAppValidation[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        // Only allow to connect to subdomains of domain.com.
        sslContextFactory.setHostnameVerifier((hostName, session) -> hostName.endsWith(".domain.com"));
        // end::tlsAppValidation[]
    }

    public void sslHandshakeListener()
    {
        // tag::sslHandshakeListener[]
        // Create a SslHandshakeListener.
        SslHandshakeListener listener = new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event) throws SSLException
            {
                SSLEngine sslEngine = event.getSSLEngine();
                System.getLogger("tls").log(INFO, "TLS handshake successful to %s", sslEngine.getPeerHost());
            }

            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                SSLEngine sslEngine = event.getSSLEngine();
                System.getLogger("tls").log(ERROR, "TLS handshake failure to %s", sslEngine.getPeerHost(), failure);
            }
        };

        HttpClient httpClient = new HttpClient();

        // Add the SslHandshakeListener as bean to HttpClient.
        // The listener will be notified of TLS handshakes success and failure.
        httpClient.addBean(listener);
        // end::sslHandshakeListener[]
    }

    public void simpleBlockingGet() throws Exception
    {
        // tag::simpleBlockingGet[]
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // Perform a simple GET and wait for the response.
        ContentResponse response = httpClient.GET("http://domain.com/path?query");
        // end::simpleBlockingGet[]
    }

    public void headFluent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::headFluent[]
        ContentResponse response = httpClient.newRequest("http://domain.com/path?query")
            .method(HttpMethod.HEAD)
            .agent("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:17.0) Gecko/20100101 Firefox/17.0")
            .send();
        // end::headFluent[]
    }

    public void headNonFluent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::headNonFluent[]
        Request request = httpClient.newRequest("http://domain.com/path?query");
        request.method(HttpMethod.HEAD);
        request.agent("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:17.0) Gecko/20100101 Firefox/17.0");
        ContentResponse response = request.send();
        // end::headNonFluent[]
    }

    public void postFluent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::postFluent[]
        ContentResponse response = httpClient.POST("http://domain.com/entity/1")
            .param("p", "value")
            .send();
        // end::postFluent[]
    }

    public void fileFluent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::fileFluent[]
        ContentResponse response = httpClient.POST("http://domain.com/upload")
            .file(Paths.get("file_to_upload.txt"), "text/plain")
            .send();
        // end::fileFluent[]
    }

    public void totalTimeout() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::totalTimeout[]
        ContentResponse response = httpClient.newRequest("http://domain.com/path?query")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        // end::totalTimeout[]
    }

    public void simpleNonBlocking() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::simpleNonBlocking[]
        httpClient.newRequest("http://domain.com/path")
            .send(result ->
            {
                // Your logic here
            });
        // end::simpleNonBlocking[]
    }

    public void nonBlockingTotalTimeout() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::nonBlockingTotalTimeout[]
        httpClient.newRequest("http://domain.com/path")
            .timeout(3, TimeUnit.SECONDS)
            .send(result ->
            {
                /* Your logic here */
            });
        // end::nonBlockingTotalTimeout[]
    }

    // @checkstyle-disable-check : LeftCurly
    public void listeners() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::listeners[]
        httpClient.newRequest("http://domain.com/path")
            // Add request hooks.
            .onRequestQueued(request -> { /* ... */ })
            .onRequestBegin(request -> { /* ... */ })
            .onRequestHeaders(request -> { /* ... */ })
            .onRequestCommit(request -> { /* ... */ })
            .onRequestContent((request, content) -> { /* ... */ })
            .onRequestFailure((request, failure) -> { /* ... */ })
            .onRequestSuccess(request -> { /* ... */ })
            // Add response hooks.
            .onResponseBegin(response -> { /* ... */ })
            .onResponseHeader((response, field) -> true)
            .onResponseHeaders(response -> { /* ... */ })
            .onResponseContentAsync((response, chunk, demander) -> demander.run())
            .onResponseFailure((response, failure) -> { /* ... */ })
            .onResponseSuccess(response -> { /* ... */ })
            // Result hook.
            .send(result -> { /* ... */ });
        // end::listeners[]
    }

    public void pathRequestContent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::pathRequestContent[]
        ContentResponse response = httpClient.POST("http://domain.com/upload")
            .body(new PathRequestContent("text/plain", Paths.get("file_to_upload.txt")))
            .send();
        // end::pathRequestContent[]
    }

    public void inputStreamRequestContent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::inputStreamRequestContent[]
        ContentResponse response = httpClient.POST("http://domain.com/upload")
            .body(new InputStreamRequestContent("text/plain", new FileInputStream("file_to_upload.txt")))
            .send();
        // end::inputStreamRequestContent[]
    }

    public void bytesStringRequestContent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        byte[] bytes = new byte[1024];
        String string = new String(bytes);
        // tag::bytesStringRequestContent[]
        ContentResponse bytesResponse = httpClient.POST("http://domain.com/upload")
            .body(new BytesRequestContent("text/plain", bytes))
            .send();

        ContentResponse stringResponse = httpClient.POST("http://domain.com/upload")
            .body(new StringRequestContent("text/plain", string))
            .send();
        // end::bytesStringRequestContent[]
    }

    public void asyncRequestContent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::asyncRequestContent[]
        AsyncRequestContent content = new AsyncRequestContent();
        httpClient.POST("http://domain.com/upload")
            .body(content)
            .send(result ->
            {
                // Your logic here
            });

        // Content not available yet here.

        // An event happens in some other class, in some other thread.
        class ContentPublisher
        {
            void publish(byte[] bytes, boolean lastContent)
            {
                // Wrap the bytes into a new ByteBuffer.
                ByteBuffer buffer = ByteBuffer.wrap(bytes);

                // Write the content.
                content.write(buffer, Callback.NOOP);

                // Close AsyncRequestContent when all the content is arrived.
                if (lastContent)
                    content.close();
            }
        }
        // end::asyncRequestContent[]
    }

    public void outputStreamRequestContent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::outputStreamRequestContent[]
        OutputStreamRequestContent content = new OutputStreamRequestContent();

        // Use try-with-resources to close the OutputStream when all content is written.
        try (OutputStream output = content.getOutputStream())
        {
            httpClient.POST("http://localhost:8080/")
                .body(content)
                .send(result ->
                {
                    // Your logic here
                });

            // Content not available yet here.

            // Content is now available.
            byte[] bytes = new byte[]{'h', 'e', 'l', 'l', 'o'};
            output.write(bytes);
        }
        // End of try-with-resource, output.close() called automatically to signal end of content.
        // end::outputStreamRequestContent[]
    }

    public void futureResponseListener() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::completableResponseListener[]
        Request request = httpClient.newRequest("http://domain.com/path");

        // Limit response content buffer to 512 KiB.
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(request, 512 * 1024)
            .send();

        // You can attach actions to the CompletableFuture,
        // to be performed when the request+response completes.

        // Wait at most 5 seconds for request+response to complete.
        ContentResponse response = completable.get(5, TimeUnit.SECONDS);
        // end::completableResponseListener[]
    }

    public void bufferingResponseListener() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::bufferingResponseListener[]
        httpClient.newRequest("http://domain.com/path")
            // Buffer response content up to 8 MiB
            .send(new BufferingResponseListener(8 * 1024 * 1024)
            {
                @Override
                public void onComplete(Result result)
                {
                    if (!result.isFailed())
                    {
                        byte[] responseContent = getContent();
                        // Your logic here
                    }
                }
            });
        // end::bufferingResponseListener[]
    }

    public void inputStreamResponseListener() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::inputStreamResponseListener[]
        // Use try-with-resources to make sure that resources
        // are released even if the InputStream is not used.
        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            httpClient.newRequest("http://domain.com/path")
                .send(listener);

            // Wait for the response headers to arrive.
            Response response = listener.get(5, TimeUnit.SECONDS);

            // Look at the response before streaming the content.
            if (response.getStatus() == HttpStatus.OK_200)
            {
                // Use try-with-resources to close input stream.
                try (InputStream responseContent = listener.getInputStream())
                {
                    // Your logic here
                }
            }
            else
            {
                response.abort(new IOException("Unexpected HTTP response"));
            }
        }
        // end::inputStreamResponseListener[]
    }

    public void pathResponseListener() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::pathResponseListener[]
        Path savePath = Path.of("/path/to/save/file.bin");

        // Typical usage as a response listener.
        PathResponseListener listener = new PathResponseListener(savePath, true);
        httpClient.newRequest("http://domain.com/path")
            .send(listener);
        // Wait for the response content to be saved.
        var result = listener.get(5, TimeUnit.SECONDS);

        // Alternative usage with CompletableFuture.
        var completable = PathResponseListener.write(httpClient.newRequest("http://domain.com/path"), savePath, true);
        completable.whenComplete((pathResponse, failure) ->
        {
            // Your logic here.
        });
        // end::pathResponseListener[]
    }

    public void forwardContent() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        String host1 = "localhost";
        String host2 = "localhost";
        int port1 = 8080;
        int port2 = 8080;
        // tag::forwardContent[]
        // Prepare a request to server1, the source.
        Request request1 = httpClient.newRequest(host1, port1)
            .path("/source");

        // Prepare a request to server2, the sink.
        AsyncRequestContent content2 = new AsyncRequestContent();
        Request request2 = httpClient.newRequest(host2, port2)
            .path("/sink")
            .body(content2);

        request1.onResponseContentSource(new Response.ContentSourceListener()
        {
            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                // Only execute this method the very first time
                // to initialize the request to server2.

                request2.onRequestCommit(request ->
                {
                    // Only when the request to server2 has been sent,
                    // then demand response content from server1.
                    contentSource.demand(() -> forwardContent(response, contentSource));
                });

                // Send the request to server2.
                request2.send(result -> System.getLogger("forwarder").log(INFO, "Forwarding to server2 complete"));
            }

            private void forwardContent(Response response, Content.Source contentSource)
            {
                // Read one chunk of content.
                Content.Chunk chunk = contentSource.read();
                if (chunk == null)
                {
                    // The read chunk is null, demand to be called back
                    // when the next one is ready to be read.
                    contentSource.demand(() -> forwardContent(response, contentSource));
                    // Once a demand is in progress, the content source must not be read
                    // nor demanded again until the demand callback is invoked.
                    return;
                }
                // Check if the chunk is last and empty, in which case the
                // read/demand loop is done. Demanding again when the terminal
                // chunk has been read will invoke the demand callback with
                // the same terminal chunk, so this check must be present to
                // avoid infinitely demanding and reading the terminal chunk.
                if (chunk.isLast() && !chunk.hasRemaining())
                {
                    chunk.release();
                    return;
                }

                // When a response chunk is received from server1, forward it to server2.
                content2.write(chunk.getByteBuffer(), Callback.from(() ->
                {
                    // When the request chunk is successfully sent to server2,
                    // release the chunk to recycle the buffer.
                    chunk.release();
                    // Then demand more response content from server1.
                    contentSource.demand(() -> forwardContent(response, contentSource));
                }, x ->
                {
                    chunk.release();
                    response.abort(x);
                }));
            }
        });

        // When the response content from server1 is complete,
        // complete also the request content to server2.
        request1.onResponseSuccess(response -> content2.close());

        // Send the request to server1.
        request1.send(result -> System.getLogger("forwarder").log(INFO, "Sourcing from server1 complete"));
        // end::forwardContent[]
    }

    public void getCookies() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::getCookies[]
        HttpCookieStore cookieStore = httpClient.getHttpCookieStore();
        List<HttpCookie> cookies = cookieStore.match(URI.create("http://domain.com/path"));
        // end::getCookies[]
    }

    public void setCookie() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::setCookie[]
        HttpCookieStore cookieStore = httpClient.getHttpCookieStore();
        HttpCookie cookie = HttpCookie.build("foo", "bar")
            .domain("domain.com")
            .path("/")
            .maxAge(TimeUnit.DAYS.toSeconds(1))
            .build();
        cookieStore.add(URI.create("http://domain.com"), cookie);
        // end::setCookie[]
    }

    public void requestCookie() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::requestCookie[]
        ContentResponse response = httpClient.newRequest("http://domain.com/path")
            .cookie(HttpCookie.from("foo", "bar"))
            .send();
        // end::requestCookie[]
    }

    public void removeCookie() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::removeCookie[]
        HttpCookieStore cookieStore = httpClient.getHttpCookieStore();
        URI uri = URI.create("http://domain.com");
        List<HttpCookie> cookies = cookieStore.match(uri);
        for (HttpCookie cookie : cookies)
        {
            cookieStore.remove(uri, cookie);
        }
        // end::removeCookie[]
    }

    public void emptyCookieStore() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::emptyCookieStore[]
        httpClient.setHttpCookieStore(new HttpCookieStore.Empty());
        // end::emptyCookieStore[]
    }

    public void filteringCookieStore() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::filteringCookieStore[]
        class GoogleOnlyCookieStore extends HttpCookieStore.Default
        {
            @Override
            public boolean add(URI uri, HttpCookie cookie)
            {
                if (uri.getHost().endsWith("google.com"))
                    return super.add(uri, cookie);
                return false;
            }
        }

        httpClient.setHttpCookieStore(new GoogleOnlyCookieStore());
        // end::filteringCookieStore[]
    }

    public void addAuthentication() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::addAuthentication[]
        // Add authentication credentials.
        AuthenticationStore auth = httpClient.getAuthenticationStore();

        URI uri1 = new URI("http://mydomain.com/secure");
        auth.addAuthentication(new BasicAuthentication(uri1, "MyRealm", "userName1", "password1"));

        URI uri2 = new URI("http://otherdomain.com/admin");
        auth.addAuthentication(new BasicAuthentication(uri1, "AdminRealm", "admin", "password"));
        // end::addAuthentication[]
    }

    public void clearResults() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::clearResults[]
        httpClient.getAuthenticationStore().clearAuthenticationResults();
        // end::clearResults[]
    }

    public void preemptedResult() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::preemptedResult[]
        AuthenticationStore auth = httpClient.getAuthenticationStore();
        URI uri = URI.create("http://domain.com/secure");
        auth.addAuthenticationResult(new BasicAuthentication.BasicResult(uri, "username", "password"));
        // end::preemptedResult[]
    }

    public void requestPreemptedResult() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::requestPreemptedResult[]
        URI uri = URI.create("http://domain.com/secure");
        Authentication.Result authn = new BasicAuthentication.BasicResult(uri, "username", "password");
        Request request = httpClient.newRequest(uri);
        authn.apply(request);
        request.send();
        // end::requestPreemptedResult[]
    }

    public void proxy() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::proxy[]
        HttpProxy proxy = new HttpProxy("proxyHost", 8888);

        // Do not proxy requests for localhost:8080.
        proxy.getExcludedAddresses().add("localhost:8080");

        // Add the new proxy to the list of proxies already registered.
        ProxyConfiguration proxyConfig = httpClient.getProxyConfiguration();
        proxyConfig.addProxy(proxy);

        ContentResponse response = httpClient.GET("http://domain.com/path");
        // end::proxy[]
    }

    public void proxySocks5() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::proxySocks5[]
        Socks5Proxy proxy = new Socks5Proxy("proxyHost", 8888);
        String socks5User = "jetty";
        String socks5Pass = "secret";
        var socks5AuthenticationFactory = new Socks5.UsernamePasswordAuthenticationFactory(socks5User, socks5Pass);
        // Add the authentication method to the proxy.
        proxy.putAuthenticationFactory(socks5AuthenticationFactory);

        // Do not proxy requests for localhost:8080.
        proxy.getExcludedAddresses().add("localhost:8080");

        // Add the new proxy to the list of proxies already registered.
        ProxyConfiguration proxyConfig = httpClient.getProxyConfiguration();
        proxyConfig.addProxy(proxy);

        ContentResponse response = httpClient.GET("http://domain.com/path");
        // end::proxySocks5[]
    }

    public void proxyAuthentication() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::proxyAuthentication[]
        AuthenticationStore auth = httpClient.getAuthenticationStore();

        // Proxy credentials.
        URI proxyURI = new URI("http://proxy.net:8080");
        auth.addAuthentication(new BasicAuthentication(proxyURI, "ProxyRealm", "proxyUser", "proxyPass"));

        // Server credentials.
        URI serverURI = new URI("http://domain.com/secure");
        auth.addAuthentication(new DigestAuthentication(serverURI, "ServerRealm", "serverUser", "serverPass"));

        // Proxy configuration.
        ProxyConfiguration proxyConfig = httpClient.getProxyConfiguration();
        HttpProxy proxy = new HttpProxy("proxy.net", 8080);
        proxyConfig.addProxy(proxy);

        ContentResponse response = httpClient.newRequest(serverURI).send();
        // end::proxyAuthentication[]
    }

    public void defaultTransport() throws Exception
    {
        // tag::defaultTransport[]
        // No transport specified, using default.
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        // end::defaultTransport[]
    }

    public void http11Transport() throws Exception
    {
        // tag::http11Transport[]
        // Configure HTTP/1.1 transport.
        HttpClientTransportOverHTTP transport = new HttpClientTransportOverHTTP();
        transport.setHeaderCacheSize(16384);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::http11Transport[]
    }

    public void http2Transport() throws Exception
    {
        // tag::http2Transport[]
        // The HTTP2Client powers the HTTP/2 transport.
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.setInitialSessionRecvWindow(64 * 1024 * 1024);

        // Create and configure the HTTP/2 transport.
        HttpClientTransportOverHTTP2 transport = new HttpClientTransportOverHTTP2(http2Client);
        transport.setUseALPN(true);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::http2Transport[]
    }

    public void http3Transport() throws Exception
    {
        // tag::http3Transport[]
        // HTTP/3 requires secure communication.
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        // The HTTP3Client powers the HTTP/3 transport.
        ClientQuicConfiguration clientQuicConfig = new ClientQuicConfiguration(sslContextFactory, null);
        HTTP3Client http3Client = new HTTP3Client(clientQuicConfig);
        http3Client.getQuicConfiguration().setSessionRecvWindow(64 * 1024 * 1024);

        // Create and configure the HTTP/3 transport.
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(http3Client);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::http3Transport[]
    }

    public void fcgiTransport() throws Exception
    {
        // tag::fcgiTransport[]
        String scriptRoot = "/var/www/wordpress";
        HttpClientTransportOverFCGI transport = new HttpClientTransportOverFCGI(scriptRoot);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::fcgiTransport[]
    }

    public void dynamicDefault() throws Exception
    {
        // tag::dynamicDefault[]
        // Dynamic transport speaks HTTP/1.1 by default.
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic();

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::dynamicDefault[]
    }

    public void dynamicOneProtocol()
    {
        // tag::dynamicOneProtocol[]
        ClientConnector connector = new ClientConnector();

        // Equivalent to HttpClientTransportOverHTTP.
        HttpClientTransportDynamic http11Transport = new HttpClientTransportDynamic(connector, HttpClientConnectionFactory.HTTP11);

        // Equivalent to HttpClientTransportOverHTTP2.
        HTTP2Client http2Client = new HTTP2Client(connector);
        HttpClientTransportDynamic http2Transport = new HttpClientTransportDynamic(connector, new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client));
        // end::dynamicOneProtocol[]
    }

    public void dynamicH1H2H3() throws Exception
    {
        // tag::dynamicH1H2H3[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);

        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;

        HTTP2Client http2Client = new HTTP2Client(connector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslContextFactory, null);
        HTTP3Client http3Client = new HTTP3Client(quicConfiguration, connector);
        ClientConnectionFactoryOverHTTP3.HTTP3 http3 = new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client);

        // The order of the protocols indicates the client's preference.
        // The first is the most preferred, the last is the least preferred, but
        // the protocol version to use can be explicitly specified in the request.
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(connector, http1, http2, http3);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::dynamicH1H2H3[]
    }

    public void dynamicExplicitVersion() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);

        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;

        HTTP2Client http2Client = new HTTP2Client(connector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslContextFactory, null);
        HTTP3Client http3Client = new HTTP3Client(quicConfiguration, connector);
        ClientConnectionFactoryOverHTTP3.HTTP3 http3 = new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client);
        // tag::dynamicExplicitVersion[]
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(connector, http1, http2, http3);
        HttpClient client = new HttpClient(transport);
        client.start();

        // The server supports HTTP/1.1, HTTP/2 and HTTP/3.

        ContentResponse http1Response = client.newRequest("https://host/")
            // Specify the version explicitly.
            .version(HttpVersion.HTTP_1_1)
            .send();

        ContentResponse http2Response = client.newRequest("https://host/")
            // Specify the version explicitly.
            .version(HttpVersion.HTTP_2)
            .send();

        ContentResponse http3Response = client.newRequest("https://host/")
            // Specify the version explicitly.
            .version(HttpVersion.HTTP_3)
            .send();

        // Make a clear-text upgrade request from HTTP/1.1 to HTTP/2.
        // The request will start as HTTP/1.1, but the response will be HTTP/2.
        ContentResponse upgradedResponse = client.newRequest("https://host/")
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .send();
        // end::dynamicExplicitVersion[]
    }

    public void dynamicPreferH3() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);

        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;

        HTTP2Client http2Client = new HTTP2Client(connector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslContextFactory, null);
        HTTP3Client http3Client = new HTTP3Client(quicConfiguration, connector);
        ClientConnectionFactoryOverHTTP3.HTTP3 http3 = new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client);
        // tag::dynamicPreferH3[]
        // Client prefers HTTP/3.
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(connector, http3, http2, http1);
        HttpClient client = new HttpClient(transport);
        client.start();

        // No explicit HTTP version specified.
        // Either HTTP/3 succeeds, or communication failure.
        ContentResponse httpResponse = client.newRequest("https://host/")
            .send();
        // end::dynamicPreferH3[]
    }

    public void dynamicPreferH2() throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);

        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;

        HTTP2Client http2Client = new HTTP2Client(connector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslContextFactory, null);
        HTTP3Client http3Client = new HTTP3Client(quicConfiguration, connector);
        ClientConnectionFactoryOverHTTP3.HTTP3 http3 = new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client);
        // tag::dynamicPreferH2[]
        // Client prefers HTTP/2.
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(connector, http2, http1, http3);
        HttpClient client = new HttpClient(transport);
        client.start();

        // No explicit HTTP version specified.
        // Either HTTP/1.1 or HTTP/2 will be negotiated via ALPN.
        // HTTP/3 only possible by specifying the version explicitly.
        ContentResponse httpResponse = client.newRequest("https://host/")
            .send();
        // end::dynamicPreferH2[]
    }

    public void getConnectionPool() throws Exception
    {
        // tag::getConnectionPool[]
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        ConnectionPool connectionPool = httpClient.getDestinations().stream()
            // Find the destination by filtering on the Origin.
            .filter(destination -> destination.getOrigin().getAddress().getHost().equals("domain.com"))
            .findAny()
            // Get the ConnectionPool.
            .map(Destination::getConnectionPool)
            .orElse(null);
        // end::getConnectionPool[]
    }

    public void setConnectionPool() throws Exception
    {
        // tag::setConnectionPool[]
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // The max number of connections in the pool.
        int maxConnectionsPerDestination = httpClient.getMaxConnectionsPerDestination();

        // The max number of requests per connection (multiplexing).
        // Start with 1, since this value is dynamically set to larger values if
        // the transport supports multiplexing requests on the same connection.
        int maxRequestsPerConnection = 1;

        HttpClientTransport transport = httpClient.getTransport();

        // Set the ConnectionPool.Factory using a lambda.
        transport.setConnectionPoolFactory(destination ->
            new RoundRobinConnectionPool(destination,
                maxConnectionsPerDestination,
                maxRequestsPerConnection));
        // end::setConnectionPool[]
    }

    public void preCreateConnections() throws Exception
    {
        // tag::preCreateConnections[]
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // For HTTP/1.1, you need to explicitly configure to initialize connections.
        if (httpClient.getTransport() instanceof HttpClientTransportOverHTTP http1)
            http1.setInitializeConnections(true);

        // Create a dummy request to the server you want to pre-create connections to.
        Request request = httpClient.newRequest("https://host/");

        // Resolve the destination for that request.
        Destination destination = httpClient.resolveDestination(request);

        // Pre-create, for example, half of the connections.
        int preCreate = httpClient.getMaxConnectionsPerDestination() / 2;
        CompletableFuture<Void> completable = destination.getConnectionPool().preCreateConnections(preCreate);

        // Wait for the connections to be created.
        completable.get(5, TimeUnit.SECONDS);
        // end::preCreateConnections[]
    }

    public void unixDomain() throws Exception
    {
        // tag::unixDomain[]
        // This is the path where the server "listens" on.
        Path unixDomainPath = Path.of("/path/to/server.sock");

        // Creates a ClientConnector.
        ClientConnector clientConnector = new ClientConnector();

        // You can use Unix-Domain for HTTP/1.1.
        HttpClientTransportOverHTTP http1Transport = new HttpClientTransportOverHTTP(clientConnector);

        // You can use Unix-Domain also for HTTP/2.
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        HttpClientTransportOverHTTP2 http2Transport = new HttpClientTransportOverHTTP2(http2Client);

        // You can use Unix-Domain also for the dynamic transport.
        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        HttpClientTransportDynamic dynamicTransport = new HttpClientTransportDynamic(clientConnector, http1, http2);

        // Choose the transport you prefer for HttpClient, for example the dynamic transport.
        HttpClient httpClient = new HttpClient(dynamicTransport);
        httpClient.start();

        ContentResponse response = httpClient.newRequest("jetty.org", 80)
            // Specify that the request must be sent over Unix-Domain.
            .transport(new Transport.TCPUnix(unixDomainPath))
            .send();
        // end::unixDomain[]
    }

    public void memory() throws Exception
    {
        // tag::memory[]
        // The server-side MemoryConnector speaking HTTP/1.1.
        Server server = new Server();
        MemoryConnector memoryConnector = new MemoryConnector(server, new HttpConnectionFactory());
        server.addConnector(memoryConnector);
        // ...

        // The code above is the server-side.
        // ----
        // The code below is the client-side.

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // Use the MemoryTransport to communicate with the server-side.
        Transport transport = new MemoryTransport(memoryConnector);

        httpClient.newRequest("http://localhost/")
            // Specify the Transport to use.
            .transport(transport)
            .send();
        // end::memory[]
    }

    public void mixedTransports() throws Exception
    {
        Path unixDomainPath = Path.of("/path/to/server.sock");

        Server server = new Server();
        MemoryConnector memoryConnector = new MemoryConnector(server, new HttpConnectionFactory());

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);

        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;

        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslContextFactory, null);
        HTTP3Client http3Client = new HTTP3Client(quicConfiguration, clientConnector);
        ClientConnectionFactoryOverHTTP3.HTTP3 http3 = new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client);

        // tag::mixedTransports[]
        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, http2, http1, http3));
        httpClient.start();

        // Make a TCP request to a 3rd party web application.
        ContentResponse thirdPartyResponse = httpClient.newRequest("https://third-party.com/api")
            // No need to specify the Transport, TCP will be used by default.
            .send();

        // Upload the third party response content to a validation process.
        ContentResponse validatedResponse = httpClient.newRequest("http://localhost/validate")
            // The validation process is available via Unix-Domain.
            .transport(new Transport.TCPUnix(unixDomainPath))
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(thirdPartyResponse.getContent()))
            .send();

        // Process the validated response intra-process by sending
        // it to another web application in the same Jetty server.
        ContentResponse response = httpClient.newRequest("http://localhost/process")
            // The processing is in-memory.
            .transport(new MemoryTransport(memoryConnector))
            .method(HttpMethod.POST)
            .body(new BytesRequestContent(validatedResponse.getContent()))
            .send();
        // end::mixedTransports[]
    }

    public void connectionInformation() throws Exception
    {
        // tag::connectionInformation[]
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        ContentResponse response = httpClient.newRequest("http://domain.com/path")
            // The connection information is only available starting from the request begin event.
            .onRequestBegin(request ->
            {
                Connection connection = request.getConnection();

                // Obtain the address of the server.
                SocketAddress remoteAddress = connection.getRemoteSocketAddress();
                System.getLogger("connection").log(INFO, "Server address: %s", remoteAddress);

                // Obtain the SslSessionData.
                EndPoint.SslSessionData sslSessionData = connection.getSslSessionData();
                if (sslSessionData != null)
                    System.getLogger("connection").log(INFO, "SslSessionData: %s", sslSessionData);
            })
            .send();
        // end::connectionInformation[]
    }

    public void connectListener() throws Exception
    {
        // tag::connectListener[]
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.addEventListener(new ClientConnector.ConnectListener()
        {
            private final ConcurrentMap<SocketChannel, Long> times = new ConcurrentHashMap<>();

            @Override
            public void onConnectBegin(SocketChannel socketChannel, SocketAddress socketAddress)
            {
                times.put(socketChannel, System.nanoTime());
            }

            @Override
            public void onConnectSuccess(SocketChannel socketChannel)
            {
                Long begin = times.remove(socketChannel);
                System.getLogger("connection").log(INFO, "established in %d ns", System.nanoTime() - begin);
            }

            @Override
            public void onConnectFailure(SocketChannel socketChannel, SocketAddress socketAddress, Throwable failure)
            {
                Long begin = times.remove(socketChannel);
                System.getLogger("connection").log(INFO, "failed in %d ns", System.nanoTime() - begin);
            }
        });

        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        httpClient.start();
        // end::connectListener[]
    }
}
