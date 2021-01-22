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

package org.eclipse.jetty.http2.hpack;

import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HpackPerfTest
{
    int _maxDynamicTableSize = 4 * 1024;
    int _unencodedSize;
    int _encodedSize;

    @BeforeEach
    public void before()
    {
        _unencodedSize = 0;
        _encodedSize = 0;
    }

    @AfterEach
    public void after()
    {
        System.err.printf("dynamictable=%d unencoded=%d encoded=%d p=%3.1f%%%n", _maxDynamicTableSize, _unencodedSize, _encodedSize, 100.0 * _encodedSize / _unencodedSize);
    }

    @Test
    public void simpleTest() throws Exception
    {
        runStories();
    }

    private void runStories() throws Exception
    {
        // Find files
        File data = MavenTestingUtils.getTestResourceDir("data");
        String[] files = data.list((dir, name) -> name.startsWith("story_"));
        assertNotNull(files);

        // Parse JSON
        @SuppressWarnings("unchecked")
        Map<String, Object>[] stories = new Map[files.length];
        int i = 0;
        for (String file : files)
        {
            @SuppressWarnings("unchecked")
            var story = (Map<String, Object>)new JSON().fromJSON(new FileReader(new File(data, file)));
            stories[i++] = story;
        }

        ByteBuffer buffer = BufferUtil.allocate(256 * 1024);

        // Encode all the requests
        encodeStories(buffer, stories, "request");

        // clear table
        BufferUtil.clearToFill(buffer);
        BufferUtil.flipToFlush(buffer, 0);

        // Encode all the responses
        encodeStories(buffer, stories, "response");
    }

    private void encodeStories(ByteBuffer buffer, Map<String, Object>[] stories, String type) throws Exception
    {
        for (Map<String, Object> story : stories)
        {
            if (type.equals(story.get("context")))
            {
                HpackEncoder encoder = new HpackEncoder(_maxDynamicTableSize, _maxDynamicTableSize);
                encoder.setValidateEncoding(false);

                Object[] cases = (Object[])story.get("cases");
                for (Object c : cases)
                {
                    @SuppressWarnings("unchecked")
                    var kase = (Map<String, Object>)c;
                    Object[] headers = (Object[])kase.get("headers");
                    // System.err.println("    "+headers);
                    HttpFields.Mutable fields = HttpFields.build();
                    for (Object header : headers)
                    {
                        @SuppressWarnings("unchecked")
                        var h = (Map<String, String>)header;
                        Map.Entry<String, String> e = h.entrySet().iterator().next();
                        fields.add(e.getKey(), e.getValue());
                        _unencodedSize += e.getKey().length() + e.getValue().length();
                    }

                    BufferUtil.clearToFill(buffer);
                    encoder.encode(buffer, new MetaData(HttpVersion.HTTP_2, fields));
                    BufferUtil.flipToFlush(buffer, 0);
                    _encodedSize += buffer.remaining();
                }
            }
        }
    }
}
