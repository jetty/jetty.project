//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;


/* ------------------------------------------------------------ */
/** A Ternary Trie String lookup data structure.
 * This Trie is of a fixed size and cannot grow (which can be a good thing with regards to DOS when used as a cache).
 * @param <V>
 */
public class ArrayTernaryTrie<V> implements Trie<V>
{
    private static int LO=1;
    private static int EQ=2;
    private static int HI=3;
    
    /**
     * The Size of a Trie row is the char, and the low, equal and high
     * child pointers
     */
    private static final int ROW_SIZE = 4;
    
    /**
     * The Trie rows in a single array which allows a lookup of row,character
     * to the next row in the Trie.  This is actually a 2 dimensional
     * array that has been flattened to achieve locality of reference.
     */
    private final char[] _tree;
    
    /**
     * The key (if any) for a Trie row. 
     * A row may be a leaf, a node or both in the Trie tree.
     */
    private final String[] _key;
    
    /**
     * The value (if any) for a Trie row. 
     * A row may be a leaf, a node or both in the Trie tree.
     */
    private final Object[] _value;
    
    
    /**
     * The number of rows allocated
     */
    private char _rows;

    public ArrayTernaryTrie()
    {
        this(128);
    }
    
    public ArrayTernaryTrie(int capacityInNodes)
    {
        _value=new Object[capacityInNodes];
        _tree=new char[capacityInNodes*ROW_SIZE];
        _key=new String[capacityInNodes];
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean put(String s, V v)
    {
        int last=EQ;
        int t=_tree[last];
        int k;
        int limit = s.length();
        int node=0;
        for(k=0; k < limit; k++)
        {
            char c=s.charAt(k);
            if (c<128)
              c=StringUtil.lowercases[c&0x7f];
            
            while (true)
            {
                if (t==0)
                {
                    node=t=++_rows;
                    if (_rows==_key.length)
                        return false;
                    int row=ROW_SIZE*t;
                    _tree[row]=c;
                    _tree[last]=(char)t;
                    last=row+EQ;
                    t=0;
                    break;
                }

                int row=ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                if (diff==0)
                {
                    node=t;
                    t=_tree[last=(row+EQ)];
                    break;
                }
                if (diff<0)
                    t=_tree[last=(row+LO)];
                else
                    t=_tree[last=(row+HI)];
            }
        }
        _key[node]=v==null?null:s;
        _value[node] = v;
        
        return true;
    }


    /* ------------------------------------------------------------ */
    @Override
    public boolean put(V v)
    {
        return put(v.toString(),v);
    }

    /* ------------------------------------------------------------ */
    @Override
    public V remove(String s)
    {
        V o=get(s);
        put(s,null);
        return o;
    }

    /* ------------------------------------------------------------ */
    @Override
    public V get(String s)
    {
        int t = _tree[EQ];
        int len = s.length();
        int node=0;
        for(int i=0; t!=0 && i < len ; i++)
        {
            char c=StringUtil.lowercases[s.charAt(i)&0x7f];
            while (t!=0)
            {
                int row = ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                
                if (diff==0)
                {
                    node=t;
                    t=_tree[row+EQ];
                    break;
                }

                if (diff<0)
                    t=_tree[row+LO];
                else
                    t=_tree[row+HI];
            }
        }
        
        return (V)_value[node];
    }

    /* ------------------------------------------------------------ */
    @Override
    public V get(ByteBuffer b,int offset,int len)
    {
        int t = _tree[EQ];
        int node=0;
        for(int i=0; t!=0 && i<len; i++)
        {
            char c=StringUtil.lowercases[b.get(offset+i)&0x7f];
            
            while (t!=0)
            {
                int row = ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                
                if (diff==0)
                {
                    node=t;
                    t=_tree[row+EQ];
                    break;
                }

                if (diff<0)
                    t=_tree[row+LO];
                else
                    t=_tree[row+HI];
            }
        }
        
        return (V)_value[node];
    }

    /* ------------------------------------------------------------ */
    @Override
    public V getBest(byte[] b,int offset,int len)
    {
        return getBest(_tree[EQ],b,offset,len);
    }

    /* ------------------------------------------------------------ */
    @Override
    public V getBest(ByteBuffer b,int offset,int len)
    {
        if (b.hasArray())
            return getBest(_tree[EQ],b.array(),b.arrayOffset()+b.position()+offset,len);
        return getBest(_tree[EQ],b,offset,len);
    }
    
    private V getBest(int t,byte[] b,int offset,int len)
    {
        int node=0;
        for(int i=0; t!=0 && i<len; i++)
        {
            char c=StringUtil.lowercases[b[offset+i]&0x7f];

            while (t!=0)
            {
                int row = ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                
                if (diff==0)
                {
                    node=t;
                    t=_tree[row+EQ];
                    
                    // if this node is a match, recurse to remember 
                    if (_key[node]!=null)
                    {
                        V best=getBest(t,b,offset+i+1,len-i-1);
                        if (best!=null)
                            return best;
                        return (V)_value[node];
                    }
                    
                    break;
                }

                if (diff<0)
                    t=_tree[row+LO];
                else
                    t=_tree[row+HI];
            }
        }
        return null;
    }

    private V getBest(int t,ByteBuffer b,int offset,int len)
    {
        int pos=b.position()+offset;
        int node=0;
        for(int i=0; t!=0 && i<len; i++)
        {
            char c=StringUtil.lowercases[b.get(pos++)&0x7f];

            while (t!=0)
            {
                int row = ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                
                if (diff==0)
                {
                    node=t;
                    t=_tree[row+EQ];
                    
                    // if this node is a match, recurse to remember 
                    if (_key[node]!=null)
                    {
                        V best=getBest(t,b,offset+i+1,len-i-1);
                        if (best!=null)
                            return best;
                        return (V)_value[node];
                    }
                    
                    break;
                }

                if (diff<0)
                    t=_tree[row+LO];
                else
                    t=_tree[row+HI];
            }
        }
        return null;
    }
    
    
    

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        for (int r=1;r<=_rows;r++)
        {
            if (_key[r]!=null && _value[r]!=null)
            {
                buf.append(',');
                buf.append(_key[r]);
                buf.append('=');
                buf.append(_value[r].toString());
            }
        }
        if (buf.length()==0)
            return "{}";
        
        buf.setCharAt(0,'{');
        buf.append('}');
        return buf.toString();
    }



    @Override
    public Set<String> keySet()
    {
        Set<String> keys = new HashSet<>();

        for (int r=1;r<=_rows;r++)
        {
            if (_key[r]!=null && _value[r]!=null)
                keys.add(_key[r]);
        }
        return keys;
    }

    @Override
    public boolean isFull()
    {
        return _rows+1==_key.length;
    }
}
