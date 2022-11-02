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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiPartTest
{
    @Test
    public void testParseNoParts()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        parser.parse(Content.Chunk.from(UTF_8.encode("--%s--".formatted(boundary)), true));

        assertEquals(1, listener.events.size());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testParseNoPartsInTwoChunks()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        parser.parse(Content.Chunk.from(UTF_8.encode("--%s--".formatted(boundary)), false));

        assertEquals(0, listener.events.size());

        parser.parse(Content.Chunk.from(BufferUtil.EMPTY_BUFFER, true));

        assertEquals(1, listener.events.size());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testEmptyPart()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart = """
            --$B\r
            \r
            --$B--\r
            """;
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart.replace("$B", boundary)), true));

        assertEquals(5, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: true length: 0", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testParsePartInTwoChunksSplitAtNewLine()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart1 = """
            --$B
            name: value
            
            content\r
            """;
        String multipart2 = """
            --$B--
            """;
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart1.replace("$B", boundary)), false));

        assertEquals(4, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name: value", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: false length: 7", listener.events.poll());

        parser.parse(Content.Chunk.from(UTF_8.encode(multipart2.replace("$B", boundary)), true));

        assertEquals(3, listener.events.size());
        assertEquals("content last: true length: 0", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testParsePartInTwoChunksSplitAtNewLineWithNewLineContent()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart1 = """
            --$B
            name: value
            
            content with additional CRLF
            \r
            """;
        String multipart2 = """
            --$B--
            """;
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart1.replace("$B", boundary)), false));

        assertEquals(4, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name: value", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: false length: 29", listener.events.poll());

        parser.parse(Content.Chunk.from(UTF_8.encode(multipart2.replace("$B", boundary)), true));

        assertEquals(3, listener.events.size());
        assertEquals("content last: true length: 0", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testParsePartInTwoChunksSplitAtNewLineWithMoreContent()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart1 = """
            --$B
            name: value
            
            content with additional CRLF
            \r
            """;
        String multipart2 = """
            more content\r
            --$B--
            """;
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart1.replace("$B", boundary)), false));

        assertEquals(4, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name: value", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: false length: 29", listener.events.poll());

        parser.parse(Content.Chunk.from(UTF_8.encode(multipart2.replace("$B", boundary)), true));

        assertEquals(5, listener.events.size());
        assertEquals("content last: false length: 1", listener.events.poll());
        assertEquals("content last: false length: 1", listener.events.poll());
        assertEquals("content last: true length: 12", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testParsePreamblePartEpilogue()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart = """
            preamble--$B
            more preamble\r
            --$B
            name: value
            
            \r\rcontent\r
            --$B--
            epilogue
            """.replace("$B", boundary);
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart), true));

        assertEquals(6, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name: value", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: true length: 9", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());

        // Try the same, one byte at a time.
        parser.reset();
        listener.clear();
        byte[] bytes = multipart.getBytes(UTF_8);
        for (int i = 0; i < bytes.length; ++i)
        {
            parser.parse(Content.Chunk.from(ByteBuffer.wrap(new byte[]{bytes[i]}), i == bytes.length - 1));
        }

        assertEquals(15, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name: value", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        for (int i = 0; i < 9; i++)
        {
            assertEquals("content last: false length: 1", listener.events.poll());
        }
        assertEquals("content last: true length: 0", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testBoundaryWithTrailingSpace()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart = """
            --$B \t\r
            name1: value1
            name2: value2\t
            
            content1\r
            content2\r
            --$B-- \t
            """.replace("$B", boundary);
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart), true));

        assertEquals(7, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name1: value1", listener.events.poll());
        assertEquals("header name2: value2", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: true length: 18", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testContentPartiallyMatchesBoundary()
    {
        String boundary = "boundary";
        String content = "--boxyz--boundar012";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart = """
            --$B
            
            $C\r
            --$B--
            """.replace("$B", boundary).replace("$C", content);
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart), true));

        assertEquals(5, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: true length: " + content.length(), listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testContentPartiallyMatchesBoundaryInTwoChunks()
    {
        String boundary = "boundary";
        String content1 = "\r\n--bo";
        String content2 = "xyz\r\n--boundar012";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);

        // Parse the initial boundary.
        parser.parse(Content.Chunk.from(UTF_8.encode("--" + boundary + "\r\n\r\n"), false));
        // Parse some content that looks like the boundary.
        parser.parse(Content.Chunk.from(UTF_8.encode(content1), false));
        // Parse some more content that looks like the boundary, plus some boundary.
        parser.parse(Content.Chunk.from(UTF_8.encode(content2 + "\r\n--"), false));
        // Parse the rest of the boundary.
        parser.parse(Content.Chunk.from(UTF_8.encode(boundary + "--"), true));

        assertEquals(8, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        // Since content1 starts with CR, it is emitted when the boundary does not match.
        assertEquals("content last: false length: 1", listener.events.poll());
        assertEquals("content last: false length: " + (content1.length() - 1), listener.events.poll());
        assertEquals("content last: false length: " + content2.length(), listener.events.poll());
        assertEquals("content last: true length: 0", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testPartWithNoContent()
    {
        String boundary = "boundary";
        TestListener listener = new TestListener();
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        String multipart = """
            --$B
            name: value
            
            --$B--
            """.replace("$B", boundary);
        parser.parse(Content.Chunk.from(UTF_8.encode(multipart), true));

        assertEquals(6, listener.events.size());
        assertEquals("begin", listener.events.poll());
        assertEquals("header name: value", listener.events.poll());
        assertEquals("headers", listener.events.poll());
        assertEquals("content last: true length: 0", listener.events.poll());
        assertEquals("end", listener.events.poll());
        assertEquals("complete", listener.events.poll());
    }

    @Test
    public void testSimple() throws Exception
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("""
            preamble\r
            --BOUNDARY\r
            name: value\r
            \r
            Hello\r
            --BOUNDARY\r
            powerLevel: 9001\r
            \r
            secondary\r
            content\r
            --BOUNDARY--epi\r
            logue\r
            """);

        parser.parse(Content.Chunk.from(data, true));

        assertEquals(2, listener.parts.size());

        MultiPart.Part part1 = listener.parts.get(0);
        assertEquals("value", part1.getHeaders().get("name"));
        assertEquals("Hello", Content.Source.asString(part1.getContent()));

        MultiPart.Part part2 = listener.parts.get(1);
        assertEquals("9001", part2.getHeaders().get("powerLevel"));
        assertEquals("secondary\r\ncontent", Content.Source.asString(part2.getContent()));

        assertEquals(0, data.remaining());
    }

    @Test
    public void testLineFeed() throws Exception
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("""
            preamble
            --BOUNDARY
            name: value
                        
            Hello
            --BOUNDARY
            powerLevel: 9001
                        
            secondary
            content
            --BOUNDARY--epi
            logue
            """);

        parser.parse(Content.Chunk.from(data, true));

        assertEquals(2, listener.parts.size());

        MultiPart.Part part1 = listener.parts.get(0);
        assertEquals("value", part1.getHeaders().get("name"));
        assertEquals("Hello", Content.Source.asString(part1.getContent()));

        MultiPart.Part part2 = listener.parts.get(1);
        assertEquals("9001", part2.getHeaders().get("powerLevel"));
        assertEquals("secondary\ncontent", Content.Source.asString(part2.getContent()));

        assertEquals(0, data.remaining());
    }

    @Test
    public void testPreamble()
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("This is not part of a part\r\n");
        parser.parse(Content.Chunk.from(data, false));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("Could be a boundary \r\n--BOUNDAR");
        parser.parse(Content.Chunk.from(data, false));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("but not it isn't \r\n--BOUN");
        parser.parse(Content.Chunk.from(data, false));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("DARX nor is this");
        parser.parse(Content.Chunk.from(data, false));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("but this is--BOUNDARY\r\n");
        parser.parse(Content.Chunk.from(data, false));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("--BOUNDARY--\r\n");
        parser.parse(Content.Chunk.from(data, true));
        assertThat(data.remaining(), is(0));
        assertTrue(listener.parts.isEmpty());
        assertTrue(listener.complete);
    }

    @Test
    public void testPartNoContent() throws Exception
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value\r
            \r
            \r
            --BOUNDARY--""");
        parser.parse(Content.Chunk.from(data, true));

        assertThat(data.remaining(), is(0));
        assertEquals(1, listener.parts.size());
        MultiPart.Part part = listener.parts.get(0);
        assertEquals("value", part.getHeaders().get("name"));
        assertEquals("", Content.Source.asString(part.getContent()));
    }

    @Test
    public void testPartNoContentNoCRLF() throws Exception
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value\r
            \r
            --BOUNDARY--""");
        parser.parse(Content.Chunk.from(data, true));

        assertThat(data.remaining(), is(0));
        assertEquals(1, listener.parts.size());
        MultiPart.Part part = listener.parts.get(0);
        assertEquals("value", part.getHeaders().get("name"));
        assertEquals("", Content.Source.asString(part.getContent()));
    }

    @Test
    public void testContentSplitInTwoChunks() throws Exception
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Hello\r
            """);
        parser.parse(Content.Chunk.from(data, false));

        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer(
            """
                this is not a --BOUNDARY\r
                that's a boundary\r
                --BOUNDARY--
                """);
        parser.parse(Content.Chunk.from(data, true));

        assertThat(data.remaining(), is(0));
        assertEquals(1, listener.parts.size());
        MultiPart.Part part = listener.parts.get(0);
        assertEquals("value", part.getHeaders().get("name"));
        assertThat(Content.Source.asString(part.getContent()), is("""
            Hello\r
            this is not a --BOUNDARY\r
            that's a boundary"""));
    }

    @Test
    public void testBinaryPart() throws Exception
    {
        byte[] random = new byte[8192];
        ThreadLocalRandom.current().nextBytes(random);
        // Make sure the last 2 bytes are not \r\n,
        // otherwise the multipart parser gets confused.
        random[random.length - 2] = 0;
        random[random.length - 1] = 0;

        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        String preamble = "Blah blah blah\r\n--BOUNDARY\r\n\r\n";
        parser.parse(Content.Chunk.from(BufferUtil.toBuffer(preamble), false));
        parser.parse(Content.Chunk.from(ByteBuffer.wrap(random), false));
        String epilogue = "\r\n--BOUNDARY--\r\nBlah blah blah!\r\n";
        ByteBuffer epilogueBuffer = BufferUtil.toBuffer(epilogue);
        parser.parse(Content.Chunk.from(epilogueBuffer, true));

        assertThat(epilogueBuffer.remaining(), is(0));
        assertEquals(1, listener.parts.size());
        MultiPart.Part part = listener.parts.get(0);
        assertThat(Content.Source.asByteBuffer(part.getContent()), is(ByteBuffer.wrap(random)));
    }

    @Test
    public void testEpilogueWithMoreBoundaries() throws Exception
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("BOUNDARY", listener);

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Hello\r
            --BOUNDARY--epilogue here:\r
            --BOUNDARY--\r
            --BOUNDARY""");

        parser.parse(Content.Chunk.from(data, true));

        assertThat(data.remaining(), is(0));
        assertEquals(1, listener.parts.size());
        MultiPart.Part part = listener.parts.get(0);
        assertEquals("value", part.getHeaders().get("name"));
        assertEquals("Hello", Content.Source.asString(part.getContent()));
    }

    @Test
    public void testOnlyCRAfterHeaders()
    {
        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("AaB03x", listener);

        ByteBuffer data = BufferUtil.toBuffer(
            """
                --AaB03x\r
                content-disposition: form-data; name="field1"\r
                \rJoe Blow\r
                --AaB03x--\r
                """);
        parser.parse(Content.Chunk.from(data, true));

        assertNotNull(listener.failure);
        assertThat(listener.failure.getMessage(), containsStringIgnoringCase("Invalid EOL"));
    }

    private static List<String> badHeaders()
    {
        return List.of(
            "Foo\\Bar: value\r\n",
            "Foo@Bar: value\r\n",
            "Foo,Bar: value\r\n",
            "Foo}Bar: value\r\n",
            "Foo{Bar: value\r\n",
            "Foo=Bar: value\r\n",
            "Foo>Bar: value\r\n",
            "Foo<Bar: value\r\n",
            "Foo)Bar: value\r\n",
            "Foo(Bar: value\r\n",
            "Foo?Bar: value\r\n",
            "Foo\"Bar: value\r\n",
            "Foo/Bar: value\r\n",
            "Foo]Bar: value\r\n",
            "Foo[Bar: value\r\n",
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
            "\u0192\u00f8\u00f8\u00df\u00e5\u00ae: value\r\n"
        );
    }

    @ParameterizedTest
    @MethodSource("badHeaders")
    public void testBadHeaderNames(String badHeader)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "--AaB03x\r\n" + badHeader + "\r\n--AaB03x--\r\n");

        TestPartsListener listener = new TestPartsListener();
        MultiPart.Parser parser = new MultiPart.Parser("AaB03x", listener);
        parser.parse(Content.Chunk.from(buffer, true));

        assertNotNull(listener.failure);
        assertThat(listener.failure.getMessage(), containsStringIgnoringCase("invalid header name"));
    }

    private static class TestListener implements MultiPart.Parser.Listener
    {
        private final Deque<String> events = new ArrayDeque<>();

        public void clear()
        {
            events.clear();
        }

        @Override
        public void onPartBegin()
        {
            events.offer("begin");
        }

        @Override
        public void onPartHeader(String name, String value)
        {
            events.offer("header %s: %s".formatted(name, value));
        }

        @Override
        public void onPartHeaders()
        {
            events.offer("headers");
        }

        @Override
        public void onPartContent(Content.Chunk chunk)
        {
            events.offer("content last: %b length: %d".formatted(chunk.isLast(), chunk.getByteBuffer().remaining()));
            chunk.release();
        }

        @Override
        public void onPartEnd()
        {
            events.offer("end");
        }

        @Override
        public void onComplete()
        {
            events.offer("complete");
        }

        @Override
        public void onFailure(Throwable failure)
        {
            events.offer("failure %s".formatted(failure.getClass().getName()));
        }
    }

    private static class TestPartsListener extends MultiPart.AbstractPartsListener
    {
        private final List<MultiPart.Part> parts = new ArrayList<>();
        private final List<Content.Chunk> partContent = new ArrayList<>();
        private boolean complete;
        private Throwable failure;

        @Override
        public void onPartContent(Content.Chunk chunk)
        {
            partContent.add(chunk);
        }

        @Override
        public void onPart(String name, String fileName, HttpFields headers)
        {
            parts.add(new MultiPart.ChunksPart(name, fileName, headers, List.copyOf(partContent)));
            partContent.clear();
        }

        @Override
        public void onComplete()
        {
            super.onComplete();
            complete = true;
        }

        @Override
        public void onFailure(Throwable failure)
        {
            super.onFailure(failure);
            this.failure = failure;
        }
    }
}
