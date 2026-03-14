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

import org.eclipse.jetty.util.TypeUtil;

/// Captures RTT data calculated by [PacketTracker].
///
/// @param latestRTT the latest RTT in nanoseconds
/// @param minimumRTT the minimum RTT in nanoseconds
/// @param smoothedRTT the smoothed RTT in nanoseconds
/// @param variationRTT the variation RTT in nanoseconds
public record RTTData(long latestRTT, long minimumRTT, long smoothedRTT, long variationRTT)
{
    @Override
    public String toString()
    {
        return "%s@%x[l=%d,m=%d,s=%d,v=%d]".formatted(
            TypeUtil.toShortName(getClass()),
            hashCode(),
            latestRTT, minimumRTT, smoothedRTT, variationRTT
        );
    }
}
