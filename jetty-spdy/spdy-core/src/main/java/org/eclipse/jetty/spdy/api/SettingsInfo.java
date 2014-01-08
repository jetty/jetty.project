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

package org.eclipse.jetty.spdy.api;

import java.util.concurrent.TimeUnit;

public class SettingsInfo extends Info
{
    public static final byte CLEAR_PERSISTED = 1;

    private final Settings settings;
    private final boolean clearPersisted;

    public SettingsInfo(Settings settings)
    {
        this(0, TimeUnit.SECONDS, settings, false);
    }

    public SettingsInfo(long timeout, TimeUnit unit, Settings settings, boolean clearPersisted)
    {
        super(timeout, unit);
        this.settings = settings;
        this.clearPersisted = clearPersisted;
    }

    public SettingsInfo(Settings settings, boolean clearPersisted)
    {
        this(0, TimeUnit.SECONDS, settings, clearPersisted);
    }

    public boolean isClearPersisted()
    {
        return clearPersisted;
    }

    public byte getFlags()
    {
        return isClearPersisted() ? CLEAR_PERSISTED : 0;
    }

    public Settings getSettings()
    {
        return settings;
    }
}
