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

import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * <p>Implementation of {@link Response.Listener} that retains the response
 * content without copying it, up to a configurable number of bytes.</p>
 * <p>Instances of this class are not reusable, so one must be allocated for each request.</p>
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via one of the {@code getContent*()} or {@code takeContent*()} methods.</p>
 * <p>IMPORTANT: {@link #onSuccess(Response)} is overridden to avoid copying the contents into a {@code byte[]},
 * so this means the contents MUST be consumed by calling one of the {@code getContent*()} or
 * {@code takeContent*()} methods otherwise the backing buffers may be leaked. If a streaming method like
 * {@link #takeContentAsInputStream()} or {@link #takeContentAsContentSource()} is called, the content
 * MUST be read until the end (or closed/failed appropriately) otherwise the backing buffers of the unread
 * content may also be leaked.</p>
 */
public abstract class RetainingResponseListener extends AbstractResponseListener
{
    public RetainingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    public RetainingResponseListener(int maxLength)
    {
        // A DynamicCapacity that always retains.
        super(new RetainableByteBuffer.DynamicCapacity(null, maxLength, 0));
    }

    @Override
    public void onSuccess(Response response)
    {
        // Make sure the content is not taken on success.
    }
}
