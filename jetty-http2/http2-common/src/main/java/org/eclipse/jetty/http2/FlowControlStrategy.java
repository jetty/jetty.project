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

package org.eclipse.jetty.http2;

import org.eclipse.jetty.http2.frames.WindowUpdateFrame;

public interface FlowControlStrategy
{
    int DEFAULT_WINDOW_SIZE = 65535;

    void onStreamCreated(IStream stream);

    void onStreamDestroyed(IStream stream);

    void updateInitialStreamWindow(ISession session, int initialStreamWindow, boolean local);

    void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame);

    void onDataReceived(ISession session, IStream stream, int length);

    void onDataConsumed(ISession session, IStream stream, int length);

    void windowUpdate(ISession session, IStream stream, WindowUpdateFrame frame);

    void onDataSending(IStream stream, int length);

    void onDataSent(IStream stream, int length);

    interface Factory
    {
        FlowControlStrategy newFlowControlStrategy();
    }
}
