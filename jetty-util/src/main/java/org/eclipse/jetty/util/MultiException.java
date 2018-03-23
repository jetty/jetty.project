//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 
 * Wraps multiple exceptions.
 *
 * Allows multiple exceptions to be thrown as a single exception.
 */
@SuppressWarnings("serial")
public class MultiException extends Exception
{
    /* ------------------------------------------------------------ */
    public MultiException()
    {
        super("Multiple exceptions");
    }

    /* ------------------------------------------------------------ */
    public void add(Throwable e)
    {
        if (e==null)
            throw new IllegalArgumentException();

        if (e instanceof MultiException)
            Arrays.stream(MultiException.class.cast(e).getSuppressed()).forEach(this::add);
        else
        {
            if (getCause()==null)
                initCause(e);
            else
                addSuppressed(e);
        }
    }

    /* ------------------------------------------------------------ */
    public int size()
    {
        if (getCause()==null)
            return 0;
        return 1+getSuppressed().length;
    }
    
    /* ------------------------------------------------------------ */
    public List<Throwable> getThrowables()
    {
        if (getCause()==null)
            return Collections.emptyList();
        
        Throwable[] suppressed = getSuppressed();
        List<Throwable> list = new ArrayList<>(suppressed.length+1);
        list.add(getCause());
        Arrays.stream(suppressed).forEach(list::add);
        return list;
    }
    
    /* ------------------------------------------------------------ */
    public Throwable getThrowable(int i)
    {
        if (getCause()==null)
            throw new ArrayIndexOutOfBoundsException();
        if (i==0)
            return getCause();
        return getSuppressed()[i-1];
    }

    /* ------------------------------------------------------------ */
    /** Throw a multiexception.
     * If this multi exception is empty then no action is taken. If it
     * contains a single exception then that is thrown, otherwise the this
     * multi exception is thrown. 
     * @exception Exception the Error or Exception if nested is 1, or 
     *            the MultiException itself if nested is more than 1.
     */
    public void ifExceptionThrow()
        throws Exception
    {
        Throwable cause=getCause();
        if (cause==null)
            return;
        
        Throwable[] suppressed = getSuppressed();
        
        if (suppressed.length==0)
        {
            if (cause instanceof Error)
                throw (Error)cause;
            if (cause instanceof Exception)
                throw (Exception)cause;
        }
        
        throw this;
    }
    

    /* ------------------------------------------------------------ */
    /** Throw an Exception, potentially with suppress.
     * If this multi exception is empty then no action is taken. If the first
     * exception added is an Error or Exception, then that is throw with 
     * any additional exceptions added as suppressed. Otherwise this exception
     * is thrown.
     * @exception Exception the Error or Exception if at least one is added.
     */
    public void ifExceptionThrowSuppressed()
        throws Exception
    {
        Throwable cause=getCause();
        if (cause==null)
            return;

        if (cause instanceof Error)
        {
            Arrays.stream(getSuppressed()).forEach(cause::addSuppressed);
            throw (Error)cause;
        }
        
        if (cause instanceof Exception)
        {
            Arrays.stream(getSuppressed()).forEach(cause::addSuppressed);
            throw (Exception)cause;
        }
  
        throw this;
    }
    
    /* ------------------------------------------------------------ */
    /** Throw a Runtime exception.
     * If this multi exception is empty then no action is taken. If it
     * contains a single error or runtime exception that is thrown, otherwise the this
     * multi exception is thrown, wrapped in a runtime exception. 
     * @exception Error If this exception contains exactly 1 {@link Error} 
     * @exception RuntimeException If this exception contains 1 {@link Throwable} but it is not an error,
     *                             or it contains more than 1 {@link Throwable} of any type.
     */
    public void ifExceptionThrowRuntime()
        throws Error
    {        
        Throwable cause = getCause();
        if (cause==null)
            return;

        Throwable[] nested = getSuppressed();

        if (nested.length==0)
        {
            if (cause instanceof Error)
                throw (Error)cause;
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            throw new RuntimeException(cause);
        }
        
        throw new RuntimeException(this);
    }
    
    /* ------------------------------------------------------------ */
    /** Throw a MultiException.
     * If this multi exception is empty then no action is taken. If it
     * contains a any exceptions then this multi exception is thrown. 
     * @throws MultiException the multiexception if there are nested exception
     */
    public void ifExceptionThrowMulti()
        throws MultiException
    {
        if (getCause()==null)
            return;

        throw this;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(MultiException.class.getSimpleName());
        str.append(Arrays.asList(getSuppressed()));
        return str.toString();
    }

}
