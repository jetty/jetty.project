//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.resource;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RangeWriterTest
{
    public static final String DATA = "01234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWZYZ!@#$%^&*()_+/.,[]";
    private static FileSystem zipfs;

    @AfterAll
    public static void closeZipFs() throws IOException
    {
        if (zipfs != null)
        {
            zipfs.close();
        }
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

    private static Path initZipFsDataFile() throws URISyntaxException, IOException
    {
        Path exampleJar = MavenTestingUtils.getTestResourcePathFile("example.jar");

        URI uri = new URI("jar", exampleJar.toUri().toASCIIString(), null);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        if (zipfs != null)
        {
            // close prior one
            zipfs.close();
        }

        zipfs = FileSystems.newFileSystem(uri, env);
        Path rootPath = zipfs.getRootDirectories().iterator().next();
        return rootPath.resolve("data.dat");
    }

    public static Stream<Arguments> impls() throws IOException, URISyntaxException
    {
        Resource realFileSystemResource = new PathResource(initDataFile());
        Resource nonDefaultFileSystemResource = new PathResource(initZipFsDataFile());

        return Stream.of(
            Arguments.of("Traditional / Direct Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(realFileSystemResource, true))),
            Arguments.of("Traditional / Indirect Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(realFileSystemResource, false))),
            Arguments.of("Traditional / SeekableByteChannel", new SeekableByteChannelRangeWriter(() -> (SeekableByteChannel)realFileSystemResource.getReadableByteChannel())),
            Arguments.of("Traditional / InputStream", new InputStreamRangeWriter(() -> realFileSystemResource.getInputStream())),

            Arguments.of("Non-Default FS / Direct Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(nonDefaultFileSystemResource, true))),
            Arguments.of("Non-Default FS / Indirect Buffer", new ByteBufferRangeWriter(BufferUtil.toBuffer(nonDefaultFileSystemResource, false))),
            Arguments.of("Non-Default FS / SeekableByteChannel", new SeekableByteChannelRangeWriter(() -> (SeekableByteChannel)nonDefaultFileSystemResource.getReadableByteChannel())),
            Arguments.of("Non-Default FS / InputStream", new InputStreamRangeWriter(() -> nonDefaultFileSystemResource.getInputStream()))
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
