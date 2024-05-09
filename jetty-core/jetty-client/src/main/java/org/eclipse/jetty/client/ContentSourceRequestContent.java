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

package org.eclipse.jetty.client;

import java.util.Objects;
import org.eclipse.jetty.io.Content;

/**
 * A {@link Request.Content} that wraps a {@link Content.Source}.
 */
public class ContentSourceRequestContent implements Request.Content
{
    private final Content.Source source;
    private final String contentType;

    public ContentSourceRequestContent(Content.Source source)
    {
        this(source, "application/octet-stream");
    }

    public ContentSourceRequestContent(Content.Source source, String contentType)
    {
        this.source = Objects.requireNonNull(source);
        this.contentType = contentType;
    }

    public Content.Source getContentSource()
    {
        return source;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    public long getLength()
    {
        return getContentSource().getLength();
    }

    @Override
    public Content.Chunk read()
    {
        return getContentSource().read();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        getContentSource().demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        fail(failure, true);
    }

    @Override
    public void fail(Throwable failure, boolean last)
    {
        getContentSource().fail(failure, last);
    }

    @Override
    public boolean rewind()
    {
        return getContentSource().rewind();
    }
}
