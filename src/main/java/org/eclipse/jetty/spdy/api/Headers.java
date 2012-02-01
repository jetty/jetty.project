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

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class Headers implements Iterable<Headers.Header>
{
    public static final byte[] DICTIONARY = ("" +
            "optionsgetheadpostputdeletetraceacceptaccept-charsetaccept-encodingaccept-" +
            "languageauthorizationexpectfromhostif-modified-sinceif-matchif-none-matchi" +
            "f-rangeif-unmodifiedsincemax-forwardsproxy-authorizationrangerefererteuser" +
            "-agent10010120020120220320420520630030130230330430530630740040140240340440" +
            "5406407408409410411412413414415416417500501502503504505accept-rangesageeta" +
            "glocationproxy-authenticatepublicretry-afterservervarywarningwww-authentic" +
            "ateallowcontent-basecontent-encodingcache-controlconnectiondatetrailertran" +
            "sfer-encodingupgradeviawarningcontent-languagecontent-lengthcontent-locati" +
            "oncontent-md5content-rangecontent-typeetagexpireslast-modifiedset-cookieMo" +
            "ndayTuesdayWednesdayThursdayFridaySaturdaySundayJanFebMarAprMayJunJulAugSe" +
            "pOctNovDecchunkedtext/htmlimage/pngimage/jpgimage/gifapplication/xmlapplic" +
            "ation/xhtmltext/plainpublicmax-agecharset=iso-8859-1utf-8gzipdeflateHTTP/1" +
            ".1statusversionurl" +
            // Must be NUL terminated
            "\u0000").getBytes();

    private final Map<String, Header> headers;

    public Headers()
    {
        headers = new LinkedHashMap<>();
    }

    public Headers(Headers original, boolean immutable)
    {
        Map<String, Header> copy = new LinkedHashMap<>();
        copy.putAll(original.headers);
        headers = immutable ? Collections.unmodifiableMap(copy) : copy;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        Headers that = (Headers)obj;
        return headers.equals(that.headers);
    }

    @Override
    public int hashCode()
    {
        return headers.hashCode();
    }

    public Set<String> names()
    {
        Set<String> result = new LinkedHashSet<String>();
        for (Header header : headers.values())
            result.add(header.name);
        return result;
    }

    public Header get(String name)
    {
        return headers.get(name.trim().toLowerCase());
    }

    public void put(String name, String value)
    {
        name = name.trim();
        Header header = new Header(name, value.trim());
        headers.put(name.toLowerCase(), header);
    }

    public void put(String name, Header header)
    {
        name = name.trim();
        headers.put(name.toLowerCase(), header);
    }

    public void add(String name, String value)
    {
        name = name.trim();
        Header header = headers.get(name.toLowerCase());
        if (header == null)
        {
            header = new Header(name, value.trim());
            headers.put(name.toLowerCase(), header);
        }
        else
        {
            header = new Header(header.name(), header.value() + "," + value.trim());
            headers.put(name.toLowerCase(), header);
        }
    }

    public Header remove(String name)
    {
        name = name.trim();
        return headers.remove(name.toLowerCase());
    }

    public void clear()
    {
        headers.clear();
    }

    public boolean isEmpty()
    {
        return headers.isEmpty();
    }

    public int getSize()
    {
        return headers.size();
    }

    @Override
    public Iterator<Header> iterator()
    {
        return headers.values().iterator();
    }

    public static class Header
    {
        private final String name;
        private final String[] values;

        private Header(String name, String value, String... values)
        {
            this.name = name;
            this.values = new String[values.length + 1];
            this.values[0] = value;
            if (values.length > 0)
                System.arraycopy(values, 0, this.values, 1, values.length);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Header that = (Header)obj;
            return name.equals(that.name) && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode()
        {
            int result = name.hashCode();
            result = 31 * result + Arrays.hashCode(values);
            return result;
        }

        public String name()
        {
            return name;
        }

        public String value()
        {
            return values[0];
        }

        /**
         * <p>Attempts to convert the result of {@link #value()} to an integer,
         * returning it if the conversion is successful; returns null if the
         * result of {@link #value()} is null.</p>
         *
         * @return the result of {@link #value()} converted to an integer, or null
         * @throws NumberFormatException if the conversion fails
         */
        public Integer valueAsInt()
        {
            final String value = value();
            return value == null ? null : Integer.valueOf(value);
        }

        public String[] values()
        {
            return values;
        }

        public boolean hasMultipleValues()
        {
            return values.length > 1;
        }

        @Override
        public String toString()
        {
            return Arrays.toString(values);
        }
    }
}
