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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class EchoHandler extends Handler.Abstract.NonBlocking
{
    public EchoHandler()
    {
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        response.setStatus(200);

        long contentLength = -1;
        for (HttpField field : request.getHeaders())
        {
            if (field.getHeader() != null)
            {
                switch (field.getHeader())
                {
                    case CONTENT_LENGTH -> response.getHeaders().add(HttpHeader.CONTENT_LENGTH, contentLength = field.getLongValue());
                    case CONTENT_TYPE -> response.getHeaders().add(field);
                    case TRAILER -> response.setTrailersSupplier(HttpFields.build());
                    case TRANSFER_ENCODING -> contentLength = Long.MAX_VALUE;
                }
            }
        }

        if (contentLength > 0)
            Content.copy(request, response, Response.newTrailersChunkProcessor(response), callback);
        else
            callback.succeeded();
        return true;
    }
}
