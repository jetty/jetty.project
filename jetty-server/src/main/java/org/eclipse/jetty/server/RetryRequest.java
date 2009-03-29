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

package org.eclipse.jetty.server;

/* ------------------------------------------------------------ */
/** Retry Request
 * This is thrown by a non-blocking {@link Continuation} such as
 * {@link SuspendableSelectChannelEndPoint}.  While it
 * extends ThreadDeath, it does not actually stop the thread calling it.
 * It extends ThreadDeath so as to be an Error that will not be caught
 * by most frameworks.
 * 
 * 
 *
 */
public class RetryRequest extends ThreadDeath
{
}
