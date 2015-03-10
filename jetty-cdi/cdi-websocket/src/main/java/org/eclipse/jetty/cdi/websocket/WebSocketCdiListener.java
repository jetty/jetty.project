package org.eclipse.jetty.cdi.websocket;

import java.util.Set;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketSessionScope;

public class WebSocketCdiListener implements LifeCycle.Listener, Container.InheritedListener
{
    private static final Logger LOG = Log.getLogger(WebSocketCdiListener.class);
    private final ContainerLifeCycle container;

    public WebSocketCdiListener(ContainerLifeCycle container)
    {
        this.container = container;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> ScopedInstance<T> newInstance(Class<T> clazz)
    {
        BeanManager bm = CDI.current().getBeanManager();

        ScopedInstance sbean = new ScopedInstance();
        Set<Bean<?>> beans = bm.getBeans(clazz,AnyLiteral.INSTANCE);
        if (beans.size() > 0)
        {
            sbean.bean = beans.iterator().next();
            sbean.creationalContext = bm.createCreationalContext(sbean.bean);
            sbean.instance = bm.getReference(sbean.bean,clazz,sbean.creationalContext);
            return sbean;
        }
        else
        {
            throw new RuntimeException(String.format("Can't find class %s",clazz));
        }
    }

    private ScopedInstance<WebSocketScopeContext> wsScope;

    private synchronized ScopedInstance<WebSocketScopeContext> getWebSocketScope()
    {
        if (wsScope == null)
        {
            wsScope = newInstance(WebSocketScopeContext.class);
        }
        return wsScope;
    }

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
        if (event instanceof WebSocketContainerScope)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("starting websocket container [{}]", event);
            }
            getWebSocketScope().instance.begin();
        }
        else if (event instanceof WebSocketSessionScope)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("starting websocket session [{}]", event);
            }
            getWebSocketScope().instance.setSession((Session)event);
        }
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
    }

    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {
    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
    }

    @Override
    public void lifeCycleStopped(LifeCycle event)
    {
        if (event instanceof WebSocketContainerScope)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("stopped websocket container [{}]", event);
            }
            getWebSocketScope().instance.end();
        }
        else if (event == container)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("stopped parent container [{}]", event);
            }
            if (wsScope != null)
            {
                wsScope.destroy();
            }
        }
    }

    @Override
    public void beanAdded(Container parent, Object child)
    {
        if (child instanceof LifeCycle)
        {
            ((LifeCycle)child).addLifeCycleListener(this);
        }
    }

    @Override
    public void beanRemoved(Container parent, Object child)
    {
        if (child instanceof LifeCycle)
        {
            ((LifeCycle)child).removeLifeCycleListener(this);
        }
    }
}
