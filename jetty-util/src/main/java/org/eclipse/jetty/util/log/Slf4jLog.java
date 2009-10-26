// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.log;


/* ------------------------------------------------------------ */
/** Slf4jLog Logger
 * 
 */
public class Slf4jLog implements Logger
{
    private org.slf4j.Logger _logger;


    public Slf4jLog() throws Exception
    {
        this("org.eclipse.jetty.util.log");
    }
    
    public Slf4jLog(String name)
    {
        _logger = org.slf4j.LoggerFactory.getLogger( name );
    }
    
    public String getName()
    {
        return _logger.getName();
    }
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebug(java.lang.String)
     */
    public void debug(String msg)
    {
        _logger.debug(msg);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebug(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void debug(String msg, Object arg0, Object arg1)
    {
        _logger.debug(msg, arg0, arg1);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebug(java.lang.String, java.lang.Throwable)
     */
    public void debug(String msg, Throwable th)
    {
        _logger.debug(msg, th);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebugEnabled()
     */
    public boolean isDebugEnabled()
    {
        return _logger.isDebugEnabled();
    }


    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doInfo(java.lang.String)
     */
    public void info(String msg)
    {
        _logger.info(msg);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doInfo(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void info(String msg, Object arg0, Object arg1)
    {
        _logger.info(msg, arg0, arg1);
    }


    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doWarn(java.lang.String)
     */
    public void warn(String msg)
    {
        _logger.warn(msg);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doWarn(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void warn(String msg, Object arg0, Object arg1)
    {
        _logger.warn(msg, arg0, arg1);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doWarn(java.lang.String, java.lang.Throwable)
     */
    public void warn(String msg, Throwable th)
    {

        if (th instanceof RuntimeException || th instanceof Error)
            _logger.error(msg, th);
        else
            _logger.warn(msg,th);

    }

    /* ------------------------------------------------------------ */
    public Logger getLogger(String name)
    {
        return new Slf4jLog(name);

    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _logger.toString();
    }

    /* ------------------------------------------------------------ */
    public void setDebugEnabled(boolean enabled)
    {
        warn("setDebugEnabled not implemented",null,null);
    }
}
