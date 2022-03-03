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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.quotes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

public class QuotesUtil
{
    public static List<String> loadLines(String filename) throws IOException
    {
        // read file
        File qfile = MavenTestingUtils.getTestResourceFile(filename);
        List<String> lines = new ArrayList<>();
        try (FileReader reader = new FileReader(qfile);
             BufferedReader buf = new BufferedReader(reader))
        {
            String line;
            while ((line = buf.readLine()) != null)
            {
                lines.add(line);
            }
        }
        return lines;
    }

    public static Quotes loadQuote(String filename) throws Exception
    {
        List<String> lines = loadLines(filename);

        Quotes quotes = new Quotes();
        for (String line : lines)
        {
            switch (line.charAt(0))
            {
                case 'a':
                    quotes.setAuthor(line.substring(2));
                    break;
                case 'q':
                    quotes.addQuote(line.substring(2));
                    break;
            }
        }

        return quotes;
    }

    public static List<Frame> loadAsWebSocketFrames(String filename) throws IOException
    {
        List<String> lines = loadLines(filename);
        List<Frame> ret = new ArrayList<>();
        ListIterator<String> linesIter = lines.listIterator();
        while (linesIter.hasNext())
        {
            Frame frame;
            if (!linesIter.hasPrevious())
                frame = new Frame(OpCode.TEXT);
            else
                frame = new Frame(OpCode.CONTINUATION);

            frame.setPayload(BufferUtil.toBuffer(linesIter.next() + "\n"));
            frame.setFin(!linesIter.hasNext());

            ret.add(frame);
        }

        return ret;
    }
}
