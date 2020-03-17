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

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fragment Extension
 */
public class FragmentExtension extends AbstractExtension
{
    private static final Logger LOG = LoggerFactory.getLogger(FragmentExtension.class);

    private final FragmentingFlusher flusher;
    private final Configuration configuration = new Configuration.ConfigurationCustomizer();

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
