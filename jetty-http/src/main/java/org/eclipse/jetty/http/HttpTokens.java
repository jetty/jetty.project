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

package org.eclipse.jetty.http;

/**
 * HTTP constants
 */
public interface HttpTokens
{
    // Terminal symbols.
    static final byte COLON= (byte)':';
    static final byte TAB= 0x09;
    static final byte LINE_FEED= 0x0A;
    static final byte CARRIAGE_RETURN= 0x0D;
    static final byte SPACE= 0x20;
    static final byte[] CRLF = {CARRIAGE_RETURN,LINE_FEED};
    static final byte SEMI_COLON= (byte)';';

    public enum EndOfContent { UNKNOWN_CONTENT,NO_CONTENT,EOF_CONTENT,CONTENT_LENGTH,CHUNKED_CONTENT }

}

