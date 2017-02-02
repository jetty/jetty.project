//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

/** 
 * Abstract Trie implementation.
 * <p>Provides some common implementations, which may not be the most
 * efficient. For byte operations, the assumption is made that the charset
 * is ISO-8859-1</p>
 * 
 * @param <V> the type of object that the Trie holds
 */
public abstract class AbstractTrie<V> implements Trie<V>
{
    final boolean _caseInsensitive;
    
    protected AbstractTrie(boolean insensitive)
    {
        _caseInsensitive=insensitive;
    }

    @Override
    public boolean isCaseInsensitive()
    {
        return _caseInsensitive;
    }

}
