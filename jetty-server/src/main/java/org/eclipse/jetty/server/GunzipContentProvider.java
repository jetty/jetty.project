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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.GZIPContentDecoder;
import org.eclipse.jetty.util.BufferUtil;

public class GunzipContentProvider implements Content.Provider
{
    private final GZIPContentDecoder decoder = new GZIPContentDecoder();
    private final Content.Provider deflatedProvider;

    private Content currentDeflatedContent;
    private ByteBuffer inflatedBuffer;

    public GunzipContentProvider(Content.Provider deflatedProvider)
    {
        this.deflatedProvider = deflatedProvider;
    }

    @Override
    public Content readContent()
    {
        while (true)
        {
            if (currentDeflatedContent == null)
            {
                currentDeflatedContent = deflatedProvider.readContent();
                if (currentDeflatedContent == null)
                    return null;
            }
            if (currentDeflatedContent.isSpecial())
                return currentDeflatedContent;

            if (inflatedBuffer == null || BufferUtil.isEmpty(inflatedBuffer))
                inflateNextBufferIfNeeded();
            else
                break;
        }

        return new InflatedContent(inflatedBuffer, this::inflateNextBufferIfNeeded);
    }

    /**
     * Can only be called when this.inflatedBuffer is either null or empty.
     */
    private void inflateNextBufferIfNeeded()
    {
        if (currentDeflatedContent == null)
            throw new IllegalStateException();
        if (inflatedBuffer != null)
        {
            if (!BufferUtil.isEmpty(inflatedBuffer))
                throw new IllegalStateException();
            decoder.release(inflatedBuffer);
            inflatedBuffer = null;
        }

        inflatedBuffer = decoder.decode(currentDeflatedContent.getByteBuffer());
        if (BufferUtil.isEmpty(inflatedBuffer))
        {
            decoder.release(inflatedBuffer);
            inflatedBuffer = null;
            currentDeflatedContent.release();
            currentDeflatedContent = null;
        }
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
        deflatedProvider.demandContent(onContentAvailable);
    }


    private static class InflatedContent extends Content.Abstract
    {
        private final ByteBuffer buffer;
        private final Runnable onRelease;

        public InflatedContent(ByteBuffer buffer, Runnable onRelease)
        {
            super(false, false);
            this.buffer = buffer;
            this.onRelease = onRelease;
        }

        @Override
        public void release()
        {
            if (!isEmpty())
                throw new IllegalStateException();
            onRelease.run();
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return buffer;
        }
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " deflating from " + deflatedProvider;
    }
}
