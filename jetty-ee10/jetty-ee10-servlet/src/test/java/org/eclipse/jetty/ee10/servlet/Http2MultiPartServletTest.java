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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.MultiPartRequestContent;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class Http2MultiPartServletTest
{
    private static final int MAX_FILE_SIZE = 512 * 1024;

    private Server server;
    private ServerConnector tlsConnector;
    private HttpClient client;
    private Path tmpDir;
    private String tmpDirString;

    @BeforeEach
    public void before() throws Exception
    {
        tmpDir = Files.createTempDirectory(Http2MultiPartServletTest.class.getSimpleName());
        tmpDirString = tmpDir.toAbsolutePath().toString();
    }

    private void start(HttpServlet servlet, MultipartConfigElement config) throws Exception
    {
        config = config == null ? new MultipartConfigElement(tmpDirString, MAX_FILE_SIZE, -1, 0) : config;
        server = new Server(null, null, null);

        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory h1c = new HttpConnectionFactory(httpConfig);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        ServerConnector connector = new ServerConnector(server, 1, 1, h1c, h2c);
        server.addConnector(connector);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenTestingUtils.getTestResourcePath("keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1s = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2s = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1s.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        tlsConnector = new ServerConnector(server, 1, 1, ssl, alpn, h1s, h2s);
        server.addConnector(tlsConnector);

        ServletContextHandler servletContextHandler = new ServletContextHandler("/");
        ServletHolder servletHolder = new ServletHolder(servlet);
        servletHolder.getRegistration().setMultipartConfig(config);
        servletContextHandler.addServlet(servletHolder, "/");

        // TODO: this configuration should not be necessary.
        servletContextHandler.setMaxFormContentSize(-1);
        servletContextHandler.setMaxFormKeys(-1);

        server.setHandler(servletContextHandler);

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("multipart/form-data");
        gzipHandler.setMinGzipSize(32);

        servletContextHandler.insertHandler(gzipHandler);

        server.start();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector))));
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
        IO.delete(tmpDir.toFile());
    }

    @Test
    public void testLargePart() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
            {
                Collection<Part> parts = req.getParts();
                resp.getWriter().printf("parts(%s): %s", parts.size(), parts);
            }
        }, new MultipartConfigElement(tmpDirString, -1, -1, -1));

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("param", "filename.txt", null, content));
        multiPart.close();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", tlsConnector.getLocalPort())
            .path("/defaultConfig")
            .method(HttpMethod.POST)
            .scheme(HttpScheme.HTTPS.asString())
            .body(multiPart)
            .send(listener);

        // Write large amount of content to the part.
        byte[] byteArray = new byte[1024 * 1024];
        Arrays.fill(byteArray, (byte)1);
        for (int i = 0; i < 1024 * 2; i++)
        {
            content.getOutputStream().write(byteArray);
        }
        content.close();

        Response response = listener.get(60, TimeUnit.SECONDS);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        String responseContent = IO.toString(listener.getInputStream());
        assertThat(responseContent, containsString("parts(1): "));
        assertThat(responseContent, containsString("[name=param,fileName=filename.txt,length=2147483648]"));
    }
}
