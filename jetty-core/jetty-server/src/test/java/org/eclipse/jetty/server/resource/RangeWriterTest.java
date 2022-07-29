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

package org.eclipse.jetty.server.resource;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@Disabled // TODO should not be a writer
public class RangeWriterTest
{
    public static final String DATA = "01234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWZYZ!@#$%^&*()_+/.,[]";
    private static ResourceFactory.Closeable resourceFactory;

    @BeforeAll
    public static void init()
    {
        resourceFactory = ResourceFactory.closeable();
    }

    @AfterAll
    public static void closeResourceFactory()
    {
        IO.close(resourceFactory);
    }

    public static Path initDataFile() throws IOException
    {
        Path testDir = MavenTestingUtils.getTargetTestingPath(RangeWriterTest.class.getSimpleName());
        FS.ensureEmpty(testDir);

        Path dataFile = testDir.resolve("data.dat");
        try (BufferedWriter writer = Files.newBufferedWriter(dataFile, UTF_8, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        {
            writer.write(DATA);
            writer.flush();
        }

        return dataFile;
    }

    private static Path initZipFsDataFile()
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        URI jarFileUri = Resource.toJarFileUri(exampleJar.toUri());

        Resource zipfs = resourceFactory.newResource(jarFileUri);
        Path rootPath = zipfs.getPath();
        return rootPath.resolve("data.dat");
    }

    public static Stream<Arguments> impls() throws IOException
    {
        Resource realFileSystemResource = resourceFactory.newResource(initDataFile());
        Resource nonDefaultFileSystemResource = resourceFactory.newResource(initZipFsDataFile());

        return Stream.of(
            Arguments.of("Traditional / Direct Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(realFileSystemResource, true))),
            Arguments.of("Traditional / Indirect Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(realFileSystemResource, false))),
            // TODO the cast to SeekableByteChannel is questionable
            Arguments.of("Traditional / SeekableByteChannel", new SeekableByteChannelRangeWriter(() -> (SeekableByteChannel)realFileSystemResource.newReadableByteChannel())),
            Arguments.of("Traditional / InputStream", new InputStreamRangeWriter(() -> realFileSystemResource.newInputStream())),

            Arguments.of("Non-Default FS / Direct Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(nonDefaultFileSystemResource, true))),
            Arguments.of("Non-Default FS / Indirect Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(nonDefaultFileSystemResource, false))),
            // TODO the cast to SeekableByteChannel is questionable
            Arguments.of("Non-Default FS / SeekableByteChannel", new SeekableByteChannelRangeWriter(() -> (SeekableByteChannel)nonDefaultFileSystemResource.newReadableByteChannel())),
            Arguments.of("Non-Default FS / InputStream", new InputStreamRangeWriter(() -> nonDefaultFileSystemResource.newInputStream()))
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("impls")
    public void testSimpleRange(String description, RangeWriter rangeWriter) throws IOException
    {
        ByteArrayOutputStream outputStream;

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 10, 50);
        assertThat("Range: 10 (len=50)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(10, 60)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("impls")
    public void testSameRangeMultipleTimes(String description, RangeWriter rangeWriter) throws IOException
    {
        ByteArrayOutputStream outputStream;

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 10, 50);
        assertThat("Range(a): 10 (len=50)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(10, 60)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 10, 50);
        assertThat("Range(b): 10 (len=50)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(10, 60)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("impls")
    public void testMultipleRangesOrdered(String description, RangeWriter rangeWriter) throws IOException
    {
        ByteArrayOutputStream outputStream;

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 10, 20);
        assertThat("Range(a): 10 (len=20)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(10, 10 + 20)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 35, 10);
        assertThat("Range(b): 35 (len=10)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(35, 35 + 10)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 55, 10);
        assertThat("Range(b): 55 (len=10)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(55, 55 + 10)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("impls")
    public void testMultipleRangesOverlapping(String description, RangeWriter rangeWriter) throws IOException
    {
        ByteArrayOutputStream outputStream;

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 10, 20);
        assertThat("Range(a): 10 (len=20)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(10, 10 + 20)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 15, 20);
        assertThat("Range(b): 15 (len=20)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(15, 15 + 20)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 20, 20);
        assertThat("Range(b): 20 (len=20)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(20, 20 + 20)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("impls")
    public void testMultipleRangesReverseOrder(String description, RangeWriter rangeWriter) throws IOException
    {
        ByteArrayOutputStream outputStream;

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 55, 10);
        assertThat("Range(b): 55 (len=10)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(55, 55 + 10)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 35, 10);
        assertThat("Range(b): 35 (len=10)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(35, 35 + 10)));

        outputStream = new ByteArrayOutputStream();
        rangeWriter.writeTo(outputStream, 10, 20);
        assertThat("Range(a): 10 (len=20)", new String(outputStream.toByteArray(), UTF_8), is(DATA.substring(10, 10 + 20)));
    }
}
