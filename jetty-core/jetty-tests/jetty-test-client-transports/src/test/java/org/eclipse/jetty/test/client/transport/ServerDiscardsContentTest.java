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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ServerDiscardsContentTest extends AbstractTest
{
    @ParameterizedTest
    @EnumSource(value = Transport.class, names = {"H2C", "H2"})
    public void testServerDiscardsContent(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not read the request content.
                // Immediately write a response.
                response.write(true, null, callback);
                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(1024));
        ContentResponse response = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        // With HTTP/2, the server sends a RST_STREAM(NO_ERROR) frame,
        // that should be interpreted by the client not as a failure,
        // even if the client did not finish to send the request content.
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
