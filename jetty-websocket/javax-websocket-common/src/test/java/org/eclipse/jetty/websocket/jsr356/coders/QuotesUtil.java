//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.coders;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;

public class QuotesUtil
{
    public static List<String> loadLines(String filename) throws IOException
    {
        // read file
        File qfile = MavenTestingUtils.getTestResourceFile(filename);
        List<String> lines = new ArrayList<>();
        try (FileReader reader = new FileReader(qfile); BufferedReader buf = new BufferedReader(reader))
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
    
    public static List<WebSocketFrame> loadAsWebSocketFrames(String filename) throws IOException
    {
        List<String> lines = loadLines(filename);
        List<WebSocketFrame> ret = new ArrayList<>();
        ListIterator<String> linesIter = lines.listIterator();
        while (linesIter.hasNext())
        {
            WebSocketFrame frame;
            if (!linesIter.hasPrevious())
                frame = new TextFrame();
            else
                frame = new ContinuationFrame();
            
            frame.setPayload(BufferUtil.toBuffer(linesIter.next() + "\n"));
            frame.setFin(!linesIter.hasNext());
            
            ret.add(frame);
        }
        
        return ret;
    }
}
