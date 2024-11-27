//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiPartFormDataTest
{
    private static final String CR = "\r";
    private static final String LF = "\n";
    private static final AtomicInteger testCounter = new AtomicInteger();

    private Path _tmpDir;
    private List<Content.Chunk> _allocatedChunks;

    @BeforeEach
    public void prepare()
    {
        _tmpDir = MavenTestingUtils.getTargetTestingPath(String.valueOf(testCounter.incrementAndGet()));
        _allocatedChunks = new ArrayList<>();
    }

    @AfterEach
    public void dispose()
    {
        if (Files.exists(_tmpDir))
            FS.deleteDirectory(_tmpDir);
        int leaks = 0;
        for (Content.Chunk chunk : _allocatedChunks)
        {
            // Any release that does not throw or return true is a leak.
            try
            {
                if (!chunk.release())
                    leaks++;
            }
            catch (IllegalStateException ignored)
            {
            }
        }
        assertThat("Leaked " + leaks + "/" + _allocatedChunks.size() + " chunk(s)", leaks, is(0));
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

        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(1024);
        formData.setMaxLength(3072);
        formData.setMaxMemoryFileSize(50);
        Content.Sink.write(source, true, str, Callback.NOOP);
        formData.parse(source).handle((parts, failure) ->
        {
            assertNull(parts);
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

        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(1024);
        formData.setMaxLength(3072);
        formData.setMaxMemoryFileSize(50);
        Content.Sink.write(source, true, str, Callback.NOOP);
        formData.parse(source).whenComplete((parts, failure) ->
        {
            // No errors and no parts.
            assertNull(failure);
            assertNotNull(parts);
            assertEquals(0, parts.size());
            parts.close();
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testEmpty() throws Exception
    {
        String eol = "\r\n";
        String boundary = "MockMultiPartTestBoundary";

        String str = eol +
            "--" + boundary + "--" + eol;

        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(1024);
        formData.setMaxLength(3072);
        formData.setMaxMemoryFileSize(50);
        Content.Sink.write(source, true, str, Callback.NOOP);
        formData.parse(source).whenComplete((parts, failure) ->
        {
            // No errors and no parts.
            assertNull(failure);
            assertNotNull(parts);
            assertEquals(0, parts.size());
            parts.close();
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

        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(1024);
        formData.setMaxLength(3072);
        formData.setMaxMemoryFileSize(50);
        Content.Sink.write(source, true, str, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(4));

            MultiPart.Part fileName = parts.getFirst("fileName");
            assertThat(fileName, notNullValue());
            Content.Source partContent = fileName.getContentSource();
            assertThat(partContent.getLength(), is(3L));
            assertThat(Content.Source.asString(partContent), is("abc"));

            MultiPart.Part desc = parts.getFirst("desc");
            assertThat(desc, notNullValue());
            partContent = desc.getContentSource();
            assertThat(partContent.getLength(), is(3L));
            assertThat(Content.Source.asString(partContent), is("123"));

            MultiPart.Part title = parts.getFirst("title");
            assertThat(title, notNullValue());
            partContent = title.getContentSource();
            assertThat(partContent.getLength(), is(3L));
            assertThat(Content.Source.asString(partContent), is("ttt"));

            MultiPart.Part datafile = parts.getFirst("datafile5239138112980980385.txt");
            assertThat(datafile, notNullValue());
            partContent = datafile.getContentSource();
            assertThat(partContent.getLength(), is(3L));
            assertThat(Content.Source.asString(partContent), is("000"));
        }
    }

    @Test
    public void testContentTransferEncodingQuotedPrintable() throws Exception
    {
        String boundary = "BEEF";
        String str = """
            --$B\r
            Content-Disposition: form-data; name="greeting"\r
            Content-Type: text/plain; charset=US-ASCII\r
            Content-Transfer-Encoding: quoted-printable\r
            \r
            Hello World\r
            --$B--\r
            """.replace("$B", boundary);

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578, violations);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));

            MultiPart.Part greeting = parts.getFirst("greeting");
            assertThat(greeting, notNullValue());
            Content.Source partContent = greeting.getContentSource();
            assertThat(partContent.getLength(), is(11L));
            assertThat(Content.Source.asString(partContent), is("Hello World"));

            List<ComplianceViolation.Event> events = violations.getEvents();
            assertThat(events.size(), is(1));
            ComplianceViolation.Event event = events.get(0);
            assertThat(event.violation(), is(MultiPartCompliance.Violation.QUOTED_PRINTABLE_TRANSFER_ENCODING));
        }
    }

    @Test
    public void testLFOnlyNoCRInPreviousChunk() throws Exception
    {
        String str1 = """
            --BEEF\r
            Content-Disposition: form-data; name="greeting"\r
            Content-Type: text/plain; charset=US-ASCII\r
            \r
            """;
        String str2 = "Hello World"; // not ending with CR
        String str3 = """
            \n--BEEF--\r
            """;

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("BEEF", MultiPartCompliance.RFC7578, violations);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, false, str1, Callback.NOOP);
        Content.Sink.write(source, false, str2, Callback.NOOP);
        Content.Sink.write(source, true, str3, Callback.NOOP);

        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));

            MultiPart.Part greeting = parts.getFirst("greeting");
            assertThat(greeting, notNullValue());
            Content.Source partContent = greeting.getContentSource();
            assertThat(partContent.getLength(), is(11L));
            assertThat(Content.Source.asString(partContent), is("Hello World"));

            List<ComplianceViolation.Event> events = violations.getEvents();
            assertThat(events.size(), is(1));
            ComplianceViolation.Event event = events.get(0);
            assertThat(event.violation(), is(MultiPartCompliance.Violation.LF_LINE_TERMINATION));
        }
    }

    @Test
    public void testLFOnlyNoCRInCurrentChunk() throws Exception
    {
        String str1 = """
            --BEEF\r
            Content-Disposition: form-data; name="greeting"\r
            Content-Type: text/plain; charset=US-ASCII\r
            \r
            """;
        // Do not end Hello World with "\r".
        String str2 = """
            Hello World\n--BEEF--\r
            """;

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("BEEF", MultiPartCompliance.RFC7578, violations);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, false, str1, Callback.NOOP);
        Content.Sink.write(source, true, str2, Callback.NOOP);

        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));

            MultiPart.Part greeting = parts.getFirst("greeting");
            assertThat(greeting, notNullValue());
            Content.Source partContent = greeting.getContentSource();
            assertThat(partContent.getLength(), is(11L));
            assertThat(Content.Source.asString(partContent), is("Hello World"));

            List<ComplianceViolation.Event> events = violations.getEvents();
            assertThat(events.size(), is(1));
            ComplianceViolation.Event event = events.get(0);
            assertThat(event.violation(), is(MultiPartCompliance.Violation.LF_LINE_TERMINATION));
        }
    }

    @Test
    public void testLFOnlyEOLLenient() throws Exception
    {
        String boundary = "BEEF";
        String str = """
            --$B
            Content-Disposition: form-data; name="greeting"
            Content-Type: text/plain; charset=US-ASCII
            
            Hello World
            --$B--
            """.replace("$B", boundary);

        assertThat("multipart str cannot contain CR for this test", str, not(containsString(CR)));

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578, violations);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));

            MultiPart.Part greeting = parts.getFirst("greeting");
            assertThat(greeting, notNullValue());
            Content.Source partContent = greeting.getContentSource();
            assertThat(partContent.getLength(), is(11L));
            assertThat(Content.Source.asString(partContent), is("Hello World"));

            List<ComplianceViolation.Event> events = violations.getEvents();
            assertThat(events.size(), is(1));
            ComplianceViolation.Event event = events.get(0);
            assertThat(event.violation(), is(MultiPartCompliance.Violation.LF_LINE_TERMINATION));
        }
    }

    @Test
    public void testLFOnlyEOLStrict()
    {
        String boundary = "BEEF";
        String str = """
            --$B
            Content-Disposition: form-data; name="greeting"
            Content-Type: text/plain; charset=US-ASCII
            
            Hello World
            --$B--
            """.replace("$B", boundary);

        assertThat("multipart str cannot contain CR for this test", str, not(containsString(CR)));

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578_STRICT, violations);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        ExecutionException ee = assertThrows(ExecutionException.class, () -> formData.parse(source).get(5, TimeUnit.SECONDS));
        assertThat(ee.getCause(), instanceOf(BadMessageException.class));
        BadMessageException bme = (BadMessageException)ee.getCause();
        assertThat(bme.getMessage(), containsString("invalid LF-only EOL"));
    }

    /**
     * Test of parsing where there is whitespace before the boundary.
     *
     * @see MultiPartCompliance.Violation#WHITESPACE_BEFORE_BOUNDARY
     */
    @Test
    public void testWhiteSpaceBeforeBoundary()
    {
        String boundary = "BEEF";
        String str = """
            preamble\r
             --$B\r
            Content-Disposition: form-data; name="greeting"\r
            Content-Type: text/plain; charset=US-ASCII\r
            \r
            Hello World\r
             --$B--\r
            """.replace("$B", boundary);

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578, violations);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        ExecutionException ee = assertThrows(ExecutionException.class, () -> formData.parse(source).get());
        assertThat(ee.getCause(), instanceOf(EOFException.class));
        EOFException bme = (EOFException)ee.getCause();
        assertThat(bme.getMessage(), containsString("unexpected EOF"));
    }

    @Test
    public void testCROnlyEOL()
    {
        String boundary = "BEEF";
        String str = """
            --$B
            Content-Disposition: form-data; name="greeting"
            Content-Type: text/plain; charset=US-ASCII
            
            Hello World
            --$B--
            """.replace("$B", boundary);

        // change every '\n' LF to a CR.
        str = str.replace(LF, CR);

        assertThat("multipart str cannot contain LF for this test", str, not(containsString(LF)));

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578, violations);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        ExecutionException ee = assertThrows(ExecutionException.class, () -> formData.parse(source).get(5, TimeUnit.SECONDS));
        assertThat(ee.getCause(), instanceOf(BadMessageException.class));
        BadMessageException bme = (BadMessageException)ee.getCause();
        assertThat(bme.getMessage(), containsString("invalid CR-only EOL"));
    }

    @Test
    public void testTooManyCRs()
    {
        String boundary = "BEEF";
        String str = """
            --$B
            Content-Disposition: form-data; name="greeting"
            Content-Type: text/plain; charset=US-ASCII
            
            Hello World
            --$B--
            """.replace("$B", boundary);

        // change every '\n' LF to a multiple CR then a LF.
        str = str.replace("\n", "\r\r\r\r\r\r\r\n");

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578, violations);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        ExecutionException ee = assertThrows(ExecutionException.class, () -> formData.parse(source).get());
        assertThat(ee.getCause(), instanceOf(BadMessageException.class));
        BadMessageException bme = (BadMessageException)ee.getCause();
        assertThat(bme.getMessage(), containsString("invalid CR-only EOL"));
    }

    @Test
    public void testContentTransferEncodingBase64() throws Exception
    {
        String boundary = "BEEF";
        String str = """
            --$B\r
            Content-Disposition: form-data; name="greeting"\r
            Content-Type: text/plain; charset=US-ASCII\r
            Content-Transfer-Encoding: base64\r
            \r
            SGVsbG8gV29ybGQK\r
            --$B--\r
            """.replace("$B", boundary);

        AsyncContent source = new TestContent();
        CaptureMultiPartViolations violations = new CaptureMultiPartViolations();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary, MultiPartCompliance.RFC7578, violations);
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);
        Content.Sink.write(source, true, str, Callback.NOOP);

        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));

            MultiPart.Part greeting = parts.getFirst("greeting");
            assertThat(greeting, notNullValue());
            Content.Source partContent = greeting.getContentSource();
            assertThat(partContent.getLength(), is(16L));
            assertThat(Content.Source.asString(partContent), is("SGVsbG8gV29ybGQK"));

            List<ComplianceViolation.Event> events = violations.getEvents();
            assertThat(events.size(), is(1));
            ComplianceViolation.Event event = events.get(0);
            assertThat(event.violation(), is(MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING));
        }
    }

    @Test
    public void testNoBody() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("boundary");
        source.close();
        formData.parse(source).handle((parts, failure) ->
        {
            assertNull(parts);
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("unexpected EOF"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testBodyWithOnlyCRLF() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("boundary");
        String body = "              \n\n\n\r\n\r\n\r\n\r\n";
        Content.Sink.write(source, true, body, Callback.NOOP);
        formData.parse(source).handle((parts, failure) ->
        {
            assertNull(parts);
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

        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(1024);
        formData.setMaxLength(3072);
        formData.setMaxMemoryFileSize(50);
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(2));
            MultiPart.Part part1 = parts.getFirst("field1");
            assertThat(part1, notNullValue());
            Content.Source partContent = part1.getContentSource();
            assertThat(Content.Source.asString(partContent), is("Joe Blow"));
            MultiPart.Part part2 = parts.getFirst("stuff");
            assertThat(part2, notNullValue());
            partContent = part2.getContentSource();
            assertThat(Content.Source.asString(partContent), is("aaaabbbbb"));
        }
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

        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(1024);
        formData.setMaxLength(3072);
        formData.setMaxMemoryFileSize(50);
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            // The first boundary must be on a new line, so the first "part" is not recognized as such.
            assertThat(parts.size(), is(1));
            MultiPart.Part part2 = parts.getFirst("stuff");
            assertThat(part2, notNullValue());
            Content.Source partContent = part2.getContentSource();
            assertThat(Content.Source.asString(partContent), is("aaaabbbbb"));
        }
    }

    @Test
    public void testDefaultLimits() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));
            MultiPart.Part part = parts.get(0);
            assertEquals("file", part.getName());
            assertEquals("file.txt", part.getFileName());
            // Since the default max memory size is 0, the file is always saved on disk.
            assertThat(part, instanceOf(MultiPart.PathPart.class));
            MultiPart.PathPart pathPart = (MultiPart.PathPart)part;
            assertTrue(Files.exists(pathPart.getPath()));
            assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", Content.Source.asString(part.getContentSource()));
        }
    }

    @Test
    public void testRequestContentTooBig() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxLength(16);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);
        formData.parse(source).handle((parts, failure) ->
        {
            assertNull(parts);
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("max length exceeded"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testFileTooBig() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxFileSize(16);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);
        formData.parse(source).handle((parts, failure) ->
        {
            assertNull(parts);
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("max file size exceeded"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testTwoFilesOneInMemoryOneOnDisk() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        String chunk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        formData.setMaxMemoryFileSize(chunk.length() + 1);

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
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertNotNull(parts);
            assertEquals(2, parts.size());

            MultiPart.Part part1 = parts.get(0);
            assertThat(part1, instanceOf(MultiPart.ChunksPart.class));
            assertEquals(chunk, Content.Source.asString(part1.getContentSource()));

            MultiPart.Part part2 = parts.get(1);
            assertThat(part2, instanceOf(MultiPart.PathPart.class));
            MultiPart.PathPart pathPart2 = (MultiPart.PathPart)part2;
            assertTrue(Files.exists(pathPart2.getPath()));
            assertEquals(chunk.repeat(4), Content.Source.asString(part2.getContentSource()));
        }
    }

    @Test
    public void testPartWrite() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        String chunk = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        formData.setMaxMemoryFileSize(chunk.length() + 1);

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
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertNotNull(parts);
            assertEquals(2, parts.size());

            MultiPart.Part part1 = parts.get(0);
            assertThat(part1, instanceOf(MultiPart.ChunksPart.class));
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
    }

    @Test
    public void testPathPartDelete() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertNotNull(parts);
            assertEquals(1, parts.size());

            MultiPart.Part part = parts.get(0);
            assertThat(part, instanceOf(MultiPart.PathPart.class));
            MultiPart.PathPart pathPart = (MultiPart.PathPart)part;
            Path path = pathPart.getPath();
            assertTrue(Files.exists(path));
            pathPart.delete();
            assertFalse(Files.exists(path));
        }
    }

    @Test
    public void testAbort()
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(32);

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
        Content.Sink.write(source, false, body, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        assertEquals(1, formData.getPartsSize());

        // Abort MultiPartFormData.
        futureParts.completeExceptionally(new IOException());

        // Parse the rest of the content.
        Content.Sink.write(source, true, terminator, Callback.NOOP);

        // Try to get the parts, it should fail.
        assertThrows(ExecutionException.class, () -> futureParts.get(5, TimeUnit.SECONDS));
        assertEquals(0, formData.getPartsSize());
    }

    @Test
    public void testMaxHeaderLength() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setPartHeadersMaxLength(32);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="letters"; filename="letters.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);
        formData.parse(source).handle((parts, failure) ->
        {
            assertNull(parts);
            assertNotNull(failure);
            assertThat(failure.getMessage(), containsStringIgnoringCase("headers max length exceeded: 32"));
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testDefaultCharset() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);

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
            Content-Type: text/plain; charset="UTF-8"\r
            \r
            """;
        ByteBuffer utfCedilla = UTF_8.encode("ç");
        String terminator = """
            \r
            --AaB03x--\r
            """;
        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        Content.Sink.write(source, false, body1, Callback.NOOP);
        source.write(false, isoCedilla, Callback.NOOP);
        Content.Sink.write(source, false, body2, Callback.NOOP);
        source.write(false, utfCedilla, Callback.NOOP);
        Content.Sink.write(source, true, terminator, Callback.NOOP);

        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            Charset defaultCharset = formData.getDefaultCharset();
            assertEquals(ISO_8859_1, defaultCharset);

            MultiPart.Part iso = parts.getFirst("iso");
            String cedilla = iso.getContentAsString(defaultCharset);
            assertEquals("ç", cedilla);

            MultiPart.Part utf = parts.getFirst("utf");
            cedilla = utf.getContentAsString(defaultCharset);
            assertEquals("ç", cedilla);
        }
    }

    @Test
    public void testChunkEndsWithCRNextChunkEndsWithLF() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // First chunk must end with CR.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Second chunk must end with LF.
        String chunk2 = """
            DEF
            """;
        String terminator = """
            \r
            --AaB03x--\r
            """;
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, false, chunk2, Callback.NOOP);
        Content.Sink.write(source, true, terminator, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n\rDEF\n", content);
        }
    }

    @Test
    public void testChunkEndsWithCRNextChunkHasPartialBoundaryMatch() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // First chunk must end with CR.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Second chunk must have a partial boundary that is actually content.
        String chunk2 = "\n--AaB0";
        String terminator = """
            \r
            --AaB03x--\r
            """;
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, false, chunk2, Callback.NOOP);
        Content.Sink.write(source, true, terminator, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n\r\n--AaB0", content);
        }
    }

    @Test
    public void testChunkEndsWithCRNextChunkHasPartialBoundaryMatchNextChunkHasRemainingBoundary() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // First chunk must end with CR.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Second chunk must have a partial boundary that is actually content.
        String chunk2 = "\n--AaB0";
        String chunk3 = "3x--\r\n";
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, false, chunk2, Callback.NOOP);
        Content.Sink.write(source, true, chunk3, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n", content);
        }
    }

    @Test
    public void testChunkEndsWithCRNextChunkHasPartialBoundaryMatchNextChunksHaveRemainingBoundary() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // First chunk must end with CR.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Second chunk must have a partial boundary that is actually content.
        String chunk2 = "\n--AaB0";
        String chunk3 = "3x";
        String chunk4 = "--\r\n";
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, false, chunk2, Callback.NOOP);
        Content.Sink.write(source, false, chunk3, Callback.NOOP);
        Content.Sink.write(source, true, chunk4, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n", content);
        }
    }

    @Test
    public void testChunkEndsWithCRNextChunkHasPartialBoundaryMatchNextChunkHasContent() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // First chunk must end with CR.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Second chunk must have a partial boundary that is actually content.
        String chunk2 = "\n--AaB0";
        String chunk3 = """
            -CONTENT\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, false, chunk2, Callback.NOOP);
        Content.Sink.write(source, true, chunk3, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n\r\n--AaB0-CONTENT", content);
        }
    }

    @Test
    public void testChunkEndsWithCRNextChunkHasPartialBoundaryMatchNextChunksHaveContent() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // First chunk must end with CR.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Second chunk must have a partial boundary that is actually content.
        String chunk2 = "\n--AaB";
        String chunk3 = "03";
        String chunk4 = """
            -CONTENT\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, false, chunk2, Callback.NOOP);
        Content.Sink.write(source, false, chunk3, Callback.NOOP);
        Content.Sink.write(source, true, chunk4, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n\r\n--AaB03-CONTENT", content);
        }
    }

    @Test
    public void testOneByteChunks() throws Exception
    {
        String boundary = "AaB03x";
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
        formData.setMaxMemoryFileSize(-1);

        String form = """
            --$B\r
            Content-Disposition: form-data; name="spaces"\r
            Content-Type: text/plain\r
            \r
            ABC\n\rDEF\n
            --$B--
            """.replace("$B", boundary);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);

        new Thread(() ->
        {
            ByteBuffer buf = UTF_8.encode(form);
            while (buf.hasRemaining())
            {
                source.write(false, ByteBuffer.wrap(new byte[]{buf.get()}), Callback.NOOP);
            }
            source.write(true, BufferUtil.EMPTY_BUFFER, Callback.NOOP);
        }).start();

        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part part = parts.get(0);
            String content = part.getContentAsString(UTF_8);

            assertEquals("ABC\n\rDEF\n", content);
        }
    }

    @Test
    public void testSecondChunkIsTerminator() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setMaxMemoryFileSize(-1);

        // This chunk must end with \r.
        String chunk1 = """
            --AaB03x\r
            Content-Disposition: form-data; name="j"\r
            Content-Type: text/plain\r
            \r
            ABC
            """ + "\r";
        // Terminator must start with \n--Boundary.
        String terminator = """
            \n--AaB03x--\r
            """;
        Content.Sink.write(source, false, chunk1, Callback.NOOP);
        Content.Sink.write(source, true, terminator, Callback.NOOP);

        CompletableFuture<MultiPartFormData.Parts> futureParts = formData.parse(source);
        try (MultiPartFormData.Parts parts = futureParts.get(5, TimeUnit.SECONDS))
        {
            assertEquals(1, parts.size());

            MultiPart.Part spaces = parts.get(0);
            String content = spaces.getContentAsString(UTF_8);

            assertEquals("ABC\n", content);
        }
    }

    @Test
    public void testPartWithBackSlashInFileName() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="C:\\Pictures\\4th May 2012.jpg"\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, contents, Callback.NOOP);

        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));
            MultiPart.Part part = parts.get(0);
            assertThat(part.getFileName(), is("C:\\Pictures\\4th May 2012.jpg"));
        }
    }

    @Test
    public void testPartWithWindowsFileName() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"; filename="c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, contents, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));
            MultiPart.Part part = parts.get(0);
            assertThat(part.getFileName(), is("c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"));
        }
    }

    @Test
    public void testCorrectlyEncodedFilename() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            Content-Disposition: form-data; name="stuff"; filename="file.txt"; filename*=UTF-8''file%20%E2%9C%93.txt\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, contents, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));
            MultiPart.Part part = parts.get(0);
            assertThat(part.getFileName(), is("file ✓.txt"));
        }
    }

    @Test
    public void testCorrectlyEncodedMSFilename() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setMaxMemoryFileSize(-1);

        String contents = """
            --AaB03x\r
            Content-Disposition: form-data; name="stuff"; filename="c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"\r
            Content-Type: text/plain\r
            \r
            stuffaaa\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, contents, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));
            MultiPart.Part part = parts.get(0);
            assertThat(part.getFileName(), is("c:\\this\\really\\is\\some\\path\\to\\a\\file.txt"));
        }
    }

    @Test
    public void testWriteFilesForPartWithoutFileName() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        formData.setUseFilesForPartsWithoutFileName(true);

        String body = """
            --AaB03x\r
            content-disposition: form-data; name="stuff"\r
            Content-Type: text/plain\r
            \r
            sssaaa\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(1));
            MultiPart.Part part = parts.get(0);
            assertThat(part, instanceOf(MultiPart.PathPart.class));

            MultiPart.PathPart pathPart = (MultiPart.PathPart)part;
            assertTrue(Files.exists(pathPart.getPath()));
        }
    }

    @Test
    public void testPartsWithSameName() throws Exception
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);

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
        Content.Sink.write(source, true, sameNames, Callback.NOOP);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertEquals(2, parts.size());

            List<MultiPart.Part> partsList = parts.getAll("stuff");

            assertEquals(2, partsList.size());

            MultiPart.Part part1 = partsList.get(0);
            assertEquals("stuff1.txt", part1.getFileName());
            assertEquals("00000", part1.getContentAsString(formData.getDefaultCharset()));

            MultiPart.Part part2 = partsList.get(1);
            assertEquals("stuff2.txt", part2.getFileName());
            assertEquals("AAAAA", part2.getContentAsString(formData.getDefaultCharset()));
        }
    }

    @Test
    public void testContentSourceCanBeFailed()
    {
        MultiPartFormData.ContentSource source = new MultiPartFormData.ContentSource("boundary");
        source.addPart(new MultiPart.ChunksPart("part1", "file1", HttpFields.EMPTY, List.of(
            Content.Chunk.from(ByteBuffer.wrap("the answer".getBytes(US_ASCII)), false),
            Content.Chunk.from(new NumberFormatException(), false),
            Content.Chunk.from(ByteBuffer.wrap(" is 42".getBytes(US_ASCII)), true)
        )));
        source.close();

        Content.Chunk chunk;
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("--boundary\r\n"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("Content-Disposition: form-data; name=\"part1\"; filename=\"file1\"\r\n\r\n"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("the answer"));

        chunk = source.read();
        assertThat(Content.Chunk.isFailure(chunk, false), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));
        source.fail(chunk.getFailure());

        chunk = source.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));
    }

    @Test
    public void testTransientFailuresAreReturned()
    {
        MultiPartFormData.ContentSource source = new MultiPartFormData.ContentSource("boundary");
        source.addPart(new MultiPart.ChunksPart("part1", "file1", HttpFields.EMPTY, List.of(
            Content.Chunk.from(ByteBuffer.wrap("the answer".getBytes(US_ASCII)), false),
            Content.Chunk.from(new NumberFormatException(), false),
            Content.Chunk.from(ByteBuffer.wrap(" is 42".getBytes(US_ASCII)), true)
        )));
        source.close();

        Content.Chunk chunk;
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("--boundary\r\n"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("Content-Disposition: form-data; name=\"part1\"; filename=\"file1\"\r\n\r\n"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("the answer"));

        chunk = source.read();
        assertThat(Content.Chunk.isFailure(chunk, false), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));

        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is(" is 42"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("\r\n--boundary--\r\n"));

        chunk = source.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(Content.Chunk.isFailure(chunk), is(false));
        assertThat(chunk.hasRemaining(), is(false));
    }

    @Test
    public void testTerminalFailureIsTerminal()
    {
        MultiPartFormData.ContentSource source = new MultiPartFormData.ContentSource("boundary");
        source.addPart(new MultiPart.ChunksPart("part1", "file1", HttpFields.EMPTY, List.of(
            Content.Chunk.from(ByteBuffer.wrap("the answer".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap(" is 42".getBytes(US_ASCII)), false),
            Content.Chunk.from(new NumberFormatException(), true)
        )));
        source.close();

        Content.Chunk chunk;
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("--boundary\r\n"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("Content-Disposition: form-data; name=\"part1\"; filename=\"file1\"\r\n\r\n"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("the answer"));
        chunk = source.read();
        assertThat(chunk.isLast(), is(false));
        assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is(" is 42"));

        chunk = source.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));

        chunk = source.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));
    }

    @Test
    public void testMissingFilesDirectory()
    {
        AsyncContent source = new TestContent();
        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        // Always save to disk.
        formData.setMaxMemoryFileSize(0);

        String body = """
            --AaB03x\r
            Content-Disposition: form-data; name="file1"; filename="file.txt"\r
            Content-Type: text/plain\r
            \r
            ABCDEFGHIJKLMNOPQRSTUVWXYZ\r
            --AaB03x--\r
            """;
        Content.Sink.write(source, true, body, Callback.NOOP);

        Throwable cause = assertThrows(ExecutionException.class, () -> formData.parse(source).get(5, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
    }

    @Test
    public void testNonRetainableContent() throws Exception
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

        Content.Source source = new InputStreamContentSource(new ByteArrayInputStream(body.getBytes(ISO_8859_1)))
        {
            @Override
            public Content.Chunk read()
            {
                Content.Chunk chunk = super.read();
                return new NonRetainableChunk(chunk);
            }
        };

        MultiPartFormData.Parser formData = new MultiPartFormData.Parser("AaB03x");
        formData.setFilesDirectory(_tmpDir);
        try (MultiPartFormData.Parts parts = formData.parse(source).get(5, TimeUnit.SECONDS))
        {
            assertThat(parts.size(), is(2));
            MultiPart.Part part1 = parts.getFirst("field1");
            assertThat(part1, notNullValue());
            Content.Source partContent = part1.getContentSource();
            assertThat(Content.Source.asString(partContent), is("Joe Blow"));
            MultiPart.Part part2 = parts.getFirst("stuff");
            assertThat(part2, notNullValue());
            partContent = part2.getContentSource();
            assertThat(Content.Source.asString(partContent), is("aaaabbbbb"));
        }
    }

    private class TestContent extends AsyncContent
    {
        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = super.read();
            if (chunk != null && chunk.canRetain())
                _allocatedChunks.add(chunk);
            return chunk;
        }
    }

    private static class NonRetainableChunk extends RetainableByteBuffer.NonRetainableByteBuffer implements Content.Chunk
    {
        private final boolean _isLast;
        private final Throwable _failure;

        public NonRetainableChunk(Content.Chunk chunk)
        {
            super(BufferUtil.copy(chunk.getByteBuffer()));
            _isLast = chunk.isLast();
            _failure = chunk.getFailure();
            chunk.release();
        }

        @Override
        public boolean isLast()
        {
            return _isLast;
        }

        @Override
        public Throwable getFailure()
        {
            return _failure;
        }

        @Override
        public void retain()
        {
            throw new UnsupportedOperationException();
        }
    }

    private static class CaptureMultiPartViolations implements ComplianceViolation.Listener
    {
        private final List<ComplianceViolation.Event> events = new ArrayList<>();

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            events.add(event);
        }

        public List<ComplianceViolation.Event> getEvents()
        {
            return events;
        }
    }
}
