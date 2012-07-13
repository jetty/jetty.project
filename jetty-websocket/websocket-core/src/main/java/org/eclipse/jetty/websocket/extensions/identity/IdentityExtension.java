// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.extensions.identity;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class IdentityExtension extends Extension
{
    private String id;

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);
        StringBuilder s = new StringBuilder();
        s.append(config.getName());
        s.append("[");
        for (String param : config.getParameterKeys())
        {
            s.append(';').append(param).append('=').append(QuotedStringTokenizer.quoteIfNeeded(config.getParameter(param,""),";="));
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
