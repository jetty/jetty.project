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
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;

/// The logic that controls when to send [MaxStreamsFrame]s.
public interface StreamsController
{
    /// Callback method invoked when a stream is created.
    void onStreamCreated(Stream stream);

    /// Callback method invoked when a stream is terminated.
    void onStreamTerminated(Stream stream);

    /// A factory for [StreamsController]s.
    interface Factory
    {
        StreamsController newStreamsController();
    }
}
