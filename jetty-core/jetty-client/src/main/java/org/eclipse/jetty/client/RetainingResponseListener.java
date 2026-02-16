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
 * <p>Implementation of {@link AbstractResponseListener} that retains the response
 * content without copying it, up to a configurable number of bytes.</p>
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
}
