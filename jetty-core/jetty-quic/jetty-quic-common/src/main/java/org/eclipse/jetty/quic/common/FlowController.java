//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common;

import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;

/// The logic that controls when to send data flow.
///
/// Implementation decide when [MaxDataFrame]s and [StreamMaxDataFrame]s,
/// as well as [DataBlockedFrame]s and [StreamDataBlockedFrame]s.
public interface FlowController
{
    /// Callback method invoked when a stream is created.
    void onStreamCreated(Stream stream);

    /// Callback method invoked when a stream is terminated.
    void onStreamTerminated(Stream stream);

    void onDataReceived(Stream stream);

    void onDataRead(Stream stream, long length);

    interface Factory
    {
        FlowController newFlowController();
    }
}
