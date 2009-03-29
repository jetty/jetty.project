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

package org.eclipse.jetty.server.handler;

import java.util.List;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.server.Request;

/**
 * An interface for handlers that wish to be notified of request completion.
 * 
 * If the request attribute COMPLETE_HANDLER_ATTR is set as either a single 
 * CompleteHandler instance or a {@link List} of CompleteHandler instances,
 * then when the {@link ServletRequest#complete()} method is called, then 
 * the {@link #complete(Request)} method is called for each CompleteHandler.
 * 
 * 
 *
 */
public interface CompleteHandler
{
    public final static String COMPLETE_HANDLER_ATTR = "org.eclipse.jetty.server.handler.CompleteHandlers";
    void complete(Request request);
}
