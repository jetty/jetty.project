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

import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// Implementation of [AbstractResponseListener] that accumulates by
/// retaining the response content buffers up to a configurable maximum length.
///
/// The memory retained by this class may be larger than the
/// maximum length, but saves the cost of copying the content buffers.
/// A server may send response content in small chunks that when
/// retained pin the larger network buffer used by the client.
///
/// Use [BufferingResponseListener] for an alternative
/// implementation that copies the content buffers and
/// retains less memory.
public abstract class RetainingResponseListener extends AbstractResponseListener
{
    private final BufferAccumulator accumulator = new BufferAccumulator();

    public RetainingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    public RetainingResponseListener(int maxLength)
    {
        super(maxLength);
    }

    @Override
    public void onContent(Response response, RetainableByteBuffer content)
    {
        super.onContent(response, content);
        accumulator.add(content);
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        super.onFailure(response, failure);
        accumulator.clear();
    }

    @Override
    protected RetainableByteBuffer take()
    {
        return accumulator.take();
    }
}
