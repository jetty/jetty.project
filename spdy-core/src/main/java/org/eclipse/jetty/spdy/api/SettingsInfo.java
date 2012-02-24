/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.api;

public class SettingsInfo
{
    public static final byte CLEAR_PERSISTED = 1;

    private final Settings settings;
    private final boolean clearPersisted;

    public SettingsInfo(Settings settings)
    {
        this(settings, false);
    }

    public SettingsInfo(Settings settings, boolean clearPersisted)
    {
        this.settings = settings;
        this.clearPersisted = clearPersisted;
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
