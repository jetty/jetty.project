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
                // Set Content.Source instead of writing it.
                response.setContentSource(Content.Source.from(file));
                // Write no buffer, but the Content.Source instead.
                response.write(true, null, callback);
                return true;
            }
        });

        ContentResponse response = new CompletableResponseListener(client.newRequest(newURI(transportType)), contentLength)
            .send()
            .get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(contentLength, response.getContent().length);
    }

//    @ParameterizedTest
//    @MethodSource("transportsNoFCGI")
//    public void testResponseContentSourceInChunks(TransportType transportType) throws Exception
//    {
//        start(transportType, new Handler.Abstract()
//        {
//            @Override
//            public boolean handle(Request request, Response response, Callback callback) throws Exception
//            {
//                try (Blocker.Callback blocker = Blocker.callback())
//                {
//                    // Set Content.Source instead of writing it.
//                    response.setContentSource(Content.Source.from(path, 0, length1));
//                    // Write no buffer, but the Content.Source instead.
//                    response.write(false, null, blocker);
//                    blocker.block();
//                }
//
//                response.setContentSource(Content.Source.from(path, length1, length2));
//                response.write(true, null, callback);
//
//                return true;
//            }
//        });
//    }
}
