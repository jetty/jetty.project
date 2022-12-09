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

package org.eclipse.jetty.fcgi.proxy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FastCGIProxyHandlerTest
{
    private Server server;
    private ServerConnector proxyConnector;
    private Connector serverConnector;
    private ContextHandler proxyContext;
    private HttpClient client;
    private Path unixDomainPath;
    private FastCGIProxyHandler fcgiHandler;

    public void start(boolean sendStatus200, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        proxyConnector = new ServerConnector(server, 1, 1);
        server.addConnector(proxyConnector);

        ServerFCGIConnectionFactory fcgi = new ServerFCGIConnectionFactory(new HttpConfiguration(), sendStatus200);
        if (unixDomainPath == null)
        {
            serverConnector = new ServerConnector(server, 1, 1, fcgi);
        }
        else
        {
            UnixDomainServerConnector connector = new UnixDomainServerConnector(server, 1, 1, fcgi);
            connector.setUnixDomainPath(unixDomainPath);
            serverConnector = connector;
        }
        server.addConnector(serverConnector);

        proxyContext = new ContextHandler("/ctx");

        String appContextPath = "/app";
        fcgiHandler = new FastCGIProxyHandler(request ->
        {
            HttpURI httpURI = request.getHttpURI();
            HttpURI.Mutable newHttpURI = HttpURI.build(httpURI)
                .path(appContextPath + Request.getPathInContext(request));
            newHttpURI.port(unixDomainPath == null ? ((ServerConnector)serverConnector).getLocalPort() : 0);
            return newHttpURI;
        }, "/scriptRoot");
        fcgiHandler.setUnixDomainPath(unixDomainPath);
        proxyContext.setHandler(fcgiHandler);

        ContextHandler appContext = new ContextHandler("/app");
        appContext.setHandler(handler);

        ContextHandlerCollection contexts = new ContextHandlerCollection(proxyContext, appContext);
        server.setHandler(contexts);

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
        client.setExecutor(clientThreads);
        server.addBean(client);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testGETWithSmallResponseContent(boolean sendStatus200) throws Exception
    {
        testGETWithResponseContent(sendStatus200, 1024, 0);
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testGETWithLargeResponseContent(boolean sendStatus200) throws Exception
    {
        testGETWithResponseContent(sendStatus200, 16 * 1024 * 1024, 0);
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testGETWithLargeResponseContentWithSlowClient(boolean sendStatus200) throws Exception
    {
        testGETWithResponseContent(sendStatus200, 16 * 1024 * 1024, 1);
    }

    private void testGETWithResponseContent(boolean sendStatus200, int length, long delay) throws Exception
    {
        byte[] data = new byte[length];
        new Random().nextBytes(data);

        String path = "/foo/index.php";
        start(sendStatus200, new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertNotEquals(proxyContext.getContextPath(), request.getContext().getContextPath());
                assertEquals(path, Request.getPathInContext(request));
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length);
                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        var request = client.newRequest("localhost", proxyConnector.getLocalPort())
            .onResponseContentAsync((response, content, callback) ->
            {
                try
                {
                    if (delay > 0)
                        TimeUnit.MILLISECONDS.sleep(delay);
                    callback.succeeded();
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            })
            .path(proxyContext.getContextPath() + path);
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.send(listener);

        ContentResponse response = listener.get(30, TimeUnit.SECONDS);

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @ParameterizedTest(name = "[{index}] sendStatus200={0}")
    @ValueSource(booleans = {true, false})
    public void testURIRewrite(boolean sendStatus200) throws Exception
    {
        String originalPath = "/original/index.php";
        String originalQuery = "foo=bar";
        String remotePath = "/remote/index.php";
        String pathAttribute = "_path_attribute";
        String queryAttribute = "_query_attribute";
        start(sendStatus200, new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertThat((String)request.getAttribute(FCGI.Headers.REQUEST_URI), startsWith(originalPath));
                assertEquals(originalQuery, request.getAttribute(FCGI.Headers.QUERY_STRING));
                assertThat(Request.getPathInContext(request), endsWith(remotePath));
                callback.succeeded();
            }
        });
        fcgiHandler.setOriginalPathAttribute(pathAttribute);
        fcgiHandler.setOriginalQueryAttribute(queryAttribute);

        proxyContext.stop();
        proxyContext.insertHandler(new Handler.Wrapper()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                if (Request.getPathInContext(request).startsWith("/remote/"))
                {
                    request.setAttribute(pathAttribute, originalPath);
                    request.setAttribute(queryAttribute, originalQuery);
                }
                super.process(request, response, callback);
            }
        });
        proxyContext.start();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(proxyContext.getContextPath() + remotePath)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testUnixDomain() throws Exception
    {
        Path path = Files.createTempFile("unix", ".sock");
        if (path.normalize().toAbsolutePath().toString().length() > UnixDomainServerConnector.MAX_UNIX_DOMAIN_PATH_LENGTH)
            path = Files.createTempFile(Path.of("/tmp"), "unix", ".sock");
        assertTrue(Files.deleteIfExists(path));
        unixDomainPath = path;
        byte[] content = new byte[512];
        new Random().nextBytes(content);
        start(true, new Handler.Abstract()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(content), callback);
            }
        });

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .path(proxyContext.getContextPath() + "/index.php")
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(content, response.getContent());
    }
}
