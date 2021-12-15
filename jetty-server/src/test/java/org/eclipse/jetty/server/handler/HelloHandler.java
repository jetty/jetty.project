//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class HelloHandler extends Handler.Abstract
{
    private static final Logger LOG = LoggerFactory.getLogger(HelloHandler.class);
    private final String _message;
    private final ByteBuffer _byteBuffer;

    public HelloHandler()
    {
        this("Hello");
    }

    public HelloHandler(String message)
    {
        _message = message;
        _byteBuffer = BufferUtil.toBuffer(_message, StandardCharsets.UTF_8);
    }

    public String getMessage()
    {
        return _message;
    }

    @Override
    public boolean handle(Request request, Response response) throws Exception
    {
        response.setStatus(200);
        response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
        response.setContentLength(_byteBuffer.remaining());
        response.write(true, request, _byteBuffer.slice());
        return true;
    }
}
