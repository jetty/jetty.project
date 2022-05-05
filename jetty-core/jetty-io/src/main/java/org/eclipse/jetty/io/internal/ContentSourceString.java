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

package org.eclipse.jetty.io.internal;

import java.nio.charset.Charset;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.CharsetStringBuilder;
import org.eclipse.jetty.util.Promise;

public class ContentSourceString
{
    private final Content.Source content;
    private final CharsetStringBuilder text;
    private final Promise<String> promise;

    public ContentSourceString(Content.Source content, Charset charset, Promise<String> promise)
    {
        this.content = content;
        this.text = CharsetStringBuilder.forCharset(charset);
        this.promise = promise;
    }

    public void convert()
    {
        while (true)
        {
            Content.Chunk chunk = content.read();
            if (chunk == null)
            {
                content.demand(this::convert);
                return;
            }
            if (chunk instanceof Content.Chunk.Error error)
            {
                promise.failed(error.getCause());
                return;
            }
            text.append(chunk.getByteBuffer());
            chunk.release();
            if (chunk.isLast())
            {
                succeed();
                return;
            }
        }
    }

    private void succeed()
    {
        try
        {
            String result = text.takeString();
            promise.succeeded(result);
        }
        catch (Throwable x)
        {
            promise.failed(x);
        }
    }
}
