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
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;

/// The logic that controls when to update the session and stream max data.
///
/// Implementations decide when to send [MaxDataFrame]s and [StreamMaxDataFrame]s.
public interface FlowController
{
    /// Callback method invoked when a stream is created.
    void onStreamCreated(Stream stream);

    /// Callback method invoked when a stream is terminated.
    void onStreamTerminated(Stream stream);

    /// Callback method invoked when data is received.
    void onDataReceived(Stream stream);

    /// Callback method invoked when data is read.
    void onDataRead(Stream stream, long length);

    /// The factory for [FlowController] instances.
    interface Factory
    {
        /// Creates a new [FlowController] instance.
        FlowController newFlowController();
    }
}
