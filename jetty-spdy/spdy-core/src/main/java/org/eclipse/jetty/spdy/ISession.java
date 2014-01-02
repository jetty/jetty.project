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

package org.eclipse.jetty.spdy;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.util.Callback;

public interface ISession extends Session
{
    public void control(IStream stream, ControlFrame frame, long timeout, TimeUnit unit, Callback callback);

    public void data(IStream stream, DataInfo dataInfo, long timeout, TimeUnit unit, Callback callback);

    /**
     * <p>Gracefully shuts down this session.</p>
     * <p>A special item is queued that will close the connection when it will be dequeued.</p>
     */
    public void shutdown();
}
