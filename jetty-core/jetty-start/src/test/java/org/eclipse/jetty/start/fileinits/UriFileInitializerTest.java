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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.StartArgs;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.JettyBaseConfigSource;
import org.eclipse.jetty.start.config.JettyHomeConfigSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class UriFileInitializerTest
{
    public WorkDir workDir;
    public Path testdir;

    private BaseHome baseHome;

    @BeforeEach
    public void setupBaseHome() throws IOException
    {
        testdir = workDir.getEmptyPathDir();
        Path homeDir = testdir.resolve("home");
        Path baseDir = testdir.resolve("base");

        FS.ensureDirExists(homeDir);
        FS.ensureDirExists(baseDir);

        ConfigSources config = new ConfigSources();
        config.add(new JettyHomeConfigSource(homeDir));
        config.add(new JettyBaseConfigSource(baseDir));

        this.baseHome = new BaseHome(config);
    }

    @Test
    @Tag("external")
    public void testExists() throws Exception
    {
        UriFileInitializer initializer = new UriFileInitializer(new StartArgs(baseHome), baseHome);
        URI uri = URI.create("https://jetty.org/_/img/favicon.ico");
        assertThat("exists", initializer.exists(uri), is(true));
    }

    @Test
    public void testExistsInsecureHttpThrows()
    {
        // Insecure http:// is rejected before any network access (downloads not allowed).
        UriFileInitializer initializer = newInitializer(false);
        URI uri = URI.create("http://example.org/foo.txt");
        IOException x = assertThrows(IOException.class, () -> initializer.exists(uri));
        assertThat(x.getMessage(), containsString("Insecure HTTP download not allowed"));
    }

    @Test
    public void testExistsHeadOkReturnsTrue() throws Exception
    {
        HttpServer server = startServer(exchange -> respond(exchange, 200, false));
        try
        {
            UriFileInitializer initializer = newInitializer(true);
            assertThat("exists", initializer.exists(uriFor(server, "/foo.txt")), is(true));
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    public void testExistsHeadNotFoundReturnsFalse() throws Exception
    {
        HttpServer server = startServer(exchange -> respond(exchange, 404, false));
        try
        {
            UriFileInitializer initializer = newInitializer(true);
            assertThat("exists", initializer.exists(uriFor(server, "/missing.txt")), is(false));
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    public void testExistsHeadMethodNotAllowedReturnsFalse() throws Exception
    {
        // Existence is checked with a HEAD only. A server that rejects HEAD (405) is reported
        // as "does not exist" rather than falling back to a full GET that downloads the body.
        boolean[] getRequested = {false};
        HttpServer server = startServer(exchange ->
        {
            if ("HEAD".equals(exchange.getRequestMethod()))
            {
                respond(exchange, 405, false);
            }
            else
            {
                getRequested[0] = true;
                respond(exchange, 200, true);
            }
        });
        try
        {
            UriFileInitializer initializer = newInitializer(true);
            assertThat("exists", initializer.exists(uriFor(server, "/head-unsupported.txt")), is(false));
            assertThat("no GET fallback performed", getRequested[0], is(false));
        }
        finally
        {
            server.stop(0);
        }
    }

    private UriFileInitializer newInitializer(boolean allowInsecureHttpDownloads)
    {
        return new UriFileInitializer(new StartArgs(baseHome), baseHome)
        {
            @Override
            protected boolean allowInsecureHttpDownloads()
            {
                return allowInsecureHttpDownloads;
            }
        };
    }

    private HttpServer startServer(HttpHandler handler) throws IOException
    {
        // jetty-start is the standalone bootstrap module and cannot depend on the Jetty Server
        // (that would be a layering violation, and jetty-server is not available here anyway), so
        // these tests use the JVM-provided com.sun.net.httpserver.HttpServer instead.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private URI uriFor(HttpServer server, String path)
    {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, boolean withBody) throws IOException
    {
        try (exchange)
        {
            if (withBody)
            {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody())
                {
                    out.write(body);
                }
            }
            else
            {
                exchange.sendResponseHeaders(status, -1);
            }
        }
    }
}
