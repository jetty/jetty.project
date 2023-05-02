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

package org.eclipse.jetty.websocket.tests.util;

import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.api.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows events to a {@link Callback} to drive a {@link Future} for the user.
 */
public class FutureCallback extends org.eclipse.jetty.util.FutureCallback implements Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(FutureCallback.class);

    @Override
    public void fail(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".writeFailed", cause);
        failed(cause);
    }

    @Override
    public void succeed()
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".writeSuccess");
        succeeded();
    }
}
