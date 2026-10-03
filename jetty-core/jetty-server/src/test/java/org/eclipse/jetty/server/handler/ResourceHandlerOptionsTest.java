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

package org.eclipse.jetty.server.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class ResourceHandlerOptionsTest
{
    public WorkDir workDir;
    private Server _server;

    @AfterEach
    public void tearDown() throws Exception
    {
        if (_server != null)
            _server.stop();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testOptionsForMissingResourceReachesNextHandler() throws Exception
    {
        Path docRoot = workDir.getEmptyPathDir();
        Files.writeString(docRoot.resolve("index.html"), "hello");

        AtomicInteger handled = new AtomicInteger();
        Handler next = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                handled.incrementAndGet();
                response.setStatus(HttpStatus.NO_CONTENT_204);
                callback.succeeded();
                return true;
            }
        };

        ResourceHandler resources = new ResourceHandler();
        resources.setBaseResource(ResourceFactory.root().newResource(docRoot));
        resources.setHandler(next);

        _server = new Server();
        LocalConnector connector = new LocalConnector(_server);
        _server.addConnector(connector);
        _server.setHandler(resources);
        _server.start();

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            OPTIONS /no-such HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));

        assertThat(handled.get(), is(1));
        assertThat(response.getStatus(), is(HttpStatus.NO_CONTENT_204));
    }

    @Test
    public void testOptionsForExistingResourceIsHandledLocally() throws Exception
    {
        Path docRoot = workDir.getEmptyPathDir();
        Files.writeString(docRoot.resolve("index.html"), "hello");

        AtomicInteger handled = new AtomicInteger();
        Handler next = new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                handled.incrementAndGet();
                response.setStatus(HttpStatus.NO_CONTENT_204);
                callback.succeeded();
                return true;
            }
        };

        ResourceHandler resources = new ResourceHandler();
        resources.setBaseResource(ResourceFactory.root().newResource(docRoot));
        resources.setHandler(next);

        _server = new Server();
        LocalConnector connector = new LocalConnector(_server);
        _server.addConnector(connector);
        _server.setHandler(resources);
        _server.start();

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            OPTIONS /index.html HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));

        assertThat(handled.get(), is(0));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(HttpHeader.ALLOW), containsString("OPTIONS"));
    }
}
