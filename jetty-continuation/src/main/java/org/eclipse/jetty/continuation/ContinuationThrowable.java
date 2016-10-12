//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.continuation;


/* ------------------------------------------------------------ */
/** ContinuationThrowable
 * <p>
 * A ContinuationThrowable is throw by {@link Continuation#undispatch()}
 * in order to exit the dispatch to a Filter or Servlet.  Use of
 * ContinuationThrowable is discouraged and it is preferable to 
 * allow return to be used. ContinuationThrowables should only be
 * used when there is a Filter/Servlet which cannot be modified
 * to avoid committing a response when {@link Continuation#isSuspended()}
 * is true.
 * </p>
 * <p>
 * ContinuationThrowable instances are often reused so that the
 * stack trace may be entirely unrelated to the calling stack.
 * A real stack trace may be obtained by enabling debug.
 * </p>
 * <p>
 * ContinuationThrowable extends Error as this is more likely
 * to be uncaught (or rethrown) by a Filter/Servlet.  A ContinuationThrowable
 * does not represent and error condition.
 * </p>
 */
public class ContinuationThrowable extends Error
{
    public ContinuationThrowable()
    {
        super(null, null, false, false);
    }
}
