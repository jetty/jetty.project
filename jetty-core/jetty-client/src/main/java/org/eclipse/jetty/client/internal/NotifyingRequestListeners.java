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

package org.eclipse.jetty.client.internal;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.RequestListeners;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/**
 * <p>An extension of {@link RequestListeners} that can notify request events.</p>
 */
public class NotifyingRequestListeners extends RequestListeners
{
    public void notifyQueued(Request request)
    {
        notifyQueued(getQueuedListener(), request);
    }

    public void notifyBegin(Request request)
    {
        notifyBegin(getBeginListener(), request);
    }

    public void notifyHeaders(Request request)
    {
        notifyHeaders(getHeadersListener(), request);
    }

    public void notifyCommit(Request request)
    {
        notifyCommit(getCommitListener(), request);
    }

    public void notifyContent(Request request, RetainableByteBuffer buffer)
    {
        notifyContent(getContentListener(), request, buffer);
    }

    public void notifySuccess(Request request)
    {
        notifySuccess(getSuccessListener(), request);
    }

    public void notifyFailure(Request request, Throwable failure)
    {
        notifyFailure(getFailureListener(), request, failure);
    }
}
