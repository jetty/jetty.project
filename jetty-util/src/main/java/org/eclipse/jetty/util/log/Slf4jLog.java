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
    private org.slf4j.Logger logger;


    public Slf4jLog() throws Exception
    {
        this("org.eclipse.jetty.util.log");
    }
    
    public Slf4jLog(String name)
    {
        logger = org.slf4j.LoggerFactory.getLogger( name );
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebug(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void debug(String msg, Object arg0, Object arg1)
    {
        logger.debug(msg, arg0, arg1);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebug(java.lang.String, java.lang.Throwable)
     */
    public void debug(String msg, Throwable th)
    {
        logger.debug(msg, th);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doDebugEnabled()
     */
    public boolean isDebugEnabled()
    {
        return logger.isDebugEnabled();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doInfo(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void info(String msg, Object arg0, Object arg1)
    {
        logger.info(msg, arg0, arg1);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doWarn(java.lang.String, java.lang.Object, java.lang.Object)
     */
    public void warn(String msg, Object arg0, Object arg1)
    {
        logger.warn(msg, arg0, arg1);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.log.Log#doWarn(java.lang.String, java.lang.Throwable)
     */
    public void warn(String msg, Throwable th)
    {

        if (th instanceof RuntimeException || th instanceof Error)
            logger.error(msg, th);
        else
            logger.warn(msg,th);

    }

    /* ------------------------------------------------------------ */
    public Logger getLogger(String name)
    {
        return new Slf4jLog(name);

    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return logger.toString();
    }

    /* ------------------------------------------------------------ */
    public void setDebugEnabled(boolean enabled)
    {
        warn("setDebugEnabled not implemented",null,null);
    }
}
