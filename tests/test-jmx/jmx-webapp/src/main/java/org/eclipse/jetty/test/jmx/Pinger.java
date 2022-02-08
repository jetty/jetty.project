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

package org.eclipse.jetty.test.jmx;

import java.util.Date;

/**
 * Bare POJO, intentionally has no managed annotations.
 */
public class Pinger
{
    private int count = 0;

    public int getCount()
    {
        return count;
    }

    public String ping()
    {
        count++;
        return "Ponger at " + new Date().toString();
    }
}
