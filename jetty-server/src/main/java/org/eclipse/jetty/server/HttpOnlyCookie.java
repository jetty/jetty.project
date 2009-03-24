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

import javax.servlet.http.Cookie;

/* ------------------------------------------------------------ */
/** HttpOnlyCookie.
 * 
 * <p>
 * Implements  {@link javax.servlet.Cookie} from the {@link javax.servlet} package.   
 * </p>
 * This derivation of javax.servlet.http.Cookie can be used to indicate
 * that the microsoft httponly extension should be used.
 * The addSetCookie method on HttpFields checks for this type.
 * @deprecated use {@link javax.servlet.Cookie#setHttpOnly(boolean)}
 * 
 *
 */
public class HttpOnlyCookie extends Cookie
{

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     */
    public HttpOnlyCookie(String name, String value)
    {
        super(name, value);
	setHttpOnly(true);
    }

}
