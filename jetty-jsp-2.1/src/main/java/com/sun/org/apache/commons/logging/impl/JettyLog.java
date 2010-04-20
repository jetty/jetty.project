// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
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

package com.sun.org.apache.commons.logging.impl;

import com.sun.org.apache.commons.logging.Log;

/**
 * Log
 * 
 * Bridges the com.sun.org.apache.commons.logging.Log to Jetty's log.
 *
 **/
public class JettyLog implements Log
{
    private String _name;
    private org.eclipse.jetty.util.log.Logger _logger;
    
    /**
     * 
     */
    public JettyLog(String name)
    {
        _name = name;
        _logger = org.eclipse.jetty.util.log.Log.getLogger(name);
    }
    public  void fatal (Object message)
    {
        _logger.warn(message.toString(), null, null);
    }
    
    public  void fatal (Object message, Throwable t)
    {
        _logger.warn(message.toString(), t);
    }
    
    public  void debug(Object message)
    {
        _logger.debug(message.toString(), null);
    }
    
    public  void debug (Object message, Throwable t)
    {
        _logger.debug(message.toString(), t);
    }
    
    public  void trace (Object message)
    {
        _logger.debug(message.toString(), null);
    }
  
    public  void info(Object message)
    {
       _logger.info(message.toString(), null, null);
    }

    public  void error(Object message)
    {
       _logger.warn(message.toString(), null);
    }
    
    public  void error(Object message, Throwable cause)
    {
        _logger.warn(message.toString(), cause);
    }

    public  void warn(Object message)
    {
        _logger.warn(message.toString(), null);
    }
    
    public  boolean isDebugEnabled ()
    {
        return _logger.isDebugEnabled();
    }
    
    public  boolean isWarnEnabled ()
    {
        return _logger.isDebugEnabled();
    }
    
    public  boolean isInfoEnabled ()
    {
        return true;
    }
    
    public  boolean isErrorEnabled ()
    {
        return true;
    }
    
    public  boolean isTraceEnabled ()
    {
        return _logger.isDebugEnabled();
    }
}
