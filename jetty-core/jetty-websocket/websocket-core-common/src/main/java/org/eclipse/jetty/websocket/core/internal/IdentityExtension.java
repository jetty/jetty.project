//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

@ManagedObject("Identity Extension")
public class IdentityExtension extends AbstractExtension
{
    private String id;

    public String getParam(String key)
    {
        return getConfig().getParameter(key, "?");
    }

    @Override
    public String getName()
    {
        return "identity";
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        // pass through
        nextIncomingFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        // pass through
        nextOutgoingFrame(frame, callback, batch);
    }

    @Override
    public void init(ExtensionConfig config, WebSocketComponents components)
    {
        super.init(config, components);

        StringBuilder s = new StringBuilder();
        s.append(config.getName());
        s.append("@").append(Integer.toHexString(hashCode()));
        s.append("[");
        boolean delim = false;
        for (String param : config.getParameterKeys())
        {
            if (delim)
            {
                s.append(';');
            }
            s.append(param).append('=').append(QuotedStringTokenizer.quoteIfNeeded(config.getParameter(param, ""), ";="));
            delim = true;
        }
        s.append("]");
        id = s.toString();
    }

    @Override
    public String toString()
    {
        return id;
    }
}
