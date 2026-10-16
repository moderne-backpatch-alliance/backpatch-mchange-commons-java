/*
 * Distributed as part of mchange-commons-java 0.2.11
 *
 * Copyright (C) 2015 Machinery For Change, Inc.
 *
 * Author: Steve Waldman <swaldman@mchange.com>
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of EITHER:
 *
 *     1) The GNU Lesser General Public License (LGPL), version 2.1, as 
 *        published by the Free Software Foundation
 *
 * OR
 *
 *     2) The Eclipse Public License (EPL), version 1.0
 *
 * You may choose which license to accept if you wish to redistribute
 * or modify this work. You may offer derivatives of this work
 * under the license you have chosen, or you may provide the same
 * choice of license which you have been offered here.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received copies of both LGPL v2.1 and EPL v1.0
 * along with this software; see the files LICENSE-EPL and LICENSE-LGPL.
 * If not, the text of these licenses are currently available at
 *
 * LGPL v2.1: http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 *  EPL v1.0: http://www.eclipse.org/org/documents/epl-v10.php 
 * 
 */

package com.mchange.v2.naming.junit;

import java.io.IOException;
import java.util.Hashtable;
import javax.naming.*;
import javax.naming.spi.ObjectFactory;
import junit.framework.TestCase;
import com.mchange.v2.naming.ReferenceIndirector;
import com.mchange.v2.naming.ReferenceableUtils;
import com.mchange.v2.ser.IndirectlySerialized;

// Uses only API present before CVE-2026-27727 was fixed, so it also compiles and runs against the unfixed classes.
public final class ReferenceDereferenceDefaultsJUnitTestCase extends TestCase
{
    // String literal, not SecurityConfigKey.OBJECT_FACTORY_WHITELIST, which the unfixed tree lacks.
    static final String OBJECT_FACTORY_WHITELIST_KEY = "com.mchange.v2.naming.objectFactoryWhitelist";

    public static final class AcmeObjectFactory implements ObjectFactory
    {
        public Object getObjectInstance( Object obj, Name name, Context nameCtx, Hashtable environment )
            throws Exception
        { return "ACME"; }
    }

    public static final class AcmeReferenceable implements Referenceable
    {
        public Reference getReference() throws NamingException
        { return new Reference( AcmeReferenceable.class.getName(), ACME_FACTORY, null ); }
    }

    static final String ACME_FACTORY = AcmeObjectFactory.class.getName();

    private String savedWhitelist;

    protected void setUp()
    { savedWhitelist = System.getProperty( OBJECT_FACTORY_WHITELIST_KEY ); }

    protected void tearDown()
    {
        if ( savedWhitelist == null )
            System.clearProperty( OBJECT_FACTORY_WHITELIST_KEY );
        else
            System.setProperty( OBJECT_FACTORY_WHITELIST_KEY, savedWhitelist );
    }

    public void testFactoryClassLocationIsNotUsedToLoadTheFactoryByDefault() throws NamingException
    {
        System.setProperty( OBJECT_FACTORY_WHITELIST_KEY, ACME_FACTORY );
        // a location no class loader could be built from: it can only be harmless if it is ignored
        Reference ref = new Reference( String.class.getName(), ACME_FACTORY, "acme-unknown-scheme:/factories/" );
        assertEquals( "ACME", ReferenceableUtils.referenceToObject( ref, null, null, null ) );
    }

    public void testObjectFactoryOutsideTheWhitelistIsRefused()
    {
        System.clearProperty( OBJECT_FACTORY_WHITELIST_KEY );
        Reference ref = new Reference( String.class.getName(), ACME_FACTORY, null );
        try
        {
            Object o = ReferenceableUtils.referenceToObject( ref, null, null, null );
            fail( "Expected NamingException without an object factory whitelist, got " + o );
        }
        catch ( NamingException expected )
        {}
    }

    public void testIndirectlySerializedReferenceCarryingAnEnvironmentIsRefusedByDefault() throws Exception
    {
        System.setProperty( OBJECT_FACTORY_WHITELIST_KEY, ACME_FACTORY );
        Hashtable env = new Hashtable();
        env.put( "acme.example.setting", "1" );
        ReferenceIndirector ri = new ReferenceIndirector();
        ri.setEnvironmentProperties( env );
        IndirectlySerialized is = ri.indirectForm( new AcmeReferenceable() );
        try
        {
            Object o = is.getObject();
            fail( "Expected IOException for a deserialized InitialContext environment, got " + o );
        }
        catch ( IOException expected )
        {}
    }

    public void testIndirectlySerializedReferenceWithoutEnvironmentStillResolves() throws Exception
    {
        System.setProperty( OBJECT_FACTORY_WHITELIST_KEY, ACME_FACTORY );
        IndirectlySerialized is = new ReferenceIndirector().indirectForm( new AcmeReferenceable() );
        assertEquals( "ACME", is.getObject() );
    }
}
