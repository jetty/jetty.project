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

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Settings implements Iterable<Settings.Setting>
{
    private Map<ID, Settings.Setting> settings = new HashMap<>();

    public Settings()
    {
    }

    public Settings(Settings original, boolean immutable)
    {
        Map<ID, Settings.Setting> copy = new HashMap<>(original.size());
        copy.putAll(original.settings);
        settings = immutable ? Collections.unmodifiableMap(copy) : copy;
    }

    public Setting get(ID id)
    {
        return settings.get(id);
    }

    public void put(Setting setting)
    {
        settings.put(setting.id(), setting);
    }

    public Setting remove(ID id)
    {
        return settings.remove(id);
    }

    public int size()
    {
        return settings.size();
    }

    public void clear()
    {
        settings.clear();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        Settings that = (Settings)obj;
        return settings.equals(that.settings);
    }

    @Override
    public int hashCode()
    {
        return settings.hashCode();
    }

    @Override
    public Iterator<Setting> iterator()
    {
        return settings.values().iterator();
    }

    @Override
    public String toString()
    {
        return settings.toString();
    }

    public static final class ID
    {
        public static ID UPLOAD_BANDWIDTH = new ID(1);
        public static ID DOWNLOAD_BANDWIDTH = new ID(2);
        public static ID ROUND_TRIP_TIME = new ID(3);
        public static ID MAX_CONCURRENT_STREAMS = new ID(4);
        public static ID CURRENT_CONGESTION_WINDOW = new ID(5);
        public static ID DOWNLOAD_RETRANSMISSION_RATE = new ID(6);
        public static ID INITIAL_WINDOW_SIZE = new ID(7);

        public synchronized static ID from(int code)
        {
            ID id = Codes.codes.get(code);
            if (id == null)
                id = new ID(code);
            return id;
        }

        private final int code;

        private ID(int code)
        {
            this.code = code;
            Codes.codes.put(code, this);
        }

        public int code()
        {
            return code;
        }

        @Override
        public String toString()
        {
            return String.valueOf(code);
        }

        private static class Codes
        {
            private static final Map<Integer, ID> codes = new HashMap<>();
        }
    }

    public static enum Flag
    {
        NONE((byte)0),
        PERSIST((byte)1),
        PERSISTED((byte)2);

        public static Flag from(byte code)
        {
            return Codes.codes.get(code);
        }

        private final byte code;

        private Flag(byte code)
        {
            this.code = code;
            Codes.codes.put(code, this);
        }

        public byte code()
        {
            return code;
        }

        private static class Codes
        {
            private static final Map<Byte, Flag> codes = new HashMap<>();
        }
    }

    public static class Setting
    {
        private final ID id;
        private final Flag flag;
        private final int value;

        public Setting(ID id, int value)
        {
            this(id, Flag.NONE, value);
        }

        public Setting(ID id, Flag flag, int value)
        {
            this.id = id;
            this.flag = flag;
            this.value = value;
        }

        public ID id()
        {
            return id;
        }

        public Flag flag()
        {
            return flag;
        }

        public int value()
        {
            return value;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Setting that = (Setting)obj;
            return value == that.value && flag == that.flag && id == that.id;
        }

        @Override
        public int hashCode()
        {
            int result = id.hashCode();
            result = 31 * result + flag.hashCode();
            result = 31 * result + value;
            return result;
        }

        @Override
        public String toString()
        {
            return String.format("[id=%s,flags=%s:value=%d]", id(), flag(), value());
        }
    }
}
