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

public class SearchPattern
{
    static final int alphabetSize = 256;
    int[] table;
    byte[] pattern;

    public int[] getTable(){ return this.table; }
    
    /**
     * @param pattern  The pattern to search for.
     * @return A Pattern instance for the search pattern
     */
    static SearchPattern compile(byte[] pattern)
    {
        //Create new SearchPattern instance
        SearchPattern sp = new SearchPattern();
        
        //Copy in the Pattern
        sp.pattern = pattern.clone();
        
        //Build up the pre-processed table for this pattern.
        sp.table = new int[alphabetSize];
        for(int i = 0; i<sp.table.length; ++i)
            sp.table[i] = sp.pattern.length;
        for(int i = 0; i<sp.pattern.length-1; ++i)
            sp.table[sp.pattern[i]] = sp.pattern.length-1-i;
        
        return sp;
    }
    
    
    /**
     * Search for a complete match of the pattern within the data
     * @param data The data in which to search for. The data may be arbitrary binary data, 
     * but the pattern will always be {@link StandardCharsets#US_ASCII} encoded.
     * @param offset The offset within the data to start the search
     * @param length The length of the data to search
     * @return The index within the data array at which the first instance of the pattern or -1 if not found
     */
    public int match(byte[] data, int offset, int length)
    {
        int skip = offset;
        while((skip <= data.length - pattern.length) && (skip+pattern.length <= offset+length))
        {            
            for(int i = pattern.length-1; data[skip+i] == pattern[i]; i--) 
                if(i==0) return skip;
            
            skip += table[data[skip + pattern.length - 1]];
        }
        
        return -1;
    }
    
    /**
     * Search for a partial match of the pattern at the end of the data.
     * @param data The data in which to search for. The data may be arbitrary binary data, 
     * but the pattern will always be {@link StandardCharsets#US_ASCII} encoded.
     * @param offset The offset within the data to start the search
     * @param length The length of the data to search
     * @return the length of the partial pattern matched or -1 for no match.
     */
    public int endsPartiallyWith(byte data, int offset, int length)
    {
        return -1;
    }
    
    /**
     * 
     * Search for a partial match of the pattern at the start of the data.
     * @param data The data in which to search for. The data may be arbitrary binary data, 
     * but the pattern will always be {@link StandardCharsets#US_ASCII} encoded.
     * @param offset The offset within the data to start the search
     * @param length The length of the data to search
     * @param matched The length of the partial pattern already matched
     * @return the length of the partial pattern matched or -1 for no match.
     */
    public int startsPartialyWith(byte[] data, int offset, int length, int matched)
    {
        return -1;    
    }
}
