//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

/**
 * AbstractSessionDataStoreFactory
 */
public abstract class AbstractSessionDataStoreFactory implements SessionDataStoreFactory
{

    int _gracePeriodSec = AbstractSessionDataStore.DEFAULT_GRACE_PERIOD_SEC;
    int _savePeriodSec = AbstractSessionDataStore.DEFAULT_SAVE_PERIOD_SEC;

    /**
     * @return the gracePeriodSec
     */
    public int getGracePeriodSec()
    {
        return _gracePeriodSec;
    }

    /**
     * @param gracePeriodSec the gracePeriodSec to set
     */
    public void setGracePeriodSec(int gracePeriodSec)
    {
        _gracePeriodSec = gracePeriodSec;
    }

    /**
     * @return the savePeriodSec
     */
    public int getSavePeriodSec()
    {
        return _savePeriodSec;
    }

    /**
     * @param savePeriodSec the savePeriodSec to set
     */
    public void setSavePeriodSec(int savePeriodSec)
    {
        _savePeriodSec = savePeriodSec;
    }
}
