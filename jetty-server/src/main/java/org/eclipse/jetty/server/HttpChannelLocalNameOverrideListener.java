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

package org.eclipse.jetty.server;

import org.eclipse.jetty.io.Connection;

/**
 * Sets the {@link HttpChannel#setLocalNameOverride(String)} on all newly opened connections.
 */
public class HttpChannelLocalNameOverrideListener implements Connection.Listener
{
    private final String localNameOverride;

    public HttpChannelLocalNameOverrideListener(String localNameOverride)
    {
        this.localNameOverride = localNameOverride;
    }

    @Override
    public void onOpened(Connection connection)
    {
        if (connection instanceof HttpConnection)
        {
            HttpChannel httpChannel = ((HttpConnection)connection).getHttpChannel();
            httpChannel.setLocalNameOverride(localNameOverride);
        }
    }

    @Override
    public void onClosed(Connection connection)
    {
        // ignored
    }
}
