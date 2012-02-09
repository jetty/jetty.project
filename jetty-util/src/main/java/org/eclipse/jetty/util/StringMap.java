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

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/* ------------------------------------------------------------ */
/** Map implementation Optimized for Strings keys..
 * This String Map has been optimized for mapping small sets of
 * Strings where the most frequently accessed Strings have been put to
 * the map first.
 *
 * It also has the benefit that it can look up entries by substring or
 * sections of char and byte arrays.  This can prevent many String
 * objects from being created just to look up in the map.
 *
 * This map is NOT synchronized.
 */
public class StringMap<O> extends AbstractMap<String,O>
{
    public static final boolean CASE_INSENSTIVE=true;
    protected static final int __HASH_WIDTH=17;
    
    /* ------------------------------------------------------------ */

    private final boolean _ignoreCase;
    
    protected Node<O> _root;
    protected HashSet<Map.Entry<String,O>> _entrySet=new HashSet<>(3);
    
    /* ------------------------------------------------------------ */
    /** Constructor. 
     */
    public StringMap()
    {
        _ignoreCase=false;
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor. 
     * @param ignoreCase 
     */
    public StringMap(boolean ignoreCase)
    {
        _ignoreCase=ignoreCase;
    }

    /* ------------------------------------------------------------ */
    public boolean isIgnoreCase()
    {
        return _ignoreCase;
    }
    
    public static void main(String[] arg)
    {
        StringMap<String> map = new StringMap<>();
        
        System.err.println("null="+map.get("nothing"));
        
        map.put("foo","1");
        System.err.println("null="+map.get("nothing"));
        System.err.println("null="+map.get("foobar"));
        System.err.println("1="+map.get("foo"));
        System.err.println("null="+map.get("fo"));
        
        map.put("foobar","2");
        System.err.println("null="+map.get("nothing"));
        System.err.println("2="+map.get("foobar"));
        System.err.println("1="+map.get("foo"));
        System.err.println("null="+map.get("fo"));
        System.err.println("null="+map.get("foob"));
        
        map.put("foob","3");
        System.err.println("null="+map.get("nothing"));
        System.err.println("2="+map.get("foobar"));
        System.err.println("3="+map.get("foob"));
        System.err.println("1="+map.get("foo"));
        
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public O put(String key, O value)
    {
        if (key==null)
            throw new IllegalArgumentException();

        Node<O> node = _root;
        Node<O> last = null;
        
        for (int i=0;i<key.length();i++)
        {
            char ch = key.charAt(i);
            if (_ignoreCase)
                ch=Character.toLowerCase(ch);
            
            if (node==null)
            {
                node = new Node<O>(key,value,_ignoreCase);
                _entrySet.add(node);
                if (last==null)
                    _root=node;
                else
                {
                    throw new Error("unimplemented");
                }

                return null;
            }
            else
            {
                last=node;
                node=node.nextNode(i,ch);
                
                if (node==null)
                {
                    node = new Node<O>(key,value,_ignoreCase);
                    _entrySet.add(node);
                    last.addNext(node);
                    return null;
                }   
                    
            }
        }
        
        if (node!=null)
        {
            if (key.equals(node.getKey()))
                return node.setValue(value);
            
        }

        
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public O get(Object key)
    {
        if (key==null)
            throw new IllegalArgumentException();
        return get(key.toString());
    }
    
    /* ------------------------------------------------------------ */
    public O get(String key)
    {
        if (key==null)
            throw new IllegalArgumentException();

        
        Map.Entry<String,O> entry = getEntry(key,0,key.length());
        if (entry==null)
            return null;
        return entry.getValue();
    }
    
    /* ------------------------------------------------------------ */
    public O get(ByteBuffer buffer, int position, int length)
    {
        Map.Entry<String,O> entry = getEntry(buffer,position,length);
        if (entry==null)
            return null;
        return entry.getValue();
    }
    
    /* ------------------------------------------------------------ */
    /** Get a map entry by substring key.
     * @param key String containing the key
     * @param offset Offset of the key within the String.
     * @param length The length of the key 
     * @return The Map.Entry for the key or null if the key is not in
     * the map.
     */
    public Map.Entry<String,O> getEntry(String key,int offset, int length)
    {
        if (key==null)
            throw new IllegalArgumentException();

        Node<O> node = _root;
        
        for (int i=0;i<length;i++)
        {
            char ch = key.charAt(offset+i);
            if (_ignoreCase)
                ch=Character.toLowerCase(ch);
            
            if (node==null)
                return null;
            
            node=node.nextNode(i,ch);
        }
        
        return node.getKey()==null&&node.length()==length?null:node;
    }
    
    /* ------------------------------------------------------------ */
    /** Get a map entry by char array key.
     * @param key char array containing the key
     * @param offset Offset of the key within the array.
     * @param length The length of the key 
     * @return The Map.Entry for the key or null if the key is not in
     * the map.
     */
    public Map.Entry<String, O> getEntry(char[] key,int offset, int length)
    {
        if (key==null)
            throw new IllegalArgumentException();
        
        Node<O> node = _root;
        return node;
    }

    /* ------------------------------------------------------------ */
    /** Get a map entry by byte array key, using as much of the passed key as needed for a match.
     * A simple 8859-1 byte to char mapping is assumed.
     * @param key byte array containing the key
     * @param offset Offset of the key within the array.
     * @param length The length of the key 
     * @return The Map.Entry for the key or null if the key is not in
     * the map.
     */
    public Map.Entry<String,O> getEntry(byte[] key,int offset, int length)
    {
        if (key==null)
            throw new IllegalArgumentException();
        
        Node<O> node = _root;

        
        
        return node;
    }

    /* ------------------------------------------------------------ */
    /** Get a map entry by ByteBuffer key, using as much of the passed key as needed for a match.
     * A simple 8859-1 byte to char mapping is assumed.
     * @param key ByteBuffer containing the key
     * @return The Map.Entry for the key or null if the key is not in
     * the map.
     */
    public Map.Entry<String,O> getEntry(ByteBuffer key)
    {
        return getEntry(key,key.position(),key.remaining());
    }
    
    /* ------------------------------------------------------------ */
    /** Get a map entry by ByteBuffer key, using as much of the passed key as needed for a match.
     * A simple 8859-1 byte to char mapping is assumed.
     * @param key ByteBuffer containing the key
     * @return The Map.Entry for the key or null if the key is not in
     * the map.
     */
    public Map.Entry<String,O> getEntry(ByteBuffer key,int position,int length)
    {
        if (key==null)
            throw new IllegalArgumentException();
        
        if (!key.isReadOnly() && !key.isDirect())
            return getEntry(key.array(),key.position(),key.remaining());
        
        Node<O> node = _root;
        return node;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public O remove(Object key)
    {
        if (key==null)
            return remove(null);
        return remove(key.toString());
    }
    
    
    /* ------------------------------------------------------------ */
    public O remove(String key)
    {
        if (key==null)
            throw new IllegalArgumentException();
        
        Node<O> node = _root;
        O old = node._value;
        _entrySet.remove(node);
        node._value=null;
        
        return old; 
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<Map.Entry<String,O>> entrySet()
    {
        return Collections.unmodifiableSet(_entrySet);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _entrySet.size();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isEmpty()
    {
        return _entrySet.isEmpty();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsKey(Object key)
    {
        if (key==null)
            return false;
        return
            getEntry(key.toString(),0,key==null?0:key.toString().length())!=null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void clear()
    {
        _root=null;
        _entrySet.clear();
    }

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class Node<O> implements Map.Entry<String,O>
    {
        private final String _key;
        private O _value;
        private final char[] _chars;
        private final int _length;
        private final Node<O>[] _splits;
        private Node<O> _next;
        
        
        Node(String key, O value, boolean caseInsensitive)
        {
            _key=key;
            _value=value;
            _chars = _key.toCharArray();
            _length=_chars.length;
            if (caseInsensitive)
                for (int i=_length;i-->0;)
                    _chars[i]=Character.toLowerCase(_chars[i]);
            _splits=null;
        }
        
        public int length()
        {
            return _length;
        }

        Node(char[] chars,int length)
        {
            _key=null;
            _value=null;
            _chars = chars;
            _length=length;
            _splits=new Node[__HASH_WIDTH];
        }
        
        Node<O> nextNode(int index,char c)
        {
            if (index<_chars.length)
            {
                if (_chars[index]==c)
                    return this;
                if (_next!=null)
                    return _next.nextNode(index,c);
            }
            
            if (index==_chars.length && _splits!=null)
            {
                Node<O> split=_splits[c%_splits.length];
                if (split!=null)
                    return split.nextNode(index,c);
            }

            if (_next!=null)
                return _next.nextNode(index,c);
            
            return null;
        }
        
        Node<O> split(int index)
        {
            Node<O> pre = new Node(_chars,index);
            pre.addSplit(this);
            return pre;
        }
        
        void addSplit(Node<O> split)
        {
            char c=_chars[_length];
            int i=c%__HASH_WIDTH;
            Node<O> s=_splits[i];
            if (s==null)
                _splits[i]=split;
            else
                _splits[i].addNext(split);
        }
        
        void addNext(Node<O> next)
        {
            Node<O> s = this;
            while (s._next!=null)
                s=s._next;
            s._next=next;
        }
        
        
        @Override
        public String getKey()
        {
            return _key;
        }

        @Override
        public O getValue()
        {
            return _value;
        }

        @Override
        public O setValue(O value)
        {
            O old=_value;
            _value=value;
            return old;
        }
    }

}
