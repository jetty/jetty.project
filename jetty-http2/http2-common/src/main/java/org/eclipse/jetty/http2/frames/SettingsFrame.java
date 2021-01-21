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

package org.eclipse.jetty.http2.frames;

import java.util.Map;

public class SettingsFrame extends Frame
{
    public static final int DEFAULT_MAX_KEYS = 64;

    public static final int HEADER_TABLE_SIZE = 1;
    public static final int ENABLE_PUSH = 2;
    public static final int MAX_CONCURRENT_STREAMS = 3;
    public static final int INITIAL_WINDOW_SIZE = 4;
    public static final int MAX_FRAME_SIZE = 5;
    public static final int MAX_HEADER_LIST_SIZE = 6;
    public static final int ENABLE_CONNECT_PROTOCOL = 8;

    private final Map<Integer, Integer> settings;
    private final boolean reply;

    public SettingsFrame(Map<Integer, Integer> settings, boolean reply)
    {
        super(FrameType.SETTINGS);
        this.settings = settings;
        this.reply = reply;
    }

    public Map<Integer, Integer> getSettings()
    {
        return settings;
    }

    public boolean isReply()
    {
        return reply;
    }

    @Override
    public String toString()
    {
        return String.format("%s,reply=%b,params=%s", super.toString(), reply, settings);
    }
}
