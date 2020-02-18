//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.util;

import java.util.concurrent.Future;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows events to a {@link WriteCallback} to drive a {@link Future} for the user.
 */
public class FutureWriteCallback extends FutureCallback implements WriteCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(FutureWriteCallback.class);

    @Override
    public void writeFailed(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".writeFailed", cause);
        failed(cause);
    }

    @Override
    public void writeSuccess()
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".writeSuccess");
        succeeded();
    }
}
