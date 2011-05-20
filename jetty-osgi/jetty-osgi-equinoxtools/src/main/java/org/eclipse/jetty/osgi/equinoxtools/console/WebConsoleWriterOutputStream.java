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
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Can be set with a listener that is called back right after the flush method is called.
 */
public class WebConsoleWriterOutputStream extends org.eclipse.jetty.io.WriterOutputStream
{
    
    /**
     * Interface called back after the outputstream is flushed.
     */
    public interface OnFlushListener
    {
        /**
         * Called right after the flush method on the output stream has been executed.
         */
        public void onFlush();

    }
    
    public interface MessageBroadcaster
    {
        public void broadcast();
    }

    private List<OnFlushListener> _callBacks;

    public WebConsoleWriterOutputStream(Writer writer, String encoding)
    {
        super(writer, encoding);
    }
    
    @Override
    public synchronized void flush() throws IOException
    {
        super.flush();
        if (_callBacks != null)
        {
            for (OnFlushListener listener : _callBacks)
            {
                listener.onFlush();
            }
        }
    }
    
    public synchronized void addOnFlushListener(OnFlushListener callback)
    {
        if (_callBacks == null)
        {
            _callBacks = new ArrayList<WebConsoleWriterOutputStream.OnFlushListener>();
        }
        if (!_callBacks.contains(callback))
        {
            _callBacks.add(callback);
        }
    }
    public synchronized boolean removeOnFlushListener(OnFlushListener callback)
    {
        if (_callBacks != null)
        {
            return _callBacks.remove(callback);
        }
        return false;
    }
    public synchronized List<OnFlushListener> getOnFlushListeners()
    {
        return _callBacks;
    }
    
}
