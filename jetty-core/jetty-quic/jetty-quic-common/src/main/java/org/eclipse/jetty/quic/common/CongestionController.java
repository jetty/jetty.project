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

import java.util.List;

import org.eclipse.jetty.quic.common.packets.Packet;

/// A congestion controller implements the algorithm that
/// regulates the traffic of data sent over the network.
///
/// A congestion controller prevents committing too
/// much data to the network, which reduces data loss
/// and prevents network collapse due to congestion.
public interface CongestionController
{
    /// Method invoked when a packet is sent.
    ///
    /// @param packet the packet being sent
    /// @param length the length in bytes of the packet being sent
    /// @param dataStalled whether there is more data to send
    /// @param rttData the round trip time data
    void onPacketSent(Packet.WithFrames packet, long length, boolean dataStalled, RTTData rttData);

    /// Method invoked when acknowledgments are received.
    ///
    /// @param packets the packets being acknowledged
    /// @param rttData the round trip time data
    void onPacketsAcknowledged(List<Packet.WithFrames> packets, RTTData rttData);

    /// Method invoked when packets are lost.
    ///
    /// @param packets the packets being lost
    /// @param rttData the round trip time data
    void onPacketsLost(List<Packet.WithFrames> packets, RTTData rttData);

    /// Method invoked when there are no packets to send.
    void onIdle();

    /// The congestion window is the number of bytes that
    /// can be sent without waiting for acknowledgments.
    ///
    /// @return the congestion window in bytes
    long getCongestionWindow();

    /// The pacing delay is the time to wait between each packet send.
    /// The delay avoids committing too much data to the network,
    /// which reduces packet loss and prevents network collapse.
    ///
    /// @return the pacing delay in nanoseconds
    long getPacingDelay();

    /// A factory for creating [CongestionController] instances.
    interface Factory
    {
        /// @return a new [CongestionController] instance
        CongestionController newCongestionController();
    }
}
