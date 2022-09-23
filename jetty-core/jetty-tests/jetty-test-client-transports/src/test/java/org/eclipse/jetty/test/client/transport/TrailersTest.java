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

package org.eclipse.jetty.test.client.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TrailersTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testTrailers(Transport transport) throws Exception
    {
        String trailerName = "Some-Trailer";
        String trailerValue = "0xC0FFEE";
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Read slowly.
                try (InputStream input = Content.Source.asInputStream(request))
                {
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                }

                HttpFields requestTrailers = request.getTrailers();
                assertNotNull(requestTrailers);
                assertEquals(trailerValue, requestTrailers.get(trailerName));

                HttpFields.Mutable responseTrailers = HttpFields.build();
                response.setTrailersSupplier(() -> responseTrailers);

                // Write the content first, then the trailers.
                response.write(false, ByteBuffer.allocate(1024 * 1024), new Callback.Nested(callback)
                {
                    @Override
                    public void succeeded()
                    {
                        responseTrailers.put(trailerName, trailerValue);
                        response.write(true, null, getCallback());
                    }
                });
            }
        });

        HttpFields.Mutable requestTrailers = HttpFields.build();
        OutputStreamRequestContent body = new OutputStreamRequestContent();
        InputStreamResponseListener listener = new InputStreamResponseListener();
        try (OutputStream output = body.getOutputStream())
        {
            client.newRequest(newURI(transport))
                .trailersSupplier(() -> requestTrailers)
                .body(body)
                .send(listener);

            // Write the content first, then the trailers.
            output.write(new byte[1024 * 1024]);
            requestTrailers.put(trailerName, trailerValue);
        }

        var response = listener.get(5, TimeUnit.SECONDS);

        // Read slowly.
        try (InputStream input = listener.getInputStream())
        {
            while (true)
            {
                int read = input.read();
                if (read < 0)
                    break;
            }
        }

        HttpFields responseTrailers = response.getTrailers();
        assertNotNull(responseTrailers);
        assertEquals(trailerValue, responseTrailers.get(trailerName));
    }
}
