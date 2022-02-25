//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.spi.util;

/**
 * This class holds the default pool constants
 */
public enum Pool
{

    DEFAULT_SIZE(0),

    CORE_POOL_SIZE(15),

    MAXIMUM_POOL_SIZE(20),

    KEEP_ALIVE_TIME(300),

    DEFAULT_WORK_QUEUE_SIZE(20);

    private final int value;

    private Pool(int value)
    {
        this.value = value;
    }

    public int getValue()
    {
        return value;
    }
}
