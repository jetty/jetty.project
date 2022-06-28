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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiPartsTest
{
    private static final AtomicInteger testCounter = new AtomicInteger();

    private Path _tmpDir;

    @BeforeEach
    public void prepare()
    {
        _tmpDir = MavenTestingUtils.getTargetTestingPath(String.valueOf(testCounter.incrementAndGet()));
    }

    @AfterEach
    public void dispose()
    {
        if (Files.exists(_tmpDir))
            FS.deleteDirectory(_tmpDir);
    }

    @Test
    public void testBadMultiPart() throws Exception
    {
        String boundary = "X0Y0";
        String str = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n" +
            "How now brown cow." +
            "\r\n--" + boundary + "-\r\n" +
            "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n" +
            "\r\n";

        MultiParts multiParts = new MultiParts(boundary);
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(1024);
        multiParts.setMaxLength(3072);
        multiParts.setMaxMemoryFileSize(50);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(str), true));

        multiParts.handle((parts, failure) ->
        {
            assertInstanceOf(BadMessageException.class, failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("bad last boundary"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testFinalBoundaryOnly() throws Exception
    {
        String eol = "\r\n";
        String boundary = "MockMultiPartTestBoundary";

        // Multipart request body containing only an arbitrary string of text,
        // followed by the final boundary marker, delimited by empty lines.
        String str = eol +
            "Hello world" +
            eol +        // Two eol markers, which make an empty line.
            eol +
            "--" + boundary + "--" + eol;

        MultiParts multiParts = new MultiParts(boundary);
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(1024);
        multiParts.setMaxLength(3072);
        multiParts.setMaxMemoryFileSize(50);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(str), true));

        multiParts.whenComplete((parts, failure) ->
        {
            // No errors and no parts.
            assertNull(failure);
            assertNotNull(parts);
            assertEquals(0, parts.size());
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testEmpty() throws Exception
    {
        String eol = "\r\n";
        String boundary = "MockMultiPartTestBoundary";

        String str = eol +
            "--" + boundary + "--" + eol;

        MultiParts multiParts = new MultiParts(boundary);
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(1024);
        multiParts.setMaxLength(3072);
        multiParts.setMaxMemoryFileSize(50);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(str), true));

        multiParts.whenComplete((parts, failure) ->
        {
            // No errors and no parts.
            assertNull(failure);
            assertNotNull(parts);
            assertEquals(0, parts.size());
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testEmptyStringBoundary() throws Exception
    {
        String str = """
            --\r
            Content-Disposition: form-data; name="fileName"\r
            Content-Type: text/plain; charset=US-ASCII\r
            Content-Transfer-Encoding: 8bit\r
            \r
            abc\r
            --\r
            Content-Disposition: form-data; name="desc"\r
            Content-Type: text/plain; charset=US-ASCII\r
            Content-Transfer-Encoding: 8bit\r
            \r
            123\r
            --\r
            Content-Disposition: form-data; name="title"\r
            Content-Type: text/plain; charset=US-ASCII\r
            Content-Transfer-Encoding: 8bit\r
            \r
            ttt\r
            --\r
            Content-Disposition: form-data; name="datafile5239138112980980385.txt"; filename="datafile5239138112980980385.txt"\r
            Content-Type: application/octet-stream; charset=ISO-8859-1\r
            Content-Transfer-Encoding: binary\r
            \r
            000\r
            ----\r
            """;

        MultiParts multiParts = new MultiParts("");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(1024);
        multiParts.setMaxLength(3072);
        multiParts.setMaxMemoryFileSize(50);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(str), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);
        assertThat(parts.size(), is(4));

        MultiPart.Part fileName = parts.getFirst("fileName");
        assertThat(fileName, notNullValue());
        Content.Source partContent = fileName.getContent();
        assertThat(partContent.getLength(), is(3L));
        assertThat(Content.Source.asString(partContent), is("abc"));

        MultiPart.Part desc = parts.getFirst("desc");
        assertThat(desc, notNullValue());
        partContent = desc.getContent();
        assertThat(partContent.getLength(), is(3L));
        assertThat(Content.Source.asString(partContent), is("123"));

        MultiPart.Part title = parts.getFirst("title");
        assertThat(title, notNullValue());
        partContent = title.getContent();
        assertThat(partContent.getLength(), is(3L));
        assertThat(Content.Source.asString(partContent), is("ttt"));

        MultiPart.Part datafile = parts.getFirst("datafile5239138112980980385.txt");
        assertThat(datafile, notNullValue());
        partContent = datafile.getContent();
        assertThat(partContent.getLength(), is(3L));
        assertThat(Content.Source.asString(partContent), is("000"));
    }

    @Test
    public void testNoBody() throws Exception
    {
        MultiParts multiParts = new MultiParts("boundary");
        multiParts.parse(Content.Chunk.from(ByteBuffer.allocate(0), true));

        multiParts.handle((parts, failure) ->
        {
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("unexpected EOF"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testBodyWithOnlyCRLF() throws Exception
    {
        MultiParts multiParts = new MultiParts("boundary");
        String body = "              \n\n\n\r\n\r\n\r\n\r\n";
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        multiParts.handle((parts, failure) ->
        {
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("unexpected EOF"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testLeadingWhitespaceBodyWithCRLF() throws Exception
    {
        String body = """
            

            \r
            \r
            \r
            \r
            --AaB03x\r
            content-disposition: form-data; name="field1"\r
            \r
            Joe Blow\r
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="foo.txt"\r
            Content-Type: text/plain\r
            \r
            aaaabbbbb\r
            --AaB03x--\r
            """;

        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(1024);
        multiParts.setMaxLength(3072);
        multiParts.setMaxMemoryFileSize(50);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        assertThat(parts.size(), is(2));
        MultiPart.Part part1 = parts.getFirst("field1");
        assertThat(part1, notNullValue());
        Content.Source partContent = part1.getContent();
        assertThat(Content.Source.asString(partContent), is("Joe Blow"));
        MultiPart.Part part2 = parts.getFirst("stuff");
        assertThat(part2, notNullValue());
        partContent = part2.getContent();
        assertThat(Content.Source.asString(partContent), is("aaaabbbbb"));
    }

    @Test
    public void testLeadingWhitespaceBodyWithoutCRLF() throws Exception
    {
        String body = """
                        --AaB03x\r
            content-disposition: form-data; name="field1"\r
            \r
            Joe Blow\r
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="foo.txt"\r
            Content-Type: text/plain\r
            \r
            aaaabbbbb\r
            --AaB03x--\r
            """;

        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(1024);
        multiParts.setMaxLength(3072);
        multiParts.setMaxMemoryFileSize(50);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        // The first boundary must be on a new line, so the first "part" is not recognized as such.
        assertThat(parts.size(), is(1));
        MultiPart.Part part2 = parts.getFirst("stuff");
        assertThat(part2, notNullValue());
        Content.Source partContent = part2.getContent();
        assertThat(Content.Source.asString(partContent), is("aaaabbbbb"));
    }

    @Test
    public void testDefaultLimits() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);
        assertThat(parts.size(), is(1));
        MultiPart.Part part = parts.get(0);
        assertEquals("file", part.getName());
        assertEquals("file.txt", part.getFileName());
        // Since the default max memory size is 0, the file is always saved on disk.
        assertThat(part, instanceOf(MultiPart.PathPart.class));
        MultiPart.PathPart pathPart = (MultiPart.PathPart)part;
        assertTrue(Files.exists(pathPart.getPath()));
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", Content.Source.asString(part.getContent()));
    }

    @Test
    public void testRequestContentTooBig() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxLength(16);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        multiParts.handle((parts, failure) ->
        {
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("max length exceeded"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testFileTooBig() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxFileSize(16);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        multiParts.handle((parts, failure) ->
        {
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("max file size exceeded"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testTwoFilesOneInMemoryOneOnDisk() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        String chunk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        multiParts.setMaxMemoryFileSize(chunk.length() + 1);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file1"; filename="file1.txt"\r
            Content-Type: text/plain\r
            \r
            $C\r
            --AaB03x\r
            Content-Disposition: form-data; name="file2"; filename="file2.txt"\r
            Content-Type: text/plain\r
            \r
            $C$C$C$C\r
            --AaB03x--\r
            """.replace("$C", chunk);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);
        assertNotNull(parts);
        assertEquals(2, parts.size());

        MultiPart.Part part1 = parts.get(0);
        assertThat(part1, instanceOf(MultiPart.ChunkPart.class));
        assertEquals(chunk, Content.Source.asString(part1.getContent()));

        MultiPart.Part part2 = parts.get(1);
        assertThat(part2, instanceOf(MultiPart.PathPart.class));
        MultiPart.PathPart pathPart2 = (MultiPart.PathPart)part2;
        assertTrue(Files.exists(pathPart2.getPath()));
        assertEquals(chunk.repeat(4), Content.Source.asString(part2.getContent()));
    }

    @Test
    public void testPartWrite() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        String chunk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        multiParts.setMaxMemoryFileSize(chunk.length() + 1);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file1"; filename="file1.txt"\r
            Content-Type: text/plain\r
            \r
            $C\r
            --AaB03x\r
            Content-Disposition: form-data; name="file2"; filename="file2.txt"\r
            Content-Type: text/plain\r
            \r
            $C$C$C$C\r
            --AaB03x--\r
            """.replace("$C", chunk);
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);
        assertNotNull(parts);
        assertEquals(2, parts.size());

        MultiPart.Part part1 = parts.get(0);
        assertThat(part1, instanceOf(MultiPart.ChunkPart.class));
        Path newPath1 = _tmpDir.resolve("file1.2.txt");
        part1.writeTo(newPath1);
        assertTrue(Files.exists(newPath1));

        MultiPart.Part part2 = parts.get(1);
        assertThat(part2, instanceOf(MultiPart.PathPart.class));
        MultiPart.PathPart pathPart2 = (MultiPart.PathPart)part2;
        assertTrue(Files.exists(pathPart2.getPath()));
        // Create the file in a different directory.
        Path newPath2 = Files.createTempFile("file2.2", ".txt");
        part2.writeTo(newPath2);
        assertTrue(Files.exists(newPath2));
        Files.delete(newPath2);
    }

    @Test
    public void testPathPartDelete() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);
        assertNotNull(parts);
        assertEquals(1, parts.size());

        MultiPart.Part part = parts.get(0);
        assertThat(part, instanceOf(MultiPart.PathPart.class));
        MultiPart.PathPart pathPart = (MultiPart.PathPart)part;
        assertTrue(Files.exists(pathPart.getPath()));
        pathPart.delete();
        assertFalse(Files.exists(pathPart.getPath()));
    }

    @Test
    public void testAbort()
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxMemoryFileSize(32);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="letters"; filename="letters.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x\r
            """;
        String terminator = """
            --AaB03x--\r
            """;
        // Parse only part of the content.
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), false));
        assertEquals(1, multiParts.getParts().size());

        // Abort MultiParts.
        multiParts.completeExceptionally(new IOException());

        // Parse the rest of the content.
        multiParts.parse(Content.Chunk.from(UTF_8.encode(terminator), true));

        // Try to get the parts, it should fail.
        assertThrows(ExecutionException.class, () -> multiParts.get(5, TimeUnit.SECONDS));
        assertEquals(0, multiParts.getParts().size());
    }

    @Test
    public void testMaxHeaderLength() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setHeadersMaxLength(32);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="letters"; filename="letters.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        multiParts.handle((parts, failure) ->
        {
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("headers max length exceeded: 32"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testDefaultCharset() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxMemoryFileSize(-1);

        String body1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="_charset_"\r
            \r
            ISO-8859-1\r
            --AaB03x\r
            Content-Disposition: form-data; name="iso"\r
            Content-Type: text/plain\r
            \r
            """;
        ByteBuffer isoCedilla = ISO_8859_1.encode("ç");
        String body2 = """
            \r
            --AaB03x\r
            Content-Disposition: form-data; name="utf"\r
            Content-Type: text/plain; charset="UTF-8"
            \r
            """;
        ByteBuffer utfCedilla = UTF_8.encode("ç");
        String terminator = """
            \r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body1), false));
        multiParts.parse(Content.Chunk.from(isoCedilla, false));
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body2), false));
        multiParts.parse(Content.Chunk.from(utfCedilla, false));
        multiParts.parse(Content.Chunk.from(UTF_8.encode(terminator), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        Charset defaultCharset = multiParts.getDefaultCharset();
        assertEquals(ISO_8859_1, defaultCharset);

        MultiPart.Part iso = parts.getFirst("iso");
        String cedilla = iso.getContentAsString(defaultCharset);
        assertEquals("ç", cedilla);

        MultiPart.Part utf = parts.getFirst("utf");
        cedilla = utf.getContentAsString(defaultCharset);
        assertEquals("ç", cedilla);
    }

    @Test
    public void testPartWithBackSlashInFileName() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="Taken on Aug 22 \\ 2012.jpg"\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(contents), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        assertThat(parts.size(), is(1));
        MultiPart.Part part = parts.get(0);
        assertThat(part.getFileName(), is("Taken on Aug 22 \\ 2012.jpg"));
    }

    @Test
    public void testPartWithWindowsFileName() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(contents), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        assertThat(parts.size(), is(1));
        MultiPart.Part part = parts.get(0);
        assertThat(part.getFileName(), is("c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"));
    }

    @Test
    public void testCorrectlyEncodedMSFilename() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="c:\\\\this\\\\really\\\\is\\\\some\\\\path\\\\to\\\\a\\\\file.txt"\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(contents), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        assertThat(parts.size(), is(1));
        MultiPart.Part part = parts.get(0);
        assertThat(part.getFileName(), is("c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"));
    }

    @Test
    public void testWriteFilesForPartWithoutFileName() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);
        multiParts.setUseFilesForPartsWithoutFileName(true);

        String body = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"\r
            Content-Type: text/plain\r
            \r
            sssaaa\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(body), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        assertThat(parts.size(), is(1));
        MultiPart.Part part = parts.get(0);
        assertThat(part, instanceOf(MultiPart.PathPart.class));

        MultiPart.PathPart pathPart = (MultiPart.PathPart)part;
        assertTrue(Files.exists(pathPart.getPath()));
    }

    @Test
    public void testPartsWithSameName() throws Exception
    {
        MultiParts multiParts = new MultiParts("AaB03x");
        multiParts.setFileDirectory(_tmpDir);

        String sameNames = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="stuff1.txt"\r
            Content-Type: text/plain\r
            \r
            00000\r
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="stuff2.txt"\r
            Content-Type: text/plain\r
            \r
            AAAAA\r
            --AaB03x--\r
            """;
        multiParts.parse(Content.Chunk.from(UTF_8.encode(sameNames), true));

        MultiParts.Parts parts = multiParts.get(5, TimeUnit.SECONDS);

        assertEquals(2, parts.size());

        List<MultiPart.Part> partsList = parts.getAll("stuff");

        assertEquals(2, partsList.size());

        MultiPart.Part part1 = partsList.get(0);
        assertEquals("stuff1.txt", part1.getFileName());
        assertEquals("00000", part1.getContentAsString(multiParts.getDefaultCharset()));

        MultiPart.Part part2 = partsList.get(1);
        assertEquals("stuff2.txt", part2.getFileName());
        assertEquals("AAAAA", part2.getContentAsString(multiParts.getDefaultCharset()));
    }
}
