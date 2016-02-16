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

package org.eclipse.jetty.websocket.common.extensions.identity;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

@ManagedObject("Identity Extension")
public class IdentityExtension extends AbstractExtension
{
    private String id;

    public String getParam(String key)
    {
        return getConfig().getParameter(key,"?");
    }
    
    @Override
    public String getName()
    {
        return "identity";
    }

    @Override
    public void incomingError(Throwable e)
    {
        // pass through
        nextIncomingError(e);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        // pass through
        nextIncomingFrame(frame);
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        // pass through
        nextOutgoingFrame(frame,callback, batchMode);
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);
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
            s.append(param).append('=').append(QuotedStringTokenizer.quoteIfNeeded(config.getParameter(param,""),";="));
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
