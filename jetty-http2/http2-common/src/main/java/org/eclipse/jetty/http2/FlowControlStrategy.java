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

    public interface Factory
    {
        public FlowControlStrategy newFlowControlStrategy();
    }
}
