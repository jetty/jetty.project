//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.api;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;

public interface Stream
{
    public int getId();

    public Session getSession();

    public void headers(HeadersFrame frame, Callback callback);

    public void data(DataFrame frame, Callback callback);

    // TODO: see SPDY's Stream

    public interface Listener
    {
        public void onData(Stream stream, DataFrame frame);

        public void onFailure(Stream stream, Throwable x);

        // TODO: See SPDY's StreamFrameListener

        public static class Adapter implements Listener
        {
            @Override
            public void onData(Stream stream, DataFrame frame)
            {

            }

            @Override
            public void onFailure(Stream stream, Throwable x)
            {

            }
        }
    }
}
