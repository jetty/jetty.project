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

public class NewRenoCongestionControllerFactory implements CongestionController.Factory
{
    @Override
    public CongestionController newCongestionController()
    {
        return new NewRenoCongestionController();
    }

    // TODO: implement this.
    private static class NewRenoCongestionController implements CongestionController
    {
        @Override
        public void onPacketSent(Packet.WithFrames packet, long length, RTTData rttData)
        {
        }

        @Override
        public void onPacketsAcknowledged(List<Packet.WithFrames> packets, long totalLength, RTTData rttData)
        {
        }

        @Override
        public void onPacketsLost(List<Packet.WithFrames> packets, long totalLength, RTTData rttData)
        {
        }

        @Override
        public long getCongestionWindow()
        {
            return Integer.MAX_VALUE;
        }

        @Override
        public long getPacingDelay()
        {
            return 0;
        }
    }
}
