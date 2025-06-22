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

package org.eclipse.jetty.server.internal;

import java.nio.charset.Charset;
import java.util.Objects;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;

public class CachedQueryFields extends Fields
{
    public static final String KEY = "_oejsi_CQF";
    private final String _query;
    private final Charset _charset;

    public static CachedQueryFields getCached(Request request, String query, Charset charset)
    {
        // Cached fields can be stored directly in a ChannelRequest, else use an attribute
        CachedQueryFields cached = request instanceof HttpChannelState.ChannelRequest channelRequest
            ? channelRequest.getCachedQueryFields()
            : request.getAttribute(CachedQueryFields.class.getName()) instanceof CachedQueryFields c ? c : null;

        return cached != null && Objects.equals(query, cached._query) && Objects.equals(charset, cached._charset) ? cached : null;
    }

    public static void setCached(Request request, CachedQueryFields fields)
    {
        if (request instanceof HttpChannelState.ChannelRequest channelRequest)
            channelRequest.setCachedQueryFields(fields);
        else
            request.setAttribute(CachedQueryFields.class.getName(), fields);
    }

    public CachedQueryFields(String query, Charset charset)
    {
        super(true);
        _query = query;
        _charset = charset;
    }

    public String getQuery()
    {
        return _query;
    }

    public Charset getCharset()
    {
        return _charset;
    }
}
