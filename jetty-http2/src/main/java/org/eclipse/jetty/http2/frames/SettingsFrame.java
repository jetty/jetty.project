//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

public class SettingsFrame
{
    private final Map<Integer, Integer> settings;
    private final boolean reply;

    public SettingsFrame(Map<Integer, Integer> settings, boolean reply)
    {
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
}
