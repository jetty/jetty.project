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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MappedFileContentFactory implements HttpContent.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(MappedFileContentFactory.class);

    private final HttpContent.Factory _factory;

    public MappedFileContentFactory(HttpContent.Factory factory)
    {
        _factory = Objects.requireNonNull(factory);
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        HttpContent content = _factory.getContent(path);
        if (content.getContentLengthValue() > 16 * 1024)
            return new FileMappedContent(content);
        return content;
    }

    public static class FileMappedContent extends HttpContentWrapper
    {
        private final HttpContent content;

        public FileMappedContent(HttpContent content)
        {
            super(content);
            this.content = content;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            try
            {
                return BufferUtil.toMappedBuffer(content.getResource().getPath());
            }
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error getting Mapped Buffer", t);
            }

            return super.getBuffer();
        }
    }
}
