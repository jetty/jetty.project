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

package org.eclipse.jetty.http.content;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.content.HttpContent.Factory;

/**
 * This {@link HttpContent.Factory} populates the {@link HttpContent#getPreCompressedContentFormats()} field for any
 * {@link HttpContent} fetched through this factory.
 */
public class PreCompressedHttpContentFactory implements HttpContent.Factory
{
    private final HttpContent.Factory _factory;
    private final List<CompressedContentFormat> _preCompressedFormats;

    public PreCompressedHttpContentFactory(Factory factory, CompressedContentFormat[] preCompressedFormats)
    {
        this(factory, Arrays.asList(preCompressedFormats));
    }

    public PreCompressedHttpContentFactory(HttpContent.Factory factory, List<CompressedContentFormat> preCompressedFormats)
    {
        _factory = factory;
        _preCompressedFormats = preCompressedFormats;
    }

    @Override
    public HttpContent getContent(String pathInContext) throws IOException
    {
        HttpContent content = _factory.getContent(pathInContext);
        if (content == null)
            return null;

        Set<CompressedContentFormat> compressedFormats = new HashSet<>();
        for (CompressedContentFormat contentFormat : _preCompressedFormats)
        {
            HttpContent preCompressedContent = _factory.getContent(pathInContext + contentFormat.getExtension());
            if (preCompressedContent != null)
                compressedFormats.add(contentFormat);
        }

        return new CompressedFormatsHttpContent(content, compressedFormats);
    }

    @Override
    public String toString()
    {
        return "%s@%x[%s,%s]".formatted(getClass().getSimpleName(), hashCode(), _factory, _preCompressedFormats);
    }

    private static class CompressedFormatsHttpContent extends HttpContent.Wrapper
    {
        private final Set<CompressedContentFormat> compressedFormats;

        public CompressedFormatsHttpContent(HttpContent content, Set<CompressedContentFormat> compressedFormats)
        {
            super(content);
            this.compressedFormats = compressedFormats;
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return compressedFormats;
        }
    }
}
