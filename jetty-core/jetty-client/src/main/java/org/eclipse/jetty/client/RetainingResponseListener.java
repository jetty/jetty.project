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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>Implementation of {@link Response.Listener} that retains response content up to a configurable number of bytes.</p>
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via {@link #getContent()} or {@link #getContentAsString()}.</p>
 * <p>Instances of this class are not reusable, so one must be allocated for each request.</p>
 * <p>The implementation retains the response content chunks, and converts them into a {@code byte[]}
 * upon response success, returned by {@link #getContent()}.</p>
 */
public abstract class RetainingResponseListener extends AbstractResponseListener
{
    private final RetainableByteBuffer.DynamicCapacity accumulator;
    private byte[] content;

    public RetainingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    public RetainingResponseListener(int maxLength)
    {
        super(maxLength);
        this.accumulator = new RetainableByteBuffer.DynamicCapacity(null, maxLength, 0);
    }

    @Override
    public void onContent(Response response, Content.Chunk chunk, Runnable demander) throws Exception
    {
        chunk.retain();
        accumulator.add(chunk);
        demander.run();
    }

    @Override
    public void onSuccess(Response response)
    {
        content = accumulator.takeByteArray();
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        accumulator.clear();
    }

    @Override
    public abstract void onComplete(Result result);

    /**
     * @return the content as a byte array.
     * @see #getContentAsString()
     */
    public byte[] getContent()
    {
        return content == null ? BufferUtil.EMPTY_BYTES : content;
    }

    /**
     * @return the content as a string, using the "Content-Type" header to detect
     * the encoding or defaulting to UTF-8 if the encoding could not be detected.
     * @see #getContentAsString(String)
     */
    public String getContentAsString()
    {
        String encoding = getEncoding();
        if (encoding == null)
            return getContentAsString(StandardCharsets.UTF_8);
        return getContentAsString(encoding);
    }

    /**
     * @param encoding the encoding of the content bytes
     * @return the content as a string, with the specified encoding
     * @see #getContentAsString()
     */
    public String getContentAsString(String encoding)
    {
        return getContentAsString(Charset.forName(encoding));
    }

    /**
     * @param charset the charset of the content bytes
     * @return the content as a string, with the specified charset
     * @see #getContentAsString()
     */
    public String getContentAsString(Charset charset)
    {
        return new String(getContent(), charset);
    }
}
