//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
@Disabled // TODO
public class ResourceCacheTest
{
    public WorkDir workDir;

    public Path createUtilTestResources(Path basePath) throws IOException
    {
        // root
        makeFile(basePath.resolve("resource.txt"), "this is test data");

        // - one/
        Path one = basePath.resolve("one");
        FS.ensureDirExists(one);
        makeFile(one.resolve("1.txt"), "1 - one");

        // - one/dir/
        Path oneDir = one.resolve("dir");
        FS.ensureDirExists(oneDir);
        makeFile(oneDir.resolve("1.txt"), "1 - one");

        // - two/
        Path two = basePath.resolve("two");
        FS.ensureDirExists(two);
        makeFile(two.resolve("1.txt"), "1 - two");
        makeFile(two.resolve("2.txt"), "2 - two");

        // - two/dir/
        Path twoDir = two.resolve("dir");
        FS.ensureDirExists(twoDir);
        makeFile(twoDir.resolve("2.txt"), "2 - two");

        // - three/
        Path three = basePath.resolve("three");
        FS.ensureDirExists(three);
        makeFile(three.resolve("2.txt"), "2 - three");
        makeFile(three.resolve("3.txt"), "3 - three");

        // - three/dir/
        Path threeDir = three.resolve("dir");
        FS.ensureDirExists(threeDir);
        makeFile(threeDir.resolve("3.txt"), "3 - three");

        // - four/
        Path four = basePath.resolve("four");
        FS.ensureDirExists(four);
        makeFile(four.resolve("four"), "4 - four (no extension)");
        makeFile(four.resolve("four.txt"), "4 - four");

        return basePath;
    }

