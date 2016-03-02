//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.frames;

import java.util.Map;

public class SettingsFrame extends Frame
{
    public static final int HEADER_TABLE_SIZE = 1;
    public static final int ENABLE_PUSH = 2;
    public static final int MAX_CONCURRENT_STREAMS = 3;
    public static final int INITIAL_WINDOW_SIZE = 4;
    public static final int MAX_FRAME_SIZE = 5;
    public static final int MAX_HEADER_LIST_SIZE = 6;
    
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
        return String.format("%s,reply=%b:%s", super.toString(), reply, settings);
    }
}
