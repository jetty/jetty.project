//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;

/**
 * Dummy implementation of {@link IncomingFrames} used for testing
 */
public class DummyIncomingFrames implements IncomingFrames
{
    private static final Logger LOG = Log.getLogger(DummyIncomingFrames.class);
    private final String id;

    public DummyIncomingFrames(String id)
    {
        this.id = id;
    }

    @Override
    public void incomingError(Throwable e)
    {
        LOG.debug("incomingError()",e);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        LOG.debug("incomingFrame({})",frame);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]",this.getClass().getSimpleName(),hashCode(),id);
    }
}
