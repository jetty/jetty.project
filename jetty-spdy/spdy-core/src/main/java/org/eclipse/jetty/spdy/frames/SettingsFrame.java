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

package org.eclipse.jetty.spdy.frames;

import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.api.SettingsInfo;

public class SettingsFrame extends ControlFrame
{
    private final Settings settings;

    public SettingsFrame(short version, byte flags, Settings settings)
    {
        super(version, ControlFrameType.SETTINGS, flags);
        this.settings = settings;
    }

    public boolean isClearPersisted()
    {
        return (getFlags() & SettingsInfo.CLEAR_PERSISTED) == SettingsInfo.CLEAR_PERSISTED;
    }

    public Settings getSettings()
    {
        return settings;
    }

    @Override
    public String toString()
    {
        return String.format("%s clear_persisted=%b settings=%s", super.toString(), isClearPersisted(), getSettings());
    }
}
