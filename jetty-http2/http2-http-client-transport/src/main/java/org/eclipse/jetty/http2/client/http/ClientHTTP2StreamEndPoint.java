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

package org.eclipse.jetty.http2.client.http;

import org.eclipse.jetty.http2.HTTP2Channel;
import org.eclipse.jetty.http2.HTTP2StreamEndPoint;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ClientHTTP2StreamEndPoint extends HTTP2StreamEndPoint implements HTTP2Channel.Client
{
    private static final Logger LOG = Log.getLogger(ClientHTTP2StreamEndPoint.class);

    public ClientHTTP2StreamEndPoint(IStream stream)
    {
        super(stream);
    }

    @Override
    public void onData(DataFrame frame, Callback callback)
    {
        offerData(frame, callback);
    }

    @Override
    public boolean onTimeout(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout on {}: {}", this, failure);
        Connection connection = getConnection();
        if (connection != null)
            return connection.onIdleExpired();
        return true;
    }

    @Override
    public void onFailure(Throwable failure, Callback callback)
    {
        // TODO
        callback.succeeded();
    }
}
