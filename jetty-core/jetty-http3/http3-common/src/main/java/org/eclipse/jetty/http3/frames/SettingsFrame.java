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

import java.util.Map;

public class SettingsFrame extends Frame
{
    public static final long MAX_FIELD_SECTION_SIZE = 0x06;

    public static boolean isReserved(long key)
    {
        return key >= 0 && key <= 5;
    }

    private final Map<Long, Long> settings;

    public SettingsFrame(Map<Long, Long> settings)
    {
        super(FrameType.SETTINGS);
        this.settings = settings;
    }

    public Map<Long, Long> getSettings()
    {
        return settings;
    }

    @Override
    public String toString()
    {
        return String.format("%s,settings=%s", super.toString(), settings);
    }
}
