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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.List;

import org.eclipse.jetty.osgi.equinoxtools.console.WebConsoleWriterOutputStream.OnFlushListener;
import org.eclipse.osgi.framework.console.ConsoleSession;

/**
 * A simple console session for equinox.
 * 
 * @author hmalphettes
 */
public class WebConsoleSession extends ConsoleSession
{

    private OutputStream _out;
    private StringWriter _outWriter;
    private PrintStream _source;
    private InputStream _in;

    public WebConsoleSession()
    {
        _outWriter = new StringWriter();
        _out = new WebConsoleWriterOutputStream(_outWriter,"UTF-8");
        try
        {
            PipedOutputStream source = new PipedOutputStream();
            PipedInputStream sink = new PipedInputStream(source);
            _in = sink;
            _source = new PrintStream(source);
        }
        catch (IOException e)
        {
            //this never happens?
            e.printStackTrace();
        }
    }
    
    @Override
    protected void doClose()
    {
        if (_out != null) try { _out.close(); } catch (IOException e) {}
        if (_in != null) try { _in.close(); } catch (IOException ioe) {}
    }

    @Override
    public InputStream getInput()
    {
        return _in;
    }

    @Override
    public OutputStream getOutput()
    {
        return _out;
    }

    /**
     * For the output we are using a string writer in fact.
     * @return
     */
    public StringWriter getOutputAsWriter()
    {
        return _outWriter;
    }
    
    /**
     * @return The print stream where commands can be written.
     */
    public PrintStream getSource()
    {
        return _source;
    }
    
    /**
     * Issue a command on the console.
     * @param cmd
     */
    public void issueCommand(String cmd)
    {
        if (cmd != null)
        {
            getSource().println(cmd);
        }
    }
    
    /**
     * @param flushListener Object that is called back when the outputstream is flushed.
     */
    public void addOnFlushListener(OnFlushListener flushListener)
    {
        ((WebConsoleWriterOutputStream)_out).addOnFlushListener(flushListener);
    }
    /**
     * @param flushListener Object that is called back when the outputstream is flushed.
     */
    public boolean removeOnFlushListener(OnFlushListener flushListener)
    {
        return ((WebConsoleWriterOutputStream)_out).removeOnFlushListener(flushListener);
    }
    
    /**
     * Process command comming from a web UI
     * @param cmd
     */
    public void processCommand(String cmd, boolean wait)
    {
        cmd = cmd.trim();
        while (cmd.startsWith("osgi>"))
        {
            cmd = cmd.substring("osgi>".length()).trim();
        }
        if (cmd.equals("clear"))
        {
            clearOutput();
        }
        else
        {
            getOutputAsWriter().append(cmd + "\n");
            int originalOutputLength = getOutputAsWriter().getBuffer().length();
            issueCommand(cmd);
            
            if (wait)
            {
                //it does not get uglier than this:
                //give a little time to equinox to interpret the command so we see the response
                //we could do a lot better, but we might as well use the async servlets anyways.
                int waitLoopNumber = 0;
                int lastWaitOutputLength = -1;
                while (waitLoopNumber < 10)
                {
                    waitLoopNumber++;
                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e)
                    {
                        break;
                    }
                    int newOutputLength = getOutputAsWriter().getBuffer().length();
                    if (newOutputLength > originalOutputLength && newOutputLength == lastWaitOutputLength)
                    {
                        break;
                    }
                    lastWaitOutputLength = newOutputLength;
                }
            }
        }

    }
    
    public void clearOutput()
    {
        StringBuffer buf = getOutputAsWriter().getBuffer();
        if (buf.length() > 0) buf.delete(0,buf.length()-1);
    }
    
    public List<OnFlushListener> getOnFlushListeners()
    {
        return ((WebConsoleWriterOutputStream)_out).getOnFlushListeners();
    }
    
}
