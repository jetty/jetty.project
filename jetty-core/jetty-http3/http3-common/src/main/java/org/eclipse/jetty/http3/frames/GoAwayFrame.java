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

package org.eclipse.jetty.http3.frames;

public class GoAwayFrame extends Frame
{
    public static final GoAwayFrame CLIENT_GRACEFUL = new GoAwayFrame((1L << 62) - 1);
    public static final GoAwayFrame SERVER_GRACEFUL = new GoAwayFrame((1L << 62) - 4);

    private final long lastId;

    public GoAwayFrame(long lastId)
    {
        super(FrameType.GOAWAY);
        this.lastId = lastId;
    }

    public long getLastId()
    {
        return lastId;
    }

    public boolean isGraceful()
    {
        return lastId == CLIENT_GRACEFUL.lastId || lastId == SERVER_GRACEFUL.lastId;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[lastId=%d,graceful=%b]", getClass().getSimpleName(), hashCode(), getLastId(), isGraceful());
    }
}
