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

package org.eclipse.jetty.server;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.StringUtil;

public class ResponseUtils
{
    public static void ensureConsumeAvailableOrNotPersistent(Request request, Response response)
    {
        if (request.consumeAvailable())
            return;
        ensureNotPersistent(request, response);
    }

    public static void ensureNotPersistent(Request request, Response response)
    {
        switch (request.getConnectionMetaData().getHttpVersion())
        {
            case HTTP_1_0 ->
                // Remove any keep-alive value in Connection headers
                response.getHeaders().computeField(HttpHeader.CONNECTION, (h, fields) ->
                {
                    if (fields == null || fields.isEmpty())
                        return null;
                    String v = fields.stream()
                        .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s)))
                        .collect(Collectors.joining(", "));
                    if (StringUtil.isEmpty(v))
                        return null;

                    return new HttpField(HttpHeader.CONNECTION, v);
                });

            case HTTP_1_1 ->
                // Add close value to Connection headers
                response.getHeaders().computeField(HttpHeader.CONNECTION, (h, fields) ->
                {
                    if (fields == null || fields.isEmpty())
                        return HttpFields.CONNECTION_CLOSE;

                    if (fields.stream().anyMatch(f -> f.contains(HttpHeaderValue.CLOSE.asString())))
                    {
                        if (fields.size() == 1)
                        {
                            HttpField f = fields.get(0);
                            if (HttpFields.CONNECTION_CLOSE.equals(f))
                                return f;
                        }

                        return new HttpField(HttpHeader.CONNECTION, fields.stream()
                            .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s)))
                            .collect(Collectors.joining(", ")));
                    }

                    return new HttpField(HttpHeader.CONNECTION,
                        Stream.concat(fields.stream()
                                    .flatMap(field -> Stream.of(field.getValues()).filter(s -> !HttpHeaderValue.KEEP_ALIVE.is(s))),
                                Stream.of(HttpHeaderValue.CLOSE.asString()))
                            .collect(Collectors.joining(", ")));
                });
        }
    }

    private ResponseUtils()
    {
    }
}
