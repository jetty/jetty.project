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

package org.eclipse.jetty.http2;

import org.eclipse.jetty.io.EndPoint;

/**
 * Controls rate of events via {@link #onEvent(Object)}.
 *
 * @deprecated use {@link org.eclipse.jetty.io.RateControl} instead.
 */
@Deprecated(since = "12.1.11", forRemoval = true)
public interface RateControl extends org.eclipse.jetty.io.RateControl
{
    RateControl NO_RATE_CONTROL = event -> true;

    /**
     * @deprecated use {@link org.eclipse.jetty.io.RateControl.Factory} instead.
     */
    @Deprecated(since = "12.1.11", forRemoval = true)
    interface Factory extends org.eclipse.jetty.io.RateControl.Factory
    {
        default RateControl newRateControl(EndPoint endPoint)
        {
            return NO_RATE_CONTROL;
        }
    }
}
