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

/// The close states an entity can go through.
public enum CloseState
{
    /// Not closed.
    NOT_CLOSED,
    /// Locally closing.
    ///
    /// This is when a peer initiated the closing,
    /// but the closing has not completed yet.
    LOCALLY_CLOSING,
    /// Locally closed.
    ///
    /// This is when the local closing initiated
    /// by a peer is completed.
    LOCALLY_CLOSED,
    /// Remotely closed.
    ///
    /// This is when a peer received the closing
    /// from the remote peer.
    REMOTELY_CLOSED,
    /// Closing.
    ///
    /// This is the combination of [#LOCALLY_CLOSING]
    /// with [#REMOTELY_CLOSED].
    CLOSING,
    /// Closed.
    ///
    /// This is when the entity is both
    /// [#LOCALLY_CLOSED] and [#REMOTELY_CLOSED].
    CLOSED
}
