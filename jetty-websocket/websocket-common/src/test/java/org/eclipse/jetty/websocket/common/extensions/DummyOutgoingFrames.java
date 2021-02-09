//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.extensions;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;

/**
 * Dummy implementation of {@link OutgoingFrames} used for testing
 */
public class DummyOutgoingFrames implements OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(DummyOutgoingFrames.class);
    private final String id;

    public DummyOutgoingFrames(String id)
    {
        this.id = id;
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        LOG.debug("outgoingFrame({},{})", frame, callback);
        if (callback != null)
        {
            callback.writeSuccess();
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]", this.getClass().getSimpleName(), hashCode(), id);
    }
}
