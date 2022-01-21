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

package org.eclipse.jetty.http3.qpack.internal.table;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http3.qpack.internal.metadata.StaticTableHttpField;
import org.eclipse.jetty.util.Index;

public class StaticTable implements Iterable<Entry>
{
    public static final String[][] STATIC_TABLE =
        {
            {":authority", ""},
            {":path", "/"},
            {"age", "0"},
            {"content-disposition", ""},
            {"content-length", "0"},
            {"cookie", ""},
            {"date", ""},
            {"etag", ""},
            {"if-modified-since", ""},
            {"if-none-match", ""},
            {"last-modified", ""},
            {"link", ""},
            {"location", ""},
            {"referer", ""},
            {"set-cookie", ""},
            {":method", "CONNECT"},
            {":method", "DELETE"},
            {":method", "GET"},
            {":method", "HEAD"},
            {":method", "OPTIONS"},
            {":method", "POST"},
            {":method", "PUT"},
            {":scheme", "http"},
            {":scheme", "https"},
            {":status", "103"},
            {":status", "200"},
            {":status", "304"},
            {":status", "404"},
            {":status", "503"},
            {"accept", "*/*"},
            {"accept", "application/dns-message"},
            {"accept-encoding", "gzip, deflate, br"},
            {"accept-ranges", "bytes"},
            {"access-control-allow-headers", "cache-control"},
            {"access-control-allow-headers", "content-type"},
            {"access-control-allow-origin", "*"},
            {"cache-control", "max-age=0"},
            {"cache-control", "max-age=2592000"},
            {"cache-control", "max-age=604800"},
            {"cache-control", "no-cache"},
            {"cache-control", "no-store"},
            {"cache-control", "public, max-age=31536000"},
            {"content-encoding", "br"},
            {"content-encoding", "gzip"},
            {"content-type", "application/dns-message"},
            {"content-type", "application/javascript"},
            {"content-type", "application/json"},
            {"content-type", "application/x-www-form-urlencoded"},
            {"content-type", "image/gif"},
            {"content-type", "image/jpeg"},
            {"content-type", "image/png"},
            {"content-type", "text/css"},
            {"content-type", "text/html; charset=utf-8"},
            {"content-type", "text/plain"},
            {"content-type", "text/plain;charset=utf-8"},
            {"range", "bytes=0-"},
            {"strict-transport-security", "max-age=31536000"},
            {"strict-transport-security", "max-age=31536000; includesubdomains"},
            {"strict-transport-security", "max-age=31536000; includesubdomains; preload"},
            {"vary", "accept-encoding"},
            {"vary", "origin"},
            {"x-content-type-options", "nosniff"},
            {"x-xss-protection", "1; mode=block"},
            {":status", "100"},
            {":status", "204"},
            {":status", "206"},
            {":status", "302"},
            {":status", "400"},
            {":status", "403"},
            {":status", "421"},
            {":status", "425"},
            {":status", "500"},
            {"accept-language", ""},
            {"access-control-allow-credentials", "FALSE"},
            {"access-control-allow-credentials", "TRUE"},
            {"access-control-allow-headers", "*"},
            {"access-control-allow-methods", "get"},
            {"access-control-allow-methods", "get, post, options"},
            {"access-control-allow-methods", "options"},
            {"access-control-expose-headers", "content-length"},
            {"access-control-request-headers", "content-type"},
            {"access-control-request-method", "get"},
            {"access-control-request-method", "post"},
            {"alt-svc", "clear"},
            {"authorization", ""},
            {"content-security-policy", "script-src 'none'; object-src 'none'; base-uri 'none'"},
            {"early-data", "1"},
            {"expect-ct", ""},
            {"forwarded", ""},
            {"if-range", ""},
            {"origin", ""},
            {"purpose", "prefetch"},
            {"server", ""},
            {"timing-allow-origin", "*"},
            {"upgrade-insecure-requests", "1"},
            {"user-agent", ""},
            {"x-forwarded-for", ""},
            {"x-frame-options", "deny"},
            {"x-frame-options", "sameorigin"},
        };

    public static final int STATIC_SIZE = STATIC_TABLE.length - 1;

    private final Map<HttpField, Entry> _staticFieldMap = new HashMap<>();
    private final Index<Entry.StaticEntry> _staticNameMap;
    private final Entry.StaticEntry[] _staticTableByHeader = new Entry.StaticEntry[HttpHeader.values().length];
    private final Entry.StaticEntry[] _staticTable = new Entry.StaticEntry[STATIC_TABLE.length];

    public StaticTable()
    {
        Index.Builder<Entry.StaticEntry> staticNameMapBuilder = new Index.Builder<Entry.StaticEntry>().caseSensitive(false);
        Set<String> added = new HashSet<>();
        for (int i = 0; i < STATIC_TABLE.length; i++)
        {
            Entry.StaticEntry entry = null;

            String name = STATIC_TABLE[i][0];
            String value = STATIC_TABLE[i][1];
            HttpHeader header = HttpHeader.CACHE.get(name);
            if (header != null && value != null)
            {
                switch (header)
                {
                    case C_METHOD:
                    {

                        HttpMethod method = HttpMethod.CACHE.get(value);
                        if (method != null)
                            entry = new Entry.StaticEntry(i, new StaticTableHttpField(header, name, value, method));
                        break;
                    }

                    case C_SCHEME:
                    {

                        HttpScheme scheme = HttpScheme.CACHE.get(value);
                        if (scheme != null)
                            entry = new Entry.StaticEntry(i, new StaticTableHttpField(header, name, value, scheme));
                        break;
                    }

                    case C_STATUS:
                    {
                        entry = new Entry.StaticEntry(i, new StaticTableHttpField(header, name, value, value));
                        break;
                    }

                    default:
                        break;
                }
            }

            if (entry == null)
                entry = new Entry.StaticEntry(i, header == null ? new HttpField(STATIC_TABLE[i][0], value) : new HttpField(header, name, value));

            _staticTable[i] = entry;

            if (entry.getHttpField().getValue() != null)
                _staticFieldMap.put(entry.getHttpField(), entry);

            if (!added.contains(entry.getHttpField().getName()))
            {
                added.add(entry.getHttpField().getName());
                staticNameMapBuilder.with(entry.getHttpField().getName(), entry);
            }
        }
        _staticNameMap = staticNameMapBuilder.build();

        for (HttpHeader h : HttpHeader.values())
        {
            Entry.StaticEntry entry = _staticNameMap.get(h.asString());
            if (entry != null)
                _staticTableByHeader[h.ordinal()] = entry;
        }
    }

    public Entry get(HttpField field)
    {
        return _staticFieldMap.get(field);
    }

    public Entry get(String name)
    {
        return _staticNameMap.get(name);
    }

    public Entry get(int index)
    {
        if (index >= _staticTable.length)
            return null;
        return _staticTable[index];
    }

    public Entry get(HttpHeader header)
    {
        int index = header.ordinal();
        if (index >= _staticTableByHeader.length)
            return null;
        return _staticTableByHeader[index];
    }

    @Override
    public Iterator<Entry> iterator()
    {
        return Arrays.stream(_staticTable).map(e -> (Entry)e).iterator();
    }
}
