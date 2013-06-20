//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;


/* ------------------------------------------------------------ */
/** A Ternary Trie String lookup data structure.
 * This Trie is of a fixed size and cannot grow (which can be a good thing with regards to DOS when used as a cache).
 * <p>
 * The Trie is stored in 3 arrays:<dl>
 * <dt>char[] _tree</dt><dd>This is semantically 2 dimensional array flattened into a 1 dimensional char array. The second dimension
 * is that every 4 sequential elements represents a row of: character; hi index; eq index; low index, used to build a
 * ternary trie of key strings.</dd>
 * <dt>String[] _key<dt><dd>An array of key values where each element matches a row in the _tree array. A non zero key element 
 * indicates that the _tree row is a complete key rather than an intermediate character of a longer key.</dd>
 * <dt>V[] _value</dt><dd>An array of values corresponding to the _key array</dd>
 * </dl>
 * <p>The lookup of a value will iterate through the _tree array matching characters. If the equal tree branch is followed,
 * then the _key array is looked up to see if this is a complete match.  If a match is found then the _value array is looked up
 * to return the matching value.
 * </p>
 * @param <V>
 */
public class ArrayTernaryTrie<V> extends AbstractTrie<V>
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
    
    public ArrayTernaryTrie(boolean insensitive)
    {
        this(insensitive,128);
    }

    public ArrayTernaryTrie(int capacityInNodes)
    {
        this(true,capacityInNodes);
    }
    
    public ArrayTernaryTrie(boolean insensitive, int capacityInNodes)
    {
        super(insensitive);
        _value=new Object[capacityInNodes];
        _tree=new char[capacityInNodes*ROW_SIZE];
        _key=new String[capacityInNodes];
    }

    /* ------------------------------------------------------------ */
    /** Copy Trie and change capacity by a factor
     * @param trie
     * @param factor
     */
    public ArrayTernaryTrie(ArrayTernaryTrie<V> trie, double factor)
    {
        this(trie.isCaseInsensitive(),(int)(trie._value.length*factor));
        _rows=trie._rows;
        System.arraycopy(trie._value,0,_value,0,trie._value.length);
        System.arraycopy(trie._tree,0,_tree,0,trie._tree.length);
        System.arraycopy(trie._key,0,_key,0,trie._key.length);
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
            if(isCaseInsensitive() && c<128)
                c=StringUtil.lowercases[c];
            
            while (true)
            {
                if (t==0)
                {
                    node=t=++_rows;
                    if (_rows>=_key.length)
                    {
                        _rows--;
                        return false;
                    }
                    int row=ROW_SIZE*t;
                    _tree[row]=c;
                    _tree[last]=(char)t;
                    last=row+EQ;
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
    public V get(String s,int offset, int length)
    {
        int t = _tree[EQ];
        int len = length;
        int i=0;
        while(i<len)
        {
            char c=s.charAt(offset+i++);
            if(isCaseInsensitive() && c<128)
                c=StringUtil.lowercases[c];
            
            while (true)
            {
                int row = ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                
                if (diff==0)
                {
                    if (i==len)
                        return (V)_value[t];
                    t=_tree[row+EQ];
                    if (t==0)
                        return null;
                    break;
                }

                t=_tree[row+hilo(diff)];
                if (t==0)
                    return null;
            }
        }
        
        return null;
    }

    
    @Override
    public V get(ByteBuffer b, int offset, int length)
    {
        int t = _tree[EQ];
        int len = length;
        int i=0;
        offset+=b.position();
        
        while(i<len)
        {
            byte c=(byte)(b.get(offset+i++)&0x7f);
            if(isCaseInsensitive())
                c=(byte)StringUtil.lowercases[c];
            
            while (true)
            {
                int row = ROW_SIZE*t;
                char n=_tree[row];
                int diff=n-c;
                
                if (diff==0)
                {
                    if (i==len)
                        return (V)_value[t];
                    t=_tree[row+EQ];
                    if (t==0)
                        return null;
                    break;
                }

                t=_tree[row+hilo(diff)];
                if (t==0)
                    return null;
            }
        }
        
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public V getBest(String s)
    {
        return getBest(_tree[EQ],s,0,s.length());
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public V getBest(String s, int offset, int length)
    {
        return getBest(_tree[EQ],s,offset,length);
    }

    /* ------------------------------------------------------------ */
    private V getBest(int t,String s,int offset,int len)
    {
        int node=0;
        for(int i=0; t!=0 && i<len; i++)
        {
            char c=s.charAt(offset+i);
            if(isCaseInsensitive() && c<128)
                c=StringUtil.lowercases[c];

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
                        V best=getBest(t,s,offset+i+1,len-i-1);
                        if (best!=null)
                            return best;
                        return (V)_value[node];
                    }
                    
                    break;
                }

                t=_tree[row+hilo(diff)];
            }
        }
        return null;
    }


    /* ------------------------------------------------------------ */
    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (b.hasArray())
            return getBest(_tree[EQ],b.array(),b.arrayOffset()+b.position()+offset,len);
        return getBest(_tree[EQ],b,offset,len);
    }

    /* ------------------------------------------------------------ */
    private V getBest(int t,byte[] b, int offset, int len)
    {
        int node=0;
        for(int i=0; t!=0 && i<len; i++)
        {
            byte c=(byte)(b[offset+i]&0x7f);
            if(isCaseInsensitive())
                c=(byte)StringUtil.lowercases[c];

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

                t=_tree[row+hilo(diff)];
            }
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    private V getBest(int t,ByteBuffer b, int offset, int len)
    {
        int node=0;
        int o= offset+b.position();
        
        for(int i=0; t!=0 && i<len; i++)
        {
            byte c=(byte)(b.get(o+i)&0x7f);
            if(isCaseInsensitive())
                c=(byte)StringUtil.lowercases[c];

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

                t=_tree[row+hilo(diff)];
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
    
    public static int hilo(int diff)
    {
        // branchless equivalent to return ((diff<0)?LO:HI);
        // return 3+2*((diff&Integer.MIN_VALUE)>>Integer.SIZE-1);
        return 1+(diff|Integer.MAX_VALUE)/(Integer.MAX_VALUE/2);
    }
    
    public void dump()
    {
        for (int r=0;r<=_rows;r++)
        {
            char c=_tree[r*ROW_SIZE+0];
            System.err.printf("%4d [%s,%d,%d,%d] %s:%s%n",
                r,
                (c<' '||c>127)?(""+(int)c):"'"+c+"'",
                (int)_tree[r*ROW_SIZE+LO],
                (int)_tree[r*ROW_SIZE+EQ],
                (int)_tree[r*ROW_SIZE+HI],
                _key[r],
                _value[r]);
        }
        
    }
}
