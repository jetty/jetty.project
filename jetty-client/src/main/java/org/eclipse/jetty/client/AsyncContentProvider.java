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

package org.eclipse.jetty.client;

import java.util.EventListener;

import org.eclipse.jetty.client.api.ContentProvider;

/**
 * A {@link ContentProvider} that notifies listeners that content is available.
 */
public interface AsyncContentProvider extends ContentProvider
{
    /**
     * @param listener the listener to be notified of content availability
     */
    public void setListener(Listener listener);

    /**
     * A listener that is notified of content availability
     */
    public interface Listener extends EventListener
    {
        /**
         * Callback method invoked when content is available
         */
        public void onContent();
    }
}
