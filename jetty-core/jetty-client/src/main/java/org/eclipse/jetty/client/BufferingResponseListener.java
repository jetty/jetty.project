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

import org.eclipse.jetty.client.Response.Listener;
import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * <p>Implementation of {@link Listener} that buffers the response content
 * by copying it up to a maximum length specified to the constructors.</p>
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via {@link #getContent()} or {@link #getContentAsString()}.</p>
 * <p>Instances of this class are not reusable, so one must be allocated for each request.</p>
 * <p>Use {@link RetainingResponseListener} for a more efficient
 * implementation that does not copy the content bytes.</p>
 */
public abstract class BufferingResponseListener extends AbstractResponseListener
{
    /**
     * Creates an instance with a default maximum length of 2 MiB.
     */
    public BufferingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    /**
     * Creates an instance with the given maximum length
     *
     * @param maxLength the maximum length of the content
     */
    public BufferingResponseListener(int maxLength)
    {
        // A DynamicCapacity that always copies.
        super(new RetainableByteBuffer.DynamicCapacity(null, maxLength, Integer.MAX_VALUE));
    }
}
