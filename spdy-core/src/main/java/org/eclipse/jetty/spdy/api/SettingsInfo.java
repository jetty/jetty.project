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

import java.util.Map;

public class SettingsInfo
{
    public static final byte CLEAR_PERSISTED = 1;

    private final Map<Key, Integer> settings;
    private final boolean clearPersisted;

    public SettingsInfo(Map<Key, Integer> settings)
    {
        this(settings, false);
    }

    public SettingsInfo(Map<Key, Integer> settings, boolean clearPersisted)
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

    public Map<Key, Integer> getSettings()
    {
        return settings;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        SettingsInfo that = (SettingsInfo)obj;
        return settings.equals(that.settings) && clearPersisted == that.clearPersisted;
    }

    @Override
    public int hashCode()
    {
        int result = settings.hashCode();
        result = 31 * result + (clearPersisted ? 1 : 0);
        return result;
    }

    public static class Key
    {
        public static final int UPLOAD_BANDWIDTH = 1;
        public static final int DOWNLOAD_BANDWIDTH = 2;
        public static final int ROUND_TRIP_TIME = 3;
        public static final int MAX_CONCURRENT_STREAMS = 4;
        public static final int CURRENT_CONGESTION_WINDOW = 5;
        public static final int DOWNLOAD_RETRANSMISSION_RATE = 6;
        public static final int INITIAL_WINDOW_SIZE = 7;

        public static final int FLAG_PERSIST = 1 << 24;
        public static final int FLAG_PERSISTED = 2 << 24;

        private final int key;

        public Key(int key)
        {
            this.key = key;
        }

        public int getKey()
        {
            return key;
        }

        public boolean isPersist()
        {
            return (key & FLAG_PERSIST) == FLAG_PERSIST;
        }

        public boolean isPersisted()
        {
            return (key & FLAG_PERSISTED) == FLAG_PERSISTED;
        }

        public byte getFlags()
        {
            return (byte)(key >>> 24);
        }

        public int getId()
        {
            return key & 0xFF_FF_FF;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Key that = (Key)obj;
            return key == that.key;
        }

        @Override
        public int hashCode()
        {
            return key;
        }

        @Override
        public String toString()
        {
            return "[" + getFlags() + "," + getId() + "]";
        }
    }

    public static class Setting
    {
    }
}
