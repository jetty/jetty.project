// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * A Handler that contains other Handlers.
 * <p>
 * The contained handlers may be one (see @{link {@link org.eclipse.jetty.server.server.handler.HandlerWrapper})
 * or many (see {@link org.eclipse.jetty.server.server.handler.HandlerList} or {@link org.eclipse.jetty.server.server.handler.HandlerCollection}. 
 *
 */
public interface HandlerContainer extends LifeCycle
{
    public void addHandler(Handler handler);
    public void removeHandler(Handler handler);

    public Handler[] getChildHandlers();
    public Handler[] getChildHandlersByClass(Class<?> byclass);
    public Handler getChildHandlerByClass(Class<?> byclass);
}
