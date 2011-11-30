// ========================================================================
// Copyright (c) 2006-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.osgi.equinoxtools.console;

import java.util.LinkedList;
import java.util.Queue;

import org.eclipse.jetty.osgi.equinoxtools.console.WebConsoleWriterOutputStream.OnFlushListener;

/**
 * Processing of the messages to be received and sent to the chat servlets.
 * Made to be extended for filtering of the messages and commands.
 */
public class EquinoxChattingSupport
{
    
    private WebConsoleSession _consoleSession;
    
    public EquinoxChattingSupport(WebConsoleSession consoleSession)
    {
        _consoleSession = consoleSession;
    }
    
    /**
     * Split the output into multiple lines.
     * Format them for the json messages sent to the chat.
     * Empties the console output from what is already displayed in the chat.
     * @return The lines to add to the message queue of each client.
     */
    protected Queue<String> processConsoleOutput(boolean escape, OnFlushListener onflush)
    {
        Queue<String> result = new LinkedList<String>();
        String toDisplay = _consoleSession.getOutputAsWriter().getBuffer().toString();
        //the last listener to be called is in charge of clearing the console.
        boolean clearConsole = _consoleSession.getOnFlushListeners().indexOf(onflush) == _consoleSession.getOnFlushListeners().size();
        if (clearConsole)
        {
            _consoleSession.clearOutput();
        }
        boolean lastLineIsComplete = toDisplay.endsWith("\n") || toDisplay.endsWith("\r");
        String[] lines = toDisplay.split("\n");
        String lastLine = lastLineIsComplete ? null : lines[lines.length-1];
        if (clearConsole)
        {
            _consoleSession.getOutputAsWriter().append(lastLine);
        }
        for (int lnNb = 0; lnNb < (lastLineIsComplete ? lines.length : lines.length-1); lnNb++) 
        {
            String line = lines[lnNb];
            while (line.trim().startsWith("null"))
            {//hum..
                line = line.trim().substring("null".length()).trim();
            }
            if (line.startsWith("osgi>"))
            {
                result.add("osgi>");
                result.add(escape ? jsonEscapeString(line.substring("osgi>".length())) : line.substring("osgi>".length()));
            }
            else
            {
                result.add("&#10;");
                result.add(escape ? jsonEscapeString(line) : line);
            }
        }
        return result;
    }
    
    /**
     * http://www.ietf.org/rfc/rfc4627.txt
     * @param str
     * @return The same string escaped according to the JSON RFC.
     */
    public static String jsonEscapeString(String str)
    {
        StringBuilder sb = new StringBuilder();
        char[] asChars = str.toCharArray();
        for (char ch : asChars)
        {
            switch (ch)
            {
                //the reserved characters
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '/':
                    sb.append("\\/");
                    break;
                default:
                    //The non reserved characters
                    if (ch >= '\u0000' && ch <= '\u001F')
                    {
                        //escape as a unicode number when out of range.
                        String ss = Integer.toHexString(ch);
                        sb.append("\\u");
                        for (int i = 0; i < 4 - ss.length(); i++)
                        {
                            //padding
                            sb.append('0');
                        }
                        sb.append(ss.toUpperCase());
                    }
                    else
                    {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
    
    public void broadcast(OnFlushListener source)
    {
        for (OnFlushListener onflush : _consoleSession.getOnFlushListeners())
        {
            if (onflush != source)
            {
                onflush.onFlush();
            }
        }
    }

}
