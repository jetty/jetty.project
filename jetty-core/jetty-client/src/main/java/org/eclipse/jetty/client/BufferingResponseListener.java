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

import org.eclipse.jetty.util.buffer.BufferAggregator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// Implementation of [AbstractResponseListener] that aggregates by
/// copying the response content buffers up to a configurable maximum length.
///
/// The memory retained by this class is bound by the
/// maximum length, at the cost of copying the content buffers.
///
/// Use [RetainingResponseListener] for an alternative
/// implementation that does not copy the content buffers.
public abstract class BufferingResponseListener extends AbstractResponseListener
{
    private final BufferAggregator aggregator;

    /// Creates an instance with a default maximum length of 2 MiB.
    public BufferingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    /// Creates an instance with the given maximum length
    ///
    /// @param maxLength the maximum length of the content
    public BufferingResponseListener(int maxLength)
    {
        super(maxLength);
        aggregator = new BufferAggregator(null, false, true, Math.min(1024, maxLength), maxLength);
    }

    @Override
    public void onContent(Response response, RetainableByteBuffer content)
    {
        super.onContent(response, content);
        aggregator.append(content);
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        super.onFailure(response, failure);
        aggregator.close();
    }

    @Override
    protected RetainableByteBuffer take()
    {
        return aggregator.take();
    }
}
