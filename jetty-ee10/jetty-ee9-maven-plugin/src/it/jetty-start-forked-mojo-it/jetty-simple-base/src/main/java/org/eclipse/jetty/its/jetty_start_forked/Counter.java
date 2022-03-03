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

package org.eclipse.jetty.its.jetty_start_forked;

@SuppressWarnings("serial")
public class Counter implements java.io.Serializable
{
    int counter = 0;
    String last;

    public int getCount()
    {
        counter++;
        return counter;
    }

    public void setLast(String uri)
    {
        last = uri;
    }

    public String getLast()
    {
        return last;
    }
}

