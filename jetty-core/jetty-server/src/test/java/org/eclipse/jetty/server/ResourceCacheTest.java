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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(WorkDirExtension.class)
public class ResourceCacheTest
{
    public WorkDir workDir;

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

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

    private static CachingContentFactory newCachingContentFactory(CachingContentFactory parent, ResourceFactory factory, MimeTypes mimeTypes, boolean useFileMappedBuffer)
    {
        return newCachingContentFactory(parent, factory, mimeTypes, useFileMappedBuffer, c -> true);
    }

    private static CachingContentFactory newCachingContentFactory(CachingContentFactory parent, ResourceFactory factory, MimeTypes mimeTypes, boolean useFileMappedBuffer, Predicate<HttpContent> predicate)
    {
        return new CachingContentFactory(parent, new ResourceContentFactory(factory, mimeTypes, List.of(CompressedContentFormat.NONE)), useFileMappedBuffer)
        {
            @Override
            protected boolean isCacheable(HttpContent httpContent)
            {
                return super.isCacheable(httpContent) && predicate.test(httpContent);
            }
        };
    }

    @Test
    public void testMultipleSources1() throws Exception
    {
        Path basePath = createUtilTestResources(workDir.getEmptyPathDir());

        List<Resource> resourceList = Stream.of("one", "two", "three")
            .map(basePath::resolve)
            .map(ResourceFactory.root()::newResource)
            .toList();

        ResourceCollection rc = Resource.combine(resourceList);

        List<Resource> r = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachingContentFactory rc3 = newCachingContentFactory(null, ResourceFactory.of(r.get(2)), mime, false);
        CachingContentFactory rc2 = newCachingContentFactory(rc3, ResourceFactory.of(r.get(1)), mime, false);
        CachingContentFactory rc1 = newCachingContentFactory(rc2, ResourceFactory.of(r.get(0)), mime, false);

        assertEquals("1 - one", getContent(rc1, "1.txt"));
        assertEquals("2 - two", getContent(rc1, "2.txt"));
        assertEquals("3 - three", getContent(rc1, "3.txt"));

        assertEquals("1 - two", getContent(rc2, "1.txt"));
        assertEquals("2 - two", getContent(rc2, "2.txt"));
        assertEquals("3 - three", getContent(rc2, "3.txt"));

        assertNull(getContent(rc3, "1.txt"));
        assertEquals("2 - three", getContent(rc3, "2.txt"));
        assertEquals("3 - three", getContent(rc3, "3.txt"));
    }

    @Test
    public void testUncacheable() throws Exception
    {
        Path basePath = createUtilTestResources(workDir.getEmptyPathDir());

        List<Resource> resourceList = Stream.of("one", "two", "three")
            .map(basePath::resolve)
            .map(ResourceFactory.root()::newResource)
            .toList();

        ResourceCollection rc = Resource.combine(resourceList);

        List<Resource> r = rc.getResources();
        MimeTypes mime = new MimeTypes();

        CachingContentFactory rc3 = newCachingContentFactory(null, ResourceFactory.of(r.get(2)), mime, false);
        CachingContentFactory rc2 = newCachingContentFactory(rc3, ResourceFactory.of(r.get(1)), mime, false,
            httpContent -> !httpContent.getResource().getFileName().equals("2.txt"));

        CachingContentFactory rc1 = newCachingContentFactory(rc2, ResourceFactory.of(r.get(0)), mime, false);

        assertEquals("1 - one", getContent(rc1, "1.txt"));
        assertEquals("2 - two", getContent(rc1, "2.txt"));
        assertEquals("3 - three", getContent(rc1, "3.txt"));

        assertEquals("1 - two", getContent(rc2, "1.txt"));
        assertEquals("2 - two", getContent(rc2, "2.txt"));
        assertEquals("3 - three", getContent(rc2, "3.txt"));

        assertNull(getContent(rc3, "1.txt"));
        assertEquals("2 - three", getContent(rc3, "2.txt"));
        assertEquals("3 - three", getContent(rc3, "3.txt"));
    }

    @Test
    public void testNoextension() throws Exception
    {
        Path basePath = createUtilTestResources(workDir.getEmptyPathDir());

        Resource resource = ResourceFactory.root().newResource(basePath.resolve("four"));
        MimeTypes mime = new MimeTypes();

        CachingContentFactory cache = newCachingContentFactory(null, ResourceFactory.of(resource), mime, false);

        assertEquals(getContent(cache, "four.txt"), "4 - four");
        assertEquals(getContent(cache, "four"), "4 - four (no extension)");
    }

    static String getContent(CachingContentFactory rc, String path) throws Exception
    {
        HttpContent content = rc.getContent(path);
        if (content == null)
            return null;

        return IO.toString(content.getResource().newInputStream());
    }
}
