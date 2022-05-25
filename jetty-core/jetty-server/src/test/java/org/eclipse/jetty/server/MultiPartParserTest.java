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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.server.internal.MultiPartParser;
import org.eclipse.jetty.server.internal.MultiPartParser.State;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiPartParserTest
{
    @Test
    public void testSimple()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

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

        /* Test First Content Section */
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>"));

        /* Test Second Content Section */
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>", "powerLevel: 9001", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>", "secondary\r\ncontent", "<<LAST>>"));

        /* Test Progression to END State */
        parser.parse(data, true);
        assertThat(parser.getState(), is(State.END));
        assertThat(data.remaining(), is(0));
    }

    @Disabled // TODO support LF termination
    @Test
    public void testLineFeed()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

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

        /* Test First Content Section */
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>"));

        /* Test Second Content Section */
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>", "powerLevel: 9001", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>", "secondary\ncontent", "<<LAST>>"));

        /* Test Progression to END State */
        parser.parse(data, true);
        assertThat(parser.getState(), is(State.END));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testEmptyPreamble()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler()
        {
        }, "BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("");

        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
    }

    @Test
    public void testNoPreamble()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler()
        {
        }, "BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("--BOUNDARY   \r\n");

        parser.parse(data, false);
        assertTrue(parser.isState(State.BODY_PART));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testPreamble()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler()
        {
        }, "BOUNDARY");
        ByteBuffer data;

        data = BufferUtil.toBuffer("This is not part of a part\r\n");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("More data that almost includes \n--BOUNDARY but no CR before.");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("Could be a boundary \r\n--BOUNDAR");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("but not it isn't \r\n--BOUN");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));

        data = BufferUtil.toBuffer("DARX nor is this");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testPreambleCompleteBoundary()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler()
        {
        }, "BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("This is not part of a part\r\n--BOUNDARY  \r\n");

        parser.parse(data, false);
        assertThat(parser.getState(), is(State.BODY_PART));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testPreambleSplitBoundary()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler()
        {
        }, "BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("This is not part of a part\r\n");

        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));
        data = BufferUtil.toBuffer("-");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));
        data = BufferUtil.toBuffer("-");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));
        data = BufferUtil.toBuffer("B");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.PREAMBLE));
        assertThat(data.remaining(), is(0));
        data = BufferUtil.toBuffer("OUNDARY-");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER_CLOSE));
        assertThat(data.remaining(), is(0));
        data = BufferUtil.toBuffer("ignore\r");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER_PADDING));
        assertThat(data.remaining(), is(0));
        data = BufferUtil.toBuffer("\n");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.BODY_PART));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testFirstPartNoFields()
    {
        MultiPartParser parser = new MultiPartParser(new MultiPartParser.Handler()
        {
        }, "BOUNDARY");
        ByteBuffer data = BufferUtil.toBuffer("--BOUNDARY\r\n\r\n");

        parser.parse(data, false);
        assertThat(parser.getState(), is(State.FIRST_OCTETS));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testFirstPartFields()
    {
        TestHandler handler = new TestHandler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }
        };
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name0: value0\r
            name1 :value1 \r
            name2:value\r
             2\r
            \r
            Content""");

        parser.parse(data, false);
        assertThat(parser.getState(), is(State.FIRST_OCTETS));
        assertThat(data.remaining(), is(7));
        assertThat(handler.fields, Matchers.contains("name0: value0", "name1: value1", "name2: value 2", "<<COMPLETE>>"));
    }

    @Test
    public void testFirstPartNoContent()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value\r
            \r
            \r
            --BOUNDARY""");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("<<LAST>>"));
    }

    @Test
    public void testFirstPartNoContentNoCRLF()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value\r
            \r
            --BOUNDARY""");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("<<LAST>>"));
    }

    @Test
    public void testFirstPartContentLookingLikeNoCRLF()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value\r
            \r
            -""");
        parser.parse(data, false);
        data = BufferUtil.toBuffer("Content!");
        parser.parse(data, false);

        assertThat(parser.getState(), is(State.OCTETS));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("-", "Content!"));
    }

    @Test
    public void testFirstPartPartialContent()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Hello\r
            """);
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.OCTETS));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello"));

        data = BufferUtil.toBuffer(
            """
                Now is the time for all good men to come to the aid of the party.\r
                How now brown cow.\r
                The quick brown fox jumped over the lazy dog.\r
                this is not a --BOUNDARY\r
                """);
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.OCTETS));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "\r\n", """
            Now is the time for all good men to come to the aid of the party.\r
            How now brown cow.\r
            The quick brown fox jumped over the lazy dog.\r
            this is not a --BOUNDARY"""));
    }

    @Test
    public void testFirstPartShortContent()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Hello\r
            --BOUNDARY""");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>"));
    }

    @Test
    public void testFirstPartLongContent()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Now is the time for all good men to come to the aid of the party.\r
            How now brown cow.\r
            The quick brown fox jumped over the lazy dog.\r
            \r
            --BOUNDARY""");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("""
            Now is the time for all good men to come to the aid of the party.\r
            How now brown cow.\r
            The quick brown fox jumped over the lazy dog.\r
            """, "<<LAST>>"));
    }

    @Test
    public void testFirstPartLongContentNoCarriageReturn()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        //boundary still requires carriage return
        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY
            name: value

            Now is the time for all good men to come to the aid of the party.
            How now brown cow.
            The quick brown fox jumped over the lazy dog.
            \r
            --BOUNDARY""");
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(data.remaining(), is(0));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("""
            Now is the time for all good men to come to the aid of the party.
            How now brown cow.
            The quick brown fox jumped over the lazy dog.
            """, "<<LAST>>"));
    }

    @Test
    public void testBinaryPart()
    {
        byte[] random = new byte[8192];
        final ByteBuffer bytes = BufferUtil.allocate(random.length);
        ThreadLocalRandom.current().nextBytes(random);
        // Arrays.fill(random,(byte)'X');

        TestHandler handler = new TestHandler()
        {
            @Override
            public boolean content(ByteBuffer buffer, boolean last)
            {
                BufferUtil.append(bytes, buffer);
                return last;
            }
        };
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        String preamble = "Blah blah blah\r\n--BOUNDARY\r\n\r\n";
        String epilogue = "\r\n--BOUNDARY\r\nBlah blah blah!\r\n";

        ByteBuffer data = BufferUtil.allocate(preamble.length() + random.length + epilogue.length());
        BufferUtil.append(data, BufferUtil.toBuffer(preamble));
        BufferUtil.append(data, ByteBuffer.wrap(random));
        BufferUtil.append(data, BufferUtil.toBuffer(epilogue));

        parser.parse(data, true);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(data.remaining(), is(19));
        assertThat(bytes.array(), is(random));
    }

    @Test
    public void testEpilogue()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Hello\r
            --BOUNDARY--epilogue here:\r
            --BOUNDARY--\r
            --BOUNDARY""");

        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>"));

        parser.parse(data, true);
        assertThat(parser.getState(), is(State.END));
    }

    @Test
    public void testMultipleContent()
    {
        TestHandler handler = new TestHandler();
        MultiPartParser parser = new MultiPartParser(handler, "BOUNDARY");

        ByteBuffer data = BufferUtil.toBuffer("""
            --BOUNDARY\r
            name: value
            \r
            Hello\r
            --BOUNDARY\r
            powerLevel: 9001
            \r
            secondary\r
            content\r
            --BOUNDARY--epilogue here""");

        /* Test First Content Section */
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>"));

        /* Test Second Content Section */
        parser.parse(data, false);
        assertThat(parser.getState(), is(State.DELIMITER));
        assertThat(handler.fields, Matchers.contains("name: value", "<<COMPLETE>>", "powerLevel: 9001", "<<COMPLETE>>"));
        assertThat(handler.content, Matchers.contains("Hello", "<<LAST>>", "secondary\r\ncontent", "<<LAST>>"));

        /* Test Progression to END State */
        parser.parse(data, true);
        assertThat(parser.getState(), is(State.END));
        assertThat(data.remaining(), is(0));
    }

    @Test
    public void testCrAsLineTermination()
    {
        TestHandler handler = new TestHandler()
        {
            @Override
            public boolean messageComplete()
            {
                return true;
            }

            @Override
            public boolean content(ByteBuffer buffer, boolean last)
            {
                super.content(buffer, last);
                return false;
            }
        };
        MultiPartParser parser = new MultiPartParser(handler, "AaB03x");

        ByteBuffer data = BufferUtil.toBuffer(
            """
                --AaB03x\r
                content-disposition: form-data; name="field1"\r
                \rJoe Blow\r
                --AaB03x--\r
                """);

        BadMessageException x = assertThrows(BadMessageException.class,
            () -> parser.parse(data, true),
            "Invalid EOL");
        assertThat(x.getMessage(), containsString("Bad EOL"));
    }

    @Test // TODO: Parameterize
    public void testBadHeaderNames()
    {
        String[] bad = new String[]
            {
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
            };

        for (String s : bad)
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "--AaB03x\r\n" + s + "\r\n--AaB03x--\r\n");

            MultiPartParser.Handler handler = new TestHandler();
            MultiPartParser parser = new MultiPartParser(handler, "AaB03x");

            try
            {
                parser.parse(buffer, true);
            }
            catch (BadMessageException e)
            {
                assertTrue(e.getMessage().contains("Illegal character"));
            }
        }
    }

    @Test
    public void splitTest()
    {
        TestHandler handler = new TestHandler()
        {
            @Override
            public boolean messageComplete()
            {
                return true;
            }

            @Override
            public boolean content(ByteBuffer buffer, boolean last)
            {
                super.content(buffer, last);
                return false;
            }
        };

        MultiPartParser parser = new MultiPartParser(handler, "---------------------------9051914041544843365972754266");
        ByteBuffer data = BufferUtil.toBuffer(
            """
                POST / HTTP/1.1
                Host: localhost:8000
                User-Agent: Mozilla/5.0 (X11; Ubuntu; Linux i686; rv:29.0) Gecko/20100101 Firefox/29.0
                Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
                Accept-Language: en-US,en;q=0.5
                Accept-Encoding: gzip, deflate
                Cookie: __atuvc=34%7C7; permanent=0; _gitlab_session=226ad8a0be43681acf38c2fab9497240; __profilin=p%3Dt; request_method=GET
                Connection: keep-alive
                Content-Type: multipart/form-data; boundary=---------------------------9051914041544843365972754266
                Content-Length: 554
                \r
                -----------------------------9051914041544843365972754266
                Content-Disposition: form-data; name="text"

                text default\r
                -----------------------------9051914041544843365972754266
                Content-Disposition: form-data; name="file1"; filename="a.txt"
                Content-Type: text/plain

                Content of a.txt.
                \r
                -----------------------------9051914041544843365972754266
                Content-Disposition: form-data; name="file2"; filename="a.html"
                Content-Type: text/html

                <!DOCTYPE html><title>Content of a.html.</title>
                \r
                -----------------------------9051914041544843365972754266
                Field1: value1
                Field2: value2
                Field3: value3
                Field4: value4
                Field5: value5
                Field6: value6
                Field7: value7
                Field8: value8
                Field9: value
                 9
                \r
                -----------------------------9051914041544843365972754266
                Field1: value1
                \r
                But the amount of denudation which the strata have
                in many places suffered, independently of the rate
                of accumulation of the degraded matter, probably
                offers the best evidence of the lapse of time. I remember
                having been much struck with the evidence of
                denudation, when viewing volcanic islands, which
                have been worn by the waves and pared all round
                into perpendicular cliffs of one or two thousand feet
                in height; for the gentle slope of the lava-streams,
                due to their formerly liquid state, showed at a glance
                how far the hard, rocky beds had once extended into
                the open ocean.
                \r
                -----------------------------9051914041544843365972754266--===== ajlkfja;lkdj;lakjd;lkjf ==== epilogue here  ==== kajflajdfl;kjafl;kjl;dkfja ====
                \r
                \r\r\r


                """);

        int length = data.remaining();
        for (int i = 0; i < length - 1; i++)
        {
            //partition 0 to i
            ByteBuffer dataSeg = data.slice();
            dataSeg.position(0);
            dataSeg.limit(i);
            assertThat("First " + i, parser.parse(dataSeg, false), is(false));

            //partition i
            dataSeg = data.slice();
            dataSeg.position(i);
            dataSeg.limit(i + 1);
            assertThat("Second " + i, parser.parse(dataSeg, false), is(false));

            //partition i to length
            dataSeg = data.slice();
            dataSeg.position(i + 1);
            dataSeg.limit(length);
            assertThat("Third " + i, parser.parse(dataSeg, true), is(true));

            assertThat(handler.fields, Matchers.contains("Content-Disposition: form-data; name=\"text\"", "<<COMPLETE>>",
                "Content-Disposition: form-data; name=\"file1\"; filename=\"a.txt\"",
                "Content-Type: text/plain", "<<COMPLETE>>",
                "Content-Disposition: form-data; name=\"file2\"; filename=\"a.html\"",
                "Content-Type: text/html", "<<COMPLETE>>",
                "Field1: value1", "Field2: value2", "Field3: value3",
                "Field4: value4", "Field5: value5", "Field6: value6",
                "Field7: value7", "Field8: value8", "Field9: value 9", "<<COMPLETE>>",
                "Field1: value1", "<<COMPLETE>>"));

            assertThat(handler.contentString(), is("""
                text default<<LAST>>Content of a.txt.
                <<LAST>><!DOCTYPE html><title>Content of a.html.</title>
                <<LAST>><<LAST>>But the amount of denudation which the strata have
                in many places suffered, independently of the rate
                of accumulation of the degraded matter, probably
                offers the best evidence of the lapse of time. I remember
                having been much struck with the evidence of
                denudation, when viewing volcanic islands, which
                have been worn by the waves and pared all round
                into perpendicular cliffs of one or two thousand feet
                in height; for the gentle slope of the lava-streams,
                due to their formerly liquid state, showed at a glance
                how far the hard, rocky beds had once extended into
                the open ocean.
                <<LAST>>"""));

            handler.clear();
            parser.reset();
        }
    }

    @Test
    public void testGeneratedForm()
    {
        TestHandler handler = new TestHandler()
        {
            @Override
            public boolean messageComplete()
            {
                return true;
            }

            @Override
            public boolean content(ByteBuffer buffer, boolean last)
            {
                super.content(buffer, last);
                return false;
            }

            @Override
            public boolean headerComplete()
            {
                return false;
            }
        };

        MultiPartParser parser = new MultiPartParser(handler, "WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW");
        ByteBuffer data = BufferUtil.toBuffer("""
            Content-Type: multipart/form-data; boundary=WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r
            \r
            --WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r
            Content-Disposition: form-data; name="part1"\r

            wNfﾐxVam﾿t\r
            --WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW
            Content-Disposition: form-data; name="part2"\r
            \r
            &ﾳﾺ￙￹ￖￃO\r
            --WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW--""");

        parser.parse(data, true);
        assertThat(parser.getState(), is(State.END));
        assertThat(handler.fields.size(), is(2));
    }

    static class TestHandler implements MultiPartParser.Handler
    {
        List<String> fields = new ArrayList<>();
        List<String> content = new ArrayList<>();

        @Override
        public void parsedField(String name, String value)
        {
            fields.add(name + ": " + value);
        }

        public String contentString()
        {
            StringBuilder sb = new StringBuilder();
            for (String s : content)
            {
                sb.append(s);
            }
            return sb.toString();
        }

        @Override
        public boolean headerComplete()
        {
            fields.add("<<COMPLETE>>");
            return false;
        }

        @Override
        public boolean content(ByteBuffer buffer, boolean last)
        {
            if (BufferUtil.hasContent(buffer))
                content.add(BufferUtil.toString(buffer));
            if (last)
                content.add("<<LAST>>");
            return last;
        }

        public void clear()
        {
            fields.clear();
            content.clear();
        }
    }
}
