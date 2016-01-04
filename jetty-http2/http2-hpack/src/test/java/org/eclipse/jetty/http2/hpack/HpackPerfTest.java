//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.io.FilenameFilter;
import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class HpackPerfTest
{
    int _maxDynamicTableSize=4*1024;
    int _unencodedSize;
    int _encodedSize;
    
    @Before
    public void before()
    {
        _unencodedSize=0;
        _encodedSize=0;
    }

    @After
    public void after()
    {        
        System.err.printf("dynamictable=%d unencoded=%d encoded=%d p=%3.1f%%%n",_maxDynamicTableSize,_unencodedSize,_encodedSize,100.0*_encodedSize/_unencodedSize);

    }
    
    @Test
    public void simpleTest() throws Exception
    {
        runStories(_maxDynamicTableSize);
    }
    
    private void runStories(int maxDynamicTableSize) throws Exception
    {
        // Find files
        File data = MavenTestingUtils.getTestResourceDir("data");
        String[] files = data.list(new FilenameFilter()
        {
            @Override
            public boolean accept(File dir, String name)
            {
                return name.startsWith("story_");
            }
        });
        
        // Parse JSON
        Map<String,Object>[] stories = new Map[files.length];
        int i=0;
        for (String story : files)
            stories[i++]=(Map<String,Object>)JSON.parse(new FileReader(new File(data,story)));
        
        ByteBuffer buffer = BufferUtil.allocate(256*1024);
        
        // Encode all the requests
        encodeStories(buffer,stories,"request");

        // clear table
        BufferUtil.clearToFill(buffer);
        BufferUtil.flipToFlush(buffer,0);
        
        // Encode all the responses
        encodeStories(buffer,stories,"response");
        
    }
    
    private void encodeStories(ByteBuffer buffer,Map<String,Object>[] stories, String type) throws Exception
    {
        for (Map<String,Object> story : stories)
        {
            if (type.equals(story.get("context")))
            {
                HpackEncoder encoder = new HpackEncoder(_maxDynamicTableSize,_maxDynamicTableSize);
                
                // System.err.println(story);
                Object[] cases = (Object[])story.get("cases");
                for (Object c : cases)
                {
                    // System.err.println("  "+c);
                    Object[] headers = (Object[])((Map<String,Object>)c).get("headers");
                    // System.err.println("    "+headers);
                    HttpFields fields = new HttpFields();
                    for (Object header:headers)
                    {
                        Map<String,String> h = (Map<String,String>)header;
                        Map.Entry<String, String> e = h.entrySet().iterator().next();
                        fields.add(e.getKey(),e.getValue());
                        _unencodedSize+=e.getKey().length()+e.getValue().length();
                        
                    }

                    BufferUtil.clearToFill(buffer);
                    encoder.encode(buffer,new MetaData(HttpVersion.HTTP_2,fields));
                    BufferUtil.flipToFlush(buffer,0);
                    _encodedSize+=buffer.remaining();
                    
                }
            }
        }

    }
    
    
}
