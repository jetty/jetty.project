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
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via {@link #getContent()} or {@link #getContentAsString()}.</p>
 * <p>Instances of this class are not reusable, so one must be allocated for each request.</p>
 * <p>The implementation retains the response content chunks, and converts them into a {@code byte[]}
 * upon response success, returned by {@link #getContent()}.</p>
 * <p>Subclasses overriding {@link #onSuccess(Response)} or {@link #onFailure(Response, Throwable)}
 * must always call the {@code super} method to guarantee that the retained chunks are released.</p>
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
