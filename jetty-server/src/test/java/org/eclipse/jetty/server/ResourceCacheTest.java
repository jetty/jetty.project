//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.MimeTypes;
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

        ResourceCache rc3 = new ResourceCache(null,r[2],mime,false,false);
        ResourceCache rc2 = new ResourceCache(rc3,r[1],mime,false,false);
        ResourceCache rc1 = new ResourceCache(rc2,r[0],mime,false,false);

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

        ResourceCache rc3 = new ResourceCache(null,r[2],mime,false,false);
        ResourceCache rc2 = new ResourceCache(rc3,r[1],mime,false,false)
        {
            @Override
            public boolean isCacheable(Resource resource)
            {
                return super.isCacheable(resource) && resource.getName().indexOf("2.txt")<0;
            }
        };

        ResourceCache rc1 = new ResourceCache(rc2,r[0],mime,false,false);

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
        ResourceCache cache;

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


        cache=new ResourceCache(null,directory,new MimeTypes(),false,false);

        cache.setMaxCacheSize(95);
        cache.setMaxCachedFileSize(85);
        cache.setMaxCachedFiles(4);

        assertTrue(cache.lookup("does not exist")==null);
        assertTrue(cache.lookup(names[9]) instanceof HttpContent.ResourceAsHttpContent);

        HttpContent content;
        content=cache.lookup(names[8]);
        assertTrue(content!=null);
        assertEquals(80,content.getContentLength());

        assertEquals(80,cache.getCachedSize());
        assertEquals(1,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[1]);
        assertEquals(90,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[2]);
        assertEquals(30,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[3]);
        assertEquals(60,cache.getCachedSize());
        assertEquals(3,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[4]);
        assertEquals(90,cache.getCachedSize());
        assertEquals(3,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[5]);
        assertEquals(90,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[6]);
        assertEquals(60,cache.getCachedSize());
        assertEquals(1,cache.getCachedFiles());

        Thread.sleep(200);

        try (OutputStream out = new FileOutputStream(files[6]))
        {
            out.write(' ');
        }
        content=cache.lookup(names[7]);
        assertEquals(70,cache.getCachedSize());
        assertEquals(1,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[6]);
        assertEquals(71,cache.getCachedSize());
        assertEquals(2,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[0]);
        assertEquals(72,cache.getCachedSize());
        assertEquals(3,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[1]);
        assertEquals(82,cache.getCachedSize());
        assertEquals(4,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[2]);
        assertEquals(32,cache.getCachedSize());
        assertEquals(4,cache.getCachedFiles());

        Thread.sleep(200);

        content=cache.lookup(names[3]);
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

        ResourceCache cache = new ResourceCache(null,resources[0],mime,false,false);

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

    static String getContent(ResourceCache rc, String path) throws Exception
    {
        HttpContent content = rc.lookup(path);
        if (content==null)
            return null;

        return BufferUtil.toString(content.getIndirectBuffer());
    }
}
