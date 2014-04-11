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

package org.eclipse.jetty.util.component;
import java.lang.ref.WeakReference;
import java.util.EventListener;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** Container.
 * This class allows a containment events to be generated from update methods.
 * 
 * The style of usage is: <pre>
 *   public void setFoo(Foo foo)
 *   {
 *       getContainer().update(this,this.foo,foo,"foo");
 *       this.foo=foo;
 *   }
 *   
 *   public void setBars(Bar[] bars)
 *   {
 *       getContainer().update(this,this.bars,bars,"bar");
 *       this.bars=bars;
 *   }
 * </pre>
 */
public class Container
{
    private static final Logger LOG = Log.getLogger(Container.class);
    private final CopyOnWriteArrayList<Container.Listener> _listeners=new CopyOnWriteArrayList<Container.Listener>();
    
    public void addEventListener(Container.Listener listener)
    {
        _listeners.add(listener);
    }
    
    public void removeEventListener(Container.Listener listener)
    {
        _listeners.remove(listener);
    }
    
    /* ------------------------------------------------------------ */
    /** Update single parent to child relationship.
     * @param parent The parent of the child.
     * @param oldChild The previous value of the child.  If this is non null and differs from <code>child</code>, then a remove event is generated.
     * @param child The current child. If this is non null and differs from <code>oldChild</code>, then an add event is generated.
     * @param relationship The name of the relationship
     */
    public void update(Object parent, Object oldChild, final Object child, String relationship)
    {
        if (oldChild!=null && !oldChild.equals(child))
            remove(parent,oldChild,relationship);
        if (child!=null && !child.equals(oldChild))
            add(parent,child,relationship);
    }
    
    /* ------------------------------------------------------------ */
    /** Update single parent to child relationship.
     * @param parent The parent of the child.
     * @param oldChild The previous value of the child.  If this is non null and differs from <code>child</code>, then a remove event is generated.
     * @param child The current child. If this is non null and differs from <code>oldChild</code>, then an add event is generated.
     * @param relationship The name of the relationship
     * @param addRemove If true add/remove is called for the new/old children as well as the relationships
     */
    public void update(Object parent, Object oldChild, final Object child, String relationship,boolean addRemove)
    {
        if (oldChild!=null && !oldChild.equals(child))
        {
            remove(parent,oldChild,relationship);
            if (addRemove)
                removeBean(oldChild);
        }
        
        if (child!=null && !child.equals(oldChild))
        {
            if (addRemove)
                addBean(child);
            add(parent,child,relationship);
        }
    }

    /* ------------------------------------------------------------ */
    /** Update multiple parent to child relationship.
     * @param parent The parent of the child.
     * @param oldChildren The previous array of children.  A remove event is generated for any child in this array but not in the  <code>children</code> array.
     * This array is modified and children that remain in the new children array are nulled out of the old children array.
     * @param children The current array of children. An add event is generated for any child in this array but not in the <code>oldChildren</code> array.
     * @param relationship The name of the relationship
     */
    public void update(Object parent, Object[] oldChildren, final Object[] children, String relationship)
    {
        update(parent,oldChildren,children,relationship,false);
    }
    
    /* ------------------------------------------------------------ */
    /** Update multiple parent to child relationship.
     * @param parent The parent of the child.
     * @param oldChildren The previous array of children.  A remove event is generated for any child in this array but not in the  <code>children</code> array.
     * This array is modified and children that remain in the new children array are nulled out of the old children array.
     * @param children The current array of children. An add event is generated for any child in this array but not in the <code>oldChildren</code> array.
     * @param relationship The name of the relationship
     * @param addRemove If true add/remove is called for the new/old children as well as the relationships
     */
    public void update(Object parent, Object[] oldChildren, final Object[] children, String relationship, boolean addRemove)
    {
        Object[] newChildren = null;
        if (children!=null)
        {
            newChildren = new Object[children.length];
        
            for (int i=children.length;i-->0;)
            {
                boolean new_child=true;
                if (oldChildren!=null)
                {
                    for (int j=oldChildren.length;j-->0;)
                    {
                        if (children[i]!=null && children[i].equals(oldChildren[j]))
                        {
                            oldChildren[j]=null;
                            new_child=false;
                        }
                    }
                }
                if (new_child)
                    newChildren[i]=children[i];
            }
        }
        
        if (oldChildren!=null)
        {
            for (int i=oldChildren.length;i-->0;)
            {
                if (oldChildren[i]!=null)
                {
                    remove(parent,oldChildren[i],relationship);
                    if (addRemove)
                        removeBean(oldChildren[i]);
                }
            }
        }
        
        if (newChildren!=null)
        {
            for (int i=0;i<newChildren.length;i++)
                if (newChildren[i]!=null)
                {
                    if (addRemove)
                        addBean(newChildren[i]);
                    add(parent,newChildren[i],relationship);
                }
        }
    }

    /* ------------------------------------------------------------ */
    public void addBean(Object obj)
    {
        if (_listeners!=null)
        {
            for (int i=0; i<LazyList.size(_listeners); i++)
            {
                Listener listener=(Listener)LazyList.get(_listeners, i);
                listener.addBean(obj);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void removeBean(Object obj)
    {
        if (_listeners!=null)
        {
            for (int i=0; i<LazyList.size(_listeners); i++)
                ((Listener)LazyList.get(_listeners, i)).removeBean(obj);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Add a parent child relationship
     * @param parent
     * @param child
     * @param relationship
     */
    private void add(Object parent, Object child, String relationship)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Container "+parent+" + "+child+" as "+relationship);
        if (_listeners!=null)
        {
            Relationship event=new Relationship(this,parent,child,relationship);
            for (int i=0; i<LazyList.size(_listeners); i++)
                ((Listener)LazyList.get(_listeners, i)).add(event);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** remove a parent child relationship
     * @param parent
     * @param child
     * @param relationship
     */
    private void remove(Object parent, Object child, String relationship)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Container "+parent+" - "+child+" as "+relationship);
        if (_listeners!=null)
        {
            Relationship event=new Relationship(this,parent,child,relationship);
            for (int i=0; i<LazyList.size(_listeners); i++)
                ((Listener)LazyList.get(_listeners, i)).remove(event);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** A Container event.
     * @see Listener
     */
    public static class Relationship
    {
        private final WeakReference<Object> _parent;
        private final WeakReference<Object> _child;
        private String _relationship;
        private Container _container;
        
        private Relationship(Container container, Object parent,Object child, String relationship)
        {
            _container=container;
            _parent=new WeakReference<Object>(parent);
            _child=new WeakReference<Object>(child);
            _relationship=relationship;
        }
        
        public Container getContainer()
        {
            return _container;
        }
        
        public Object getChild()
        {
            return _child.get();
        }
        
        public Object getParent()
        {
            return _parent.get();
        }
        
        public String getRelationship()
        {
            return _relationship;
        }
        
        @Override
        public String toString()
        {
            return _parent+"---"+_relationship+"-->"+_child;
        }
        
        @Override
        public int hashCode()
        {
            return _parent.hashCode()+_child.hashCode()+_relationship.hashCode();
        }
        
        @Override
        public boolean equals(Object o)
        {
            if (o==null || !(o instanceof Relationship))
                return false;
            Relationship r = (Relationship)o;
            return r._parent.get()==_parent.get() && r._child.get()==_child.get() && r._relationship.equals(_relationship);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Listener.
     * A listener for Container events.
     */
    public interface Listener extends EventListener
    {
        public void addBean(Object bean);
        public void removeBean(Object bean);
        public void add(Container.Relationship relationship);
        public void remove(Container.Relationship relationship);
    }
}
