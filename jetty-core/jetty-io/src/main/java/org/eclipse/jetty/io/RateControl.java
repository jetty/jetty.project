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

package org.eclipse.jetty.io;

/**
 * Controls rate of events via {@link #onEvent(Object)}.
 */
public interface RateControl
{
    RateControl NO_RATE_CONTROL = event -> true;

    /**
     * <p>Applications should call this method when they want to signal an
     * event that is subject to rate control.</p>
     * <p>Implementations should return true if the event does not exceed
     * the desired rate, or false to signal that the event exceeded the
     * desired rate.</p>
     *
     * @param event the event subject to rate control.
     * @return true IFF the rate is within limits
     */
    boolean onEvent(Object event);

    /**
     * Factory to create {@link RateControl} instances.
     */
    interface Factory
    {
        /**
         * @return a new RateControl instance for the given EndPoint
         * @param endPoint the EndPoint for which the RateControl is created
         */
        default RateControl newRateControl(EndPoint endPoint)
        {
            return NO_RATE_CONTROL;
        }
    }
}
