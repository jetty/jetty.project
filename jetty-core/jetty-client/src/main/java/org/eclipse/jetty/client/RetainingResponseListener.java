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
 * via one of the {@code getContent*()} methods.</p>
 * <p>If {@link #getContent()} or {@link #getContentAsString()} is called first, then the content is copied
 * into a {@code byte[]} and all further calls will read this {@code byte[]}, while if
 * {@link #getContentAsInputStream()} or {@link #getContentAsContentSource()} is called first, the content will be
 * read without copying it, but further {@code getContent*()} calls will see an empty content.</p>
 * <p>ATTENTION: {@link #onSuccess(Response)} is overridden to avoid copying the contents into a {@code byte[]},
 * so this means the contents MUST be consumed by calling one of the {@code getContent*()} methods
 * otherwise the backing buffers will be leaked. If a streaming method like {@link #getContentAsInputStream()}
 * or {@link #getContentAsContentSource()} is called, the contents MUST be read until the end
 * (or closed/failed appropriately) otherwise the backing buffers of the unread content will also be leaked.</p>
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
