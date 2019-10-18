//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

/**
 * Fragment Extension
 */
public class FragmentExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(FragmentExtension.class);

    private final FragmentingFlusher flusher;
    private final FrameHandler.Configuration configuration = new FrameHandler.ConfigurationHolder();

    public FragmentExtension()
    {
        flusher = new FragmentingFlusher(configuration)
        {
            @Override
            void forwardFrame(Frame frame, Callback callback, boolean batch)
            {
                nextOutgoingFrame(frame, callback, batch);
            }
        };
    }

    @Override
    public String getName()
    {
        return "fragment";
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        nextIncomingFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        if (OpCode.isControlFrame(frame.getOpCode()))
        {
            nextOutgoingFrame(frame, callback, batch);
            return;
        }

        flusher.sendFrame(frame, callback, batch);
    }

    @Override
    public void init(ExtensionConfig config, WebSocketComponents components)
    {
        super.init(config, components);
        int maxLength = config.getParameter("maxLength", -1);
        configuration.setMaxFrameSize(maxLength);
    }
}
