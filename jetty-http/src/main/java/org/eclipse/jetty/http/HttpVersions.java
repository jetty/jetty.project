//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache;

/* ------------------------------------------------------------------------------- */
/** 
 * 
 * 
 */
public class HttpVersions
{
	public final static String
		HTTP_0_9 = "",
		HTTP_1_0 = "HTTP/1.0",
		HTTP_1_1 = "HTTP/1.1";
		
	public final static int
		HTTP_0_9_ORDINAL=9,
		HTTP_1_0_ORDINAL=10,
		HTTP_1_1_ORDINAL=11;
	
	public final static BufferCache CACHE = new BufferCache();
	
    public final static Buffer 
        HTTP_0_9_BUFFER=CACHE.add(HTTP_0_9,HTTP_0_9_ORDINAL),
        HTTP_1_0_BUFFER=CACHE.add(HTTP_1_0,HTTP_1_0_ORDINAL),
        HTTP_1_1_BUFFER=CACHE.add(HTTP_1_1,HTTP_1_1_ORDINAL);
}
