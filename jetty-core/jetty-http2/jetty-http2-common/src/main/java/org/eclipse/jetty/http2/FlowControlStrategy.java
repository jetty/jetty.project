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

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;

public interface FlowControlStrategy
{
    public static int DEFAULT_WINDOW_SIZE = 65535;

    public void onStreamCreated(Stream stream);

    public void onStreamDestroyed(Stream stream);

    public void updateInitialStreamWindow(Session session, int initialStreamWindow, boolean local);

    public void onWindowUpdate(Session session, Stream stream, WindowUpdateFrame frame);

    public void onDataReceived(Session session, Stream stream, int length);

    public void onDataConsumed(Session session, Stream stream, int length);

    public void windowUpdate(Session session, Stream stream, WindowUpdateFrame frame);

    public void onDataSending(Stream stream, int length);

    public void onDataSent(Stream stream, int length);

    public interface Factory
    {
        public FlowControlStrategy newFlowControlStrategy();
    }
}
