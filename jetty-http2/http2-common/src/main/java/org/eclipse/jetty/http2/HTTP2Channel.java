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

package org.eclipse.jetty.http2;

import java.util.function.Consumer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;

public interface HTTP2Channel
{
    public interface Client
    {
        public void onData(DataFrame frame, Callback callback);

        public boolean onTimeout(Throwable failure);

        public void onFailure(Throwable failure, Callback callback);
    }

    public interface Server
    {
        public Runnable onData(DataFrame frame, Callback callback);

        public Runnable onTrailer(HeadersFrame frame);

        public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer);

        public Runnable onFailure(Throwable failure, Callback callback);

        public boolean isIdle();
    }
}
