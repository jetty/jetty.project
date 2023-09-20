//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
    int _tableCapacity = 4 * 1024;
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
        System.err.printf("dynamictable=%d unencoded=%d encoded=%d p=%3.1f%%%n", _tableCapacity, _unencodedSize, _encodedSize, 100.0 * _encodedSize / _unencodedSize);
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
            Map<String, Object> story = (Map<String, Object>)JSON.parse(new FileReader(new File(data, file)));
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
                HpackEncoder encoder = new HpackEncoder();
                encoder.setMaxTableCapacity(_tableCapacity);
                encoder.setTableCapacity(_tableCapacity);
                encoder.setValidateEncoding(false);

                Object[] cases = (Object[])story.get("cases");
                for (Object c : cases)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> kase = (Map<String, Object>)c;
                    Object[] headers = (Object[])kase.get("headers");
                    // System.err.println("    "+headers);
                    HttpFields fields = new HttpFields();
                    for (Object header : headers)
                    {
                        @SuppressWarnings("unchecked")
                        Map<String, String> h = (Map<String, String>)header;
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
