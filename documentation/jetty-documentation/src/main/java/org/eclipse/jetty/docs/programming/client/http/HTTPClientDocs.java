//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.RoundRobinConnectionPool;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.client.util.PathRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

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
        // Disable certificate validation at the TLS level.
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        // end::tlsNoValidation[]
    }

    public void tlsAppValidation()
    {
        // tag::tlsAppValidation[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        // Only allow subdomains of domain.com.
        sslContextFactory.setHostnameVerifier((hostName, session) -> hostName.endsWith(".domain.com"));
        // end::tlsAppValidation[]
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
            .onResponseContentAsync((response, chunk, demander) ->
            {
                chunk.release();
                demander.run();
            })
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
            void publish(ByteBufferPool bufferPool, byte[] bytes, boolean lastContent)
            {
                // Wrap the bytes into a new ByteBuffer.
                ByteBuffer buffer = ByteBuffer.wrap(bytes);

                // Offer the content, and release the ByteBuffer
                // to the pool when the Callback is completed.
                content.write(buffer, Callback.from(() -> bufferPool.release(buffer)));

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

        // tag::futureResponseListener[]
        Request request = httpClient.newRequest("http://domain.com/path");

        // Limit response content buffer to 512 KiB.
        FutureResponseListener listener = new FutureResponseListener(request, 512 * 1024);

        request.send(listener);

        // Wait at most 5 seconds for request+response to complete.
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        // end::futureResponseListener[]
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
        InputStreamResponseListener listener = new InputStreamResponseListener();
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
        // end::inputStreamResponseListener[]
    }

    public void contentSourceListener() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        String host1 = "localhost";
        String host2 = "localhost";
        int port1 = 8080;
        int port2 = 8080;
        // tag::contentSourceListener[]
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
                // Check if the chunk is the terminal one, in which case the
                // read/demand loop is done. Demanding again when the terminal
                // chunk has been read will invoke the demand callback with
                // the same terminal chunk, so this check must be present to
                // avoid infinitely demanding and reading the terminal chunk.
                if (chunk.isTerminal())
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
        // end::contentSourceListener[]
    }

    public void getCookies() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::getCookies[]
        CookieStore cookieStore = httpClient.getCookieStore();
        List<HttpCookie> cookies = cookieStore.get(URI.create("http://domain.com/path"));
        // end::getCookies[]
    }

    public void setCookie() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::setCookie[]
        CookieStore cookieStore = httpClient.getCookieStore();
        HttpCookie cookie = new HttpCookie("foo", "bar");
        cookie.setDomain("domain.com");
        cookie.setPath("/");
        cookie.setMaxAge(TimeUnit.DAYS.toSeconds(1));
        cookieStore.add(URI.create("http://domain.com"), cookie);
        // end::setCookie[]
    }

    public void requestCookie() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::requestCookie[]
        ContentResponse response = httpClient.newRequest("http://domain.com/path")
            .cookie(new HttpCookie("foo", "bar"))
            .send();
        // end::requestCookie[]
    }

    public void removeCookie() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::removeCookie[]
        CookieStore cookieStore = httpClient.getCookieStore();
        URI uri = URI.create("http://domain.com");
        List<HttpCookie> cookies = cookieStore.get(uri);
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
        httpClient.setCookieStore(new HttpCookieStore.Empty());
        // end::emptyCookieStore[]
    }

    public void filteringCookieStore() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        // tag::filteringCookieStore[]
        class GoogleOnlyCookieStore extends HttpCookieStore
        {
            @Override
            public void add(URI uri, HttpCookie cookie)
            {
                if (uri.getHost().endsWith("google.com"))
                    super.add(uri, cookie);
            }
        }

        httpClient.setCookieStore(new GoogleOnlyCookieStore());
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
        HTTP2Client h2Client = new HTTP2Client();
        h2Client.setInitialSessionRecvWindow(64 * 1024 * 1024);

        // Create and configure the HTTP/2 transport.
        HttpClientTransportOverHTTP2 transport = new HttpClientTransportOverHTTP2(h2Client);
        transport.setUseALPN(true);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::http2Transport[]
    }

    public void http3Transport() throws Exception
    {
        // tag::http3Transport[]
        // The HTTP3Client powers the HTTP/3 transport.
        HTTP3Client h3Client = new HTTP3Client();
        h3Client.getQuicConfiguration().setSessionRecvWindow(64 * 1024 * 1024);

        // Create and configure the HTTP/3 transport.
        HttpClientTransportOverHTTP3 transport = new HttpClientTransportOverHTTP3(h3Client);

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

    public void dynamicH1H2() throws Exception
    {
        // tag::dynamicH1H2[]
        ClientConnector connector = new ClientConnector();

        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;

        HTTP2Client http2Client = new HTTP2Client(connector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);

        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(connector, http1, http2);

        HttpClient client = new HttpClient(transport);
        client.start();
        // end::dynamicH1H2[]
    }

    public void dynamicClearText() throws Exception
    {
        // tag::dynamicClearText[]
        ClientConnector connector = new ClientConnector();
        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;
        HTTP2Client http2Client = new HTTP2Client(connector);
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(connector, http1, http2);
        HttpClient client = new HttpClient(transport);
        client.start();

        // The server supports both HTTP/1.1 and HTTP/2 clear-text on port 8080.

        // Make a clear-text request without explicit version.
        // The first protocol specified to HttpClientTransportDynamic
        // is picked, in this example will be HTTP/1.1.
        ContentResponse http1Response = client.newRequest("host", 8080).send();

        // Make a clear-text request with explicit version.
        // Clear-text HTTP/2 is used for this request.
        ContentResponse http2Response = client.newRequest("host", 8080)
            // Specify the version explicitly.
            .version(HttpVersion.HTTP_2)
            .send();

        // Make a clear-text upgrade request from HTTP/1.1 to HTTP/2.
        // The request will start as HTTP/1.1, but the response will be HTTP/2.
        ContentResponse upgradedResponse = client.newRequest("host", 8080)
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .send();
        // end::dynamicClearText[]
    }

    public void getConnectionPool() throws Exception
    {
        // tag::getConnectionPool[]
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        ConnectionPool connectionPool = httpClient.getDestinations().stream()
            // Cast to HttpDestination.
            .map(HttpDestination.class::cast)
            // Find the destination by filtering on the Origin.
            .filter(destination -> destination.getOrigin().getAddress().getHost().equals("domain.com"))
            .findAny()
            // Get the ConnectionPool.
            .map(HttpDestination::getConnectionPool)
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
                destination,
                maxRequestsPerConnection));
        // end::setConnectionPool[]
    }

    public void unixDomain() throws Exception
    {
        // tag::unixDomain[]
        // This is the path where the server "listens" on.
        Path unixDomainPath = Path.of("/path/to/server.sock");

        // Creates a ClientConnector that uses Unix-Domain
        // sockets, not the network, to connect to the server.
        ClientConnector unixDomainClientConnector = ClientConnector.forUnixDomain(unixDomainPath);

        // Use Unix-Domain for HTTP/1.1.
        HttpClientTransportOverHTTP http1Transport = new HttpClientTransportOverHTTP(unixDomainClientConnector);

        // You can use Unix-Domain also for HTTP/2.
        HTTP2Client http2Client = new HTTP2Client(unixDomainClientConnector);
        HttpClientTransportOverHTTP2 http2Transport = new HttpClientTransportOverHTTP2(http2Client);

        // You can also use UnixDomain for the dynamic transport.
        ClientConnectionFactory.Info http1 = HttpClientConnectionFactory.HTTP11;
        ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        HttpClientTransportDynamic dynamicTransport = new HttpClientTransportDynamic(unixDomainClientConnector, http1, http2);

        // Choose the transport you prefer for HttpClient, for example the dynamic transport.
        HttpClient httpClient = new HttpClient(dynamicTransport);
        httpClient.start();
        // end::unixDomain[]
    }
}
