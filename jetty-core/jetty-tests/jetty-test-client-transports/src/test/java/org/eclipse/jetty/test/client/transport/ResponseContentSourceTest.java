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

package org.eclipse.jetty.test.client.transport;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ResponseContentSourceTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseContentSource(TransportType transportType) throws Exception
    {
        // Prepare a "small" file. TODO: also use a >2GiB file.
        int contentLength = 1024 * 1024;
        Path dir = Files.createDirectories(MavenPaths.targetTestDir(getClass().getSimpleName()));
        Path file = Files.createTempFile(dir, "file-", ".bin");
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE))
        {
            channel.write(ByteBuffer.allocateDirect(contentLength));
        }

        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Source source = Content.Source.from(file);
                // TODO: possible alternative?
//                source.writeTo(response, true, callback);
                Content.Sink.write(response, true, source, callback);
                return true;
            }
        });

        ContentResponse response = new CompletableResponseListener(client.newRequest(newURI(transportType)), contentLength)
            .send()
            .get(555, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(contentLength, response.getContent().length);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseContentSourceInChunks(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                int contentLength = 1024 * 1024;
                Path dir = Files.createDirectories(MavenPaths.targetTestDir(getClass().getSimpleName()));
                Path file = Files.createTempFile(dir, "file-", ".bin");
                try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE))
                {
                    channel.write(ByteBuffer.allocateDirect(contentLength));
                }

                // Write first chunk.
                int length1 = contentLength / 2;
                try (Blocker.Callback blocker = Blocker.callback())
                {
                    Content.Sink.write(response, false, Content.Source.from(file, 0, length1), blocker);
                    blocker.block();
                }

                // Write last chunk.
                Content.Sink.write(response, true, Content.Source.from(file, length1, contentLength - length1), callback);
                return true;
            }
        });
    }
}
