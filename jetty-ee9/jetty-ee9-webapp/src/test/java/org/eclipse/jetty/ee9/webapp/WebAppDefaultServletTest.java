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

package org.eclipse.jetty.ee9.webapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class WebAppDefaultServletTest
{
    public WorkDir workDir;
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepareServer() throws Exception
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        server = new Server();
        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        server.addConnector(connector);

        Path directoryPath = workDir.getEmptyPathDir();
        Path welcomeResource = directoryPath.resolve("index.html");
        Files.writeString(welcomeResource, "<h1>welcome page</h1>", StandardCharsets.UTF_8);

        Path otherResource = directoryPath.resolve("other.html");
        Files.writeString(otherResource, "<h1>other resource</h1>", StandardCharsets.UTF_8);

        Path hiddenDirectory = directoryPath.resolve("WEB-INF");
        Files.createDirectories(hiddenDirectory);
        Path hiddenResource = hiddenDirectory.resolve("one.js");
        Files.writeString(hiddenResource, "this is confidential", StandardCharsets.UTF_8);

        // Create directory to trick resource service.
        Path hackPath = directoryPath.resolve("%57EB-INF/one.js#/");
        Files.createDirectories(hackPath);
        Files.writeString(hackPath.resolve("index.html"), "this content does not matter", StandardCharsets.UTF_8);

        Path standardHashDir = directoryPath.resolve("welcome#");
        Files.createDirectories(standardHashDir);
        Files.writeString(standardHashDir.resolve("index.html"), "standard hash dir welcome", StandardCharsets.UTF_8);

        WebAppContext context = new WebAppContext();
        context.setBaseResource(context.getResourceFactory().newResource(directoryPath));
        context.setContextPath("/");
        server.setHandler(context);
        server.start();

        // Verify that I can get the file programmatically, as required by the spec.
        assertNotNull(context.getServletContext().getResource("/WEB-INF/one.js"));
    }

    @AfterEach
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    public static Stream<Arguments> argumentsStream()
    {
        return Stream.of(
            Arguments.of("/WEB-INF/", new String[]{"HTTP/1.1 404 "}),
            Arguments.of("/welcome%23/", new String[]{"HTTP/1.1 200 ", "standard hash dir welcome"}),

            // Normal requests for the directory are redirected to the welcome page.
            Arguments.of("/", new String[]{"HTTP/1.1 200 ", "<h1>welcome page</h1>"}),

            // We can be served other resources.
            Arguments.of("/other.html", new String[]{"HTTP/1.1 200 ", "<h1>other resource</h1>"}),

            // The ContextHandler will filter these ones out as as WEB-INF is a protected target.
            Arguments.of("/WEB-INF/one.js#/", new String[]{"HTTP/1.1 404 "}),
            Arguments.of("/js/../WEB-INF/one.js#/", new String[]{"HTTP/1.1 404 "}),

            // Test the URI is not double decoded by the dispatcher that serves the welcome file (we get index.html not one.js).
            Arguments.of("/%2557EB-INF/one.js%23/", new String[]{"HTTP/1.1 200 ", "this content does not matter"})
        );
    }

    @ParameterizedTest
    @MethodSource("argumentsStream")
    public void testResourceService(String uri, String[] contains) throws Exception
    {
        String request = """
            GET %s HTTP/1.1
            Host: localhost
            Connection: close
            
            """.formatted(uri);
        String response = connector.getResponse(request);
        for (String s : contains)
        {
            assertThat(response, containsString(s));
        }
    }
}
