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

package org.eclipse.jetty.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.junit.Test;

public class ResourceCacheTest
{
    @Test
    public void testMutlipleSources1() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/one/",
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/two/",
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/three/"
        });

        Resource[] r = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachedContentFactory rc3 = new CachedContentFactory(null,r[2],mime,false,false,CompressedContentFormat.NONE);
        CachedContentFactory rc2 = new CachedContentFactory(rc3,r[1],mime,false,false,CompressedContentFormat.NONE);
        CachedContentFactory rc1 = new CachedContentFactory(rc2,r[0],mime,false,false,CompressedContentFormat.NONE);

        assertEquals("1 - one", getContent(rc1, "1.txt"));
        assertEquals("2 - two", getContent(rc1, "2.txt"));
        assertEquals("3 - three", getContent(rc1, "3.txt"));

        assertEquals("1 - two", getContent(rc2, "1.txt"));
        assertEquals("2 - two", getContent(rc2, "2.txt"));
        assertEquals("3 - three", getContent(rc2, "3.txt"));

        assertEquals(null, getContent(rc3, "1.txt"));
        assertEquals("2 - three", getContent(rc3, "2.txt"));
        assertEquals("3 - three", getContent(rc3, "3.txt"));

    }

    @Test
    public void testUncacheable() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/one/",
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/two/",
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/three/"
        });

        Resource[] r = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachedContentFactory rc3 = new CachedContentFactory(null,r[2],mime,false,false,CompressedContentFormat.NONE);
        CachedContentFactory rc2 = new CachedContentFactory(rc3,r[1],mime,false,false,CompressedContentFormat.NONE)
        {
            @Override
            public boolean isCacheable(Resource resource)
            {
                return super.isCacheable(resource) && resource.getName().indexOf("2.txt")<0;
            }
        };

        CachedContentFactory rc1 = new CachedContentFactory(rc2,r[0],mime,false,false,CompressedContentFormat.NONE);

        assertEquals("1 - one", getContent(rc1, "1.txt"));
        assertEquals("2 - two", getContent(rc1, "2.txt"));
        assertEquals("3 - three", getContent(rc1, "3.txt"));

        assertEquals("1 - two", getContent(rc2, "1.txt"));
        assertEquals("2 - two", getContent(rc2, "2.txt"));
        assertEquals("3 - three", getContent(rc2, "3.txt"));

        assertEquals(null, getContent(rc3, "1.txt"));
        assertEquals("2 - three", getContent(rc3, "2.txt"));
        assertEquals("3 - three", getContent(rc3, "3.txt"));

    }


    @Test
    public void testResourceCache() throws Exception
    {
        final Resource directory;
        File[] files=new File[10];
        String[] names=new String[files.length];
        CachedContentFactory cache;

        for (int i=0;i<files.length;i++)
        {
            files[i]=File.createTempFile("R-"+i+"-",".txt");
            files[i].deleteOnExit();
            names[i]=files[i].getName();
            try (OutputStream out = new FileOutputStream(files[i]))
            {
                for (int j=0;j<(i*10-1);j++)
                    out.write(' ');
                out.write('\n');
            }
        }

        directory=Resource.newResource(files[0].getParentFile().getAbsolutePath());


        cache=new CachedContentFactory(null,directory,new MimeTypes(),false,false,CompressedContentFormat.NONE);

        cache.setMaxCacheSize(95);
        cache.setMaxCachedFileSize(85);
        cache.setMaxCachedFiles(4);

        assertTrue(cache.getContent("does not exist",4096)==null);
        assertTrue(cache.getContent(names[9],4096) instanceof ResourceHttpContent);
        assertTrue(cache.getContent(names[9],4096).getIndirectBuffer()!=null);

        HttpContent content;
        content=cache.getContent(names[8],4096);
        assertTrue(content!=null);
        assertEquals(80,content.getContentLengthValue());
        assertEquals(0,cache.getCachedSize());
        
        if (OS.IS_LINUX)
        {
            // Initially not using memory mapped files
            content.getDirectBuffer();
            assertEquals(80,cache.getCachedSize());

            // with both types of buffer loaded, this is too large for cache
            content.getIndirectBuffer();
            assertEquals(0,cache.getCachedSize());
            assertEquals(0,cache.getCachedFiles());

            cache=new CachedContentFactory(null,directory,new MimeTypes(),true,false,CompressedContentFormat.NONE);
            cache.setMaxCacheSize(95);
            cache.setMaxCachedFileSize(85);
            cache.setMaxCachedFiles(4);
            
            content=cache.getContent(names[8],4096);
            content.getDirectBuffer();
            assertEquals(cache.isUseFileMappedBuffer()?0:80,cache.getCachedSize());

            // with both types of buffer loaded, this is not too large for cache because
            // mapped buffers don't count, so we can continue
        }
        content.getIndirectBuffer();
        assertEquals(80,cache.getCachedSize());
        assertEquals(1,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[1],4096);
        assertEquals(80,cache.getCachedSize());
        content.getIndirectBuffer();
        assertEquals(90,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[2],4096);
        content.getIndirectBuffer();
        assertEquals(30,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[3],4096);
        content.getIndirectBuffer();
        assertEquals(60,cache.getCachedSize());
        assertEquals(3,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[4],4096);
        content.getIndirectBuffer();
        assertEquals(90,cache.getCachedSize());
        assertEquals(3,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[5],4096);
        content.getIndirectBuffer();
        assertEquals(90,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[6],4096);
        content.getIndirectBuffer();
        assertEquals(60,cache.getCachedSize());
        assertEquals(1,cache.getCachedFiles());

        Thread.sleep(200);

        try (OutputStream out = new FileOutputStream(files[6]))
        {
            out.write(' ');
        }
        content=cache.getContent(names[7],4096);
        content.getIndirectBuffer();
        assertEquals(70,cache.getCachedSize());
        assertEquals(1,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[6],4096);
        content.getIndirectBuffer();
        assertEquals(71,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[0],4096);
        content.getIndirectBuffer();
        assertEquals(72,cache.getCachedSize());
        assertEquals(3,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[1],4096);
        content.getIndirectBuffer();
        assertEquals(82,cache.getCachedSize());
        assertEquals(4,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[2],4096);
        content.getIndirectBuffer();
        assertEquals(32,cache.getCachedSize());
        assertEquals(4,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.getContent(names[3],4096);
        content.getIndirectBuffer();
        assertEquals(61,cache.getCachedSize());
        assertEquals(4,cache.getCachedFiles());

        Thread.sleep(200);

        cache.flushCache();
        assertEquals(0,cache.getCachedSize());
        assertEquals(0,cache.getCachedFiles());


        cache.flushCache();
    }

    @Test
    public void testNoextension() throws Exception
    {
        ResourceCollection rc = new ResourceCollection(new String[]{
                "../jetty-util/src/test/resources/org/eclipse/jetty/util/resource/four/"
        });

        Resource[] resources = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachedContentFactory cache = new CachedContentFactory(null,resources[0],mime,false,false,CompressedContentFormat.NONE);

        assertEquals("4 - four", getContent(cache, "four.txt"));
        assertEquals("4 - four (no extension)", getContent(cache, "four"));
    }

    
    static String getContent(Resource r, String path) throws Exception
    {
        StringBuilder buffer = new StringBuilder();
        String line = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(r.addPath(path).getURL().openStream())))
        {
            while((line=br.readLine())!=null)
                buffer.append(line);
        }
        return buffer.toString();
    }

    static String getContent(CachedContentFactory rc, String path) throws Exception
    {
        HttpContent content = rc.lookup(path);
        if (content==null)
            return null;

        return BufferUtil.toString(content.getIndirectBuffer());
    }
}
