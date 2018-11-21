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

package org.eclipse.jetty.cdi.websocket.basicscope;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Set;

import javax.enterprise.inject.spi.Bean;

import org.eclipse.jetty.cdi.core.AnyLiteral;
import org.eclipse.jetty.cdi.core.ScopedInstance;
import org.eclipse.jetty.cdi.core.logging.Logging;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ScopeBasicsTest
{
    private static Weld weld;
    private static WeldContainer container;

    @BeforeAll
    public static void startWeld()
    {
        Logging.config();
        weld = new Weld();
        container = weld.initialize();
    }

    @AfterAll
    public static void stopWeld()
    {
        weld.shutdown();
    }

    /**
     * Validation of Scope / Inject logic on non-websocket-scoped classes
     * @throws Exception on test failure
     */
    @Test
    public void testBasicBehavior() throws Exception
    {
        ScopedInstance<Meal> meal1Bean = newInstance(Meal.class);
        Meal meal1 = meal1Bean.instance;
        ScopedInstance<Meal> meal2Bean = newInstance(Meal.class);
        Meal meal2 = meal2Bean.instance;

        assertThat("Meals are not the same",meal1,not(sameInstance(meal2)));

        assertThat("Meal 1 Entree Constructed",meal1.getEntree().isConstructed(),is(true));
        assertThat("Meal 1 Side Constructed",meal1.getSide().isConstructed(),is(true));

        assertThat("Meal parts not the same",meal1.getEntree(),not(sameInstance(meal1.getSide())));
        assertThat("Meal entrees are the same",meal1.getEntree(),not(sameInstance(meal2.getEntree())));
        assertThat("Meal sides are the same",meal1.getSide(),not(sameInstance(meal2.getSide())));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> ScopedInstance<T> newInstance(Class<T> clazz) throws Exception
    {
        ScopedInstance sbean = new ScopedInstance();
        Set<Bean<?>> beans = container.getBeanManager().getBeans(clazz,AnyLiteral.INSTANCE);
        if (beans.size() > 0)
        {
            sbean.bean = beans.iterator().next();
            sbean.creationalContext = container.getBeanManager().createCreationalContext(sbean.bean);
            sbean.instance = container.getBeanManager().getReference(sbean.bean,clazz,sbean.creationalContext);
            return sbean;
        }
        else
        {
            throw new Exception(String.format("Can't find class %s",clazz));
        }
    }
}
