//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
    public static int DEFAULT_WINDOW_SIZE = 65535;

    public void onStreamCreated(IStream stream);

    public void onStreamDestroyed(IStream stream);

    public void updateInitialStreamWindow(ISession session, int initialStreamWindow, boolean local);

    public void onWindowUpdate(ISession session, IStream stream, WindowUpdateFrame frame);

    public void onDataReceived(ISession session, IStream stream, int length);

    public void onDataConsumed(ISession session, IStream stream, int length);

    public void windowUpdate(ISession session, IStream stream, WindowUpdateFrame frame);

    public void onDataSending(IStream stream, int length);

    public void onDataSent(IStream stream, int length);

    public void onSessionStalled(ISession session);

    public void onStreamStalled(IStream stream);
}
