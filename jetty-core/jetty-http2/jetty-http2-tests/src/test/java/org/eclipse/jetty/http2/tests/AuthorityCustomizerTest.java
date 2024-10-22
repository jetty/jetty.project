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

package org.eclipse.jetty.http2.tests;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.server.AuthorityCustomizer;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AuthorityCustomizerTest extends AbstractServerTest
{
    @Test
    public void testSynthesizeAuthorityFromHost() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                int status = request.getHttpURI().hasAuthority() ? HttpStatus.OK_200 : HttpStatus.BAD_REQUEST_400;
                response.setStatus(status);
                callback.succeeded();
                return true;
            }
        });
        httpConfig.addCustomizer(new AuthorityCustomizer());

        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        generator.control(accumulator, new PrefaceFrame());
        generator.control(accumulator, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), null, path, HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        generator.control(accumulator, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : accumulator.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<HeadersFrame> frameRef = new AtomicReference<>();
            Parser parser = new Parser(bufferPool, 8192);
            parser.init(new Parser.Listener()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    frameRef.set(frame);
                    latch.countDown();
                }
            });
            parseResponse(client, parser);

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame frame = frameRef.get();
            MetaData.Response response = (MetaData.Response)frame.getMetaData();
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }
}