    private void makeFile(Path file, String contents) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(file, UTF_8, StandardOpenOption.CREATE_NEW))
        {
            writer.write(contents);
            writer.flush();
        }
    }

    @Test
    public void testMultipleSources1() throws Exception
    {
        Path basePath = createUtilTestResources(workDir.getEmptyPathDir());

        List<Resource> resourceList = Stream.of("one", "two", "three")
            .map(basePath::resolve)
            .map(Resource::newResource)
            .toList();

        ResourceCollection rc = Resource.of(resourceList);

        List<Resource> r = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachedContentFactory rc3 = new CachedContentFactory(null, r.get(2), mime, false, false, CompressedContentFormat.NONE);
        CachedContentFactory rc2 = new CachedContentFactory(rc3, r.get(1), mime, false, false, CompressedContentFormat.NONE);
        CachedContentFactory rc1 = new CachedContentFactory(rc2, r.get(0), mime, false, false, CompressedContentFormat.NONE);

        assertEquals(getContent(rc1, "1.txt"), "1 - one");
        assertEquals(getContent(rc1, "2.txt"), "2 - two");
        assertEquals(getContent(rc1, "3.txt"), "3 - three");

        assertEquals(getContent(rc2, "1.txt"), "1 - two");
        assertEquals(getContent(rc2, "2.txt"), "2 - two");
        assertEquals(getContent(rc2, "3.txt"), "3 - three");

        assertNull(getContent(rc3, "1.txt"));
        assertEquals(getContent(rc3, "2.txt"), "2 - three");
        assertEquals(getContent(rc3, "3.txt"), "3 - three");
    }

    @Test
    public void testUncacheable() throws Exception
    {
        Path basePath = createUtilTestResources(workDir.getEmptyPathDir());

        List<Resource> resourceList = Stream.of("one", "two", "three")
            .map(basePath::resolve)
            .map(Resource::newResource)
            .toList();

        ResourceCollection rc = Resource.of(resourceList);

        List<Resource> r = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachedContentFactory rc3 = new CachedContentFactory(null, r.get(2), mime, false, false, CompressedContentFormat.NONE);
        CachedContentFactory rc2 = new CachedContentFactory(rc3, r.get(1), mime, false, false, CompressedContentFormat.NONE)
        {
            @Override
            public boolean isCacheable(Resource resource)
            {
                return super.isCacheable(resource) && resource.getName().indexOf("2.txt") < 0;
            }
        };

        CachedContentFactory rc1 = new CachedContentFactory(rc2, r.get(0), mime, false, false, CompressedContentFormat.NONE);

        assertEquals(getContent(rc1, "1.txt"), "1 - one");
        assertEquals(getContent(rc1, "2.txt"), "2 - two");
        assertEquals(getContent(rc1, "3.txt"), "3 - three");

        assertEquals(getContent(rc2, "1.txt"), "1 - two");
        assertEquals(getContent(rc2, "2.txt"), "2 - two");
        assertEquals(getContent(rc2, "3.txt"), "3 - three");

        assertNull(getContent(rc3, "1.txt"));
        assertEquals(getContent(rc3, "2.txt"), "2 - three");
        assertEquals(getContent(rc3, "3.txt"), "3 - three");
    }

    @Test
    public void testResourceCache() throws Exception
    {
        final Resource directory;
        File[] files = new File[10];
        String[] names = new String[files.length];
        CachedContentFactory cache;

        Path basePath = workDir.getEmptyPathDir();

        for (int i = 0; i < files.length; i++)
        {
            Path tmpFile = basePath.resolve("R-" + i + ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(tmpFile, UTF_8, StandardOpenOption.CREATE_NEW))
            {
                for (int j = 0; j < (i * 10 - 1); j++)
                {
                    writer.write(' ');
                }
                writer.write('\n');
            }
            files[i] = tmpFile.toFile();
            names[i] = tmpFile.getFileName().toString();
        }

        directory = Resource.newResource(files[0].getParentFile().getAbsolutePath());

        cache = new CachedContentFactory(null, directory, new MimeTypes(), false, false, CompressedContentFormat.NONE);

        cache.setMaxCacheSize(95);
        cache.setMaxCachedFileSize(85);
        cache.setMaxCachedFiles(4);

        assertNull(cache.getContent("does not exist", 4096));
        assertTrue(cache.getContent(names[9], 4096) instanceof ResourceHttpContent);
        assertNotNull(cache.getContent(names[9], 4096).getBuffer());

        HttpContent content;
        content = cache.getContent(names[8], 4096);
        assertThat(content, is(not(nullValue())));
        assertEquals(80, content.getContentLengthValue());
        assertEquals(0, cache.getCachedSize());

        if (org.junit.jupiter.api.condition.OS.LINUX.isCurrentOs())
        {
            // Initially not using memory mapped files
            content.getBuffer();
            assertEquals(80, cache.getCachedSize());

            // with both types of buffer loaded, this is too large for cache
            content.getBuffer();
            assertEquals(0, cache.getCachedSize());
            assertEquals(0, cache.getCachedFiles());

            cache = new CachedContentFactory(null, directory, new MimeTypes(), true, false, CompressedContentFormat.NONE);
            cache.setMaxCacheSize(95);
            cache.setMaxCachedFileSize(85);
            cache.setMaxCachedFiles(4);

            content = cache.getContent(names[8], 4096);
            content.getBuffer();
            assertEquals(cache.isUseFileMappedBuffer() ? 0 : 80, cache.getCachedSize());

            // with both types of buffer loaded, this is not too large for cache because
            // mapped buffers don't count, so we can continue
        }

        content.getBuffer();
        assertEquals(80, cache.getCachedSize());
        assertEquals(1, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[1], 4096);
        assertEquals(80, cache.getCachedSize());
        content.getBuffer();
        assertEquals(90, cache.getCachedSize());
        assertEquals(2, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[2], 4096);
        content.getBuffer();
        assertEquals(30, cache.getCachedSize());
        assertEquals(2, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[3], 4096);
        content.getBuffer();
        assertEquals(60, cache.getCachedSize());
        assertEquals(3, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[4], 4096);
        content.getBuffer();
        assertEquals(90, cache.getCachedSize());
        assertEquals(3, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[5], 4096);
        content.getBuffer();
        assertEquals(90, cache.getCachedSize());
        assertEquals(2, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[6], 4096);
        content.getBuffer();
        assertEquals(60, cache.getCachedSize());
        assertEquals(1, cache.getCachedFiles());

        Thread.sleep(200);

        try (OutputStream out = new FileOutputStream(files[6]))
        {
            out.write(' ');
        }
        content = cache.getContent(names[7], 4096);
        content.getBuffer();
        assertEquals(70, cache.getCachedSize());
        assertEquals(1, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[6], 4096);
        content.getBuffer();
        assertEquals(71, cache.getCachedSize());
        assertEquals(2, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[0], 4096);
        content.getBuffer();
        assertEquals(72, cache.getCachedSize());
        assertEquals(3, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[1], 4096);
        content.getBuffer();
        assertEquals(82, cache.getCachedSize());
        assertEquals(4, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[2], 4096);
        content.getBuffer();
        assertEquals(32, cache.getCachedSize());
        assertEquals(4, cache.getCachedFiles());

        Thread.sleep(200);

        content = cache.getContent(names[3], 4096);
        content.getBuffer();
        assertEquals(61, cache.getCachedSize());
        assertEquals(4, cache.getCachedFiles());

        Thread.sleep(200);

        cache.flushCache();
        assertEquals(0, cache.getCachedSize());
        assertEquals(0, cache.getCachedFiles());

        cache.flushCache();
    }

    @Test
    public void testNoextension() throws Exception
    {
        Path basePath = createUtilTestResources(workDir.getEmptyPathDir());

        Resource resource = Resource.newResource(basePath.resolve("four"));
        MimeTypes mime = new MimeTypes();

        CachedContentFactory cache = new CachedContentFactory(null, resource, mime, false, false, CompressedContentFormat.NONE);

        assertEquals(getContent(cache, "four.txt"), "4 - four");
        assertEquals(getContent(cache, "four"), "4 - four (no extension)");
    }

    static String getContent(CachedContentFactory rc, String path) throws Exception
    {
        HttpContent content = rc.getContent(path, rc.getMaxCachedFileSize());
        if (content == null)
            return null;

        return BufferUtil.toString(content.getBuffer());
    }
}
