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

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import javax.naming.CompositeName;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;
import junit.framework.TestCase;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.c3p0.DriverManagerDataSource;
import com.mchange.v2.c3p0.JndiRefConnectionPoolDataSource;
import com.mchange.v2.c3p0.WrapperConnectionPoolDataSource;
import com.mchange.v2.naming.JavaBeanObjectFactory;

// c3p0 0.9.5.5 (test scope) References dereferenced by C3P0JavaBeanObjectFactory in a separate class loader, as a
// directory lookup from another JVM would: c3p0's registry would otherwise hand back the original instances.
public final class C3P0ReferenceCompatibilityJUnitTestCase extends TestCase
{
    static final String C3P0_FACTORY = "com.mchange.v2.c3p0.impl.C3P0JavaBeanObjectFactory";

    private URLClassLoader isolated;

    protected void setUp()
    {
        URL mchange = JavaBeanObjectFactory.class.getProtectionDomain().getCodeSource().getLocation();
        URL c3p0    = ComboPooledDataSource.class.getProtectionDomain().getCodeSource().getLocation();
        isolated = new URLClassLoader( new URL[] { mchange, c3p0 }, ClassLoader.getSystemClassLoader().getParent() );
    }

    private Object dereference( Reference ref ) throws Exception
    {
        assertEquals( C3P0_FACTORY, ref.getFactoryClassName() );
        ObjectFactory factory = (ObjectFactory) Class.forName( C3P0_FACTORY, true, isolated ).newInstance();
        Object out = factory.getObjectInstance( ref, null, null, null );
        assertNotNull( out );
        assertSame( isolated, out.getClass().getClassLoader() );
        return out;
    }

    private static Object get( Object bean, String getter ) throws Exception
    {
        Method m = bean.getClass().getMethod( getter, new Class[0] );
        return m.invoke( bean, new Object[0] );
    }

    private static Properties acmeProperties()
    {
        Properties p = new Properties();
        p.setProperty( "acme.flag", "on" );
        return p;
    }

    public void testComboPooledDataSourceRoundTripsThroughItsReference() throws Exception
    {
        ComboPooledDataSource cpds = new ComboPooledDataSource();
        Reference ref;
        try
        {
            cpds.setDriverClass( "org.acme.Driver" );
            cpds.setJdbcUrl( "jdbc:acme://db.example.com/test" );
            cpds.setProperties( acmeProperties() );
            cpds.setUser( "acme" );
            cpds.setMaxPoolSize( 7 );
            cpds.setDataSourceName( "acme-pool" );
            Map extensions = new HashMap();
            extensions.put( "acme.extension", "1" );
            cpds.setExtensions( extensions );
            ref = cpds.getReference();
        }
        finally
        { cpds.close(); }

        Object out = dereference( ref );
        try
        {
            assertEquals( ComboPooledDataSource.class.getName(), out.getClass().getName() );
            assertEquals( "org.acme.Driver", get( out, "getDriverClass" ) );
            assertEquals( "jdbc:acme://db.example.com/test", get( out, "getJdbcUrl" ) );
            assertEquals( "acme", get( out, "getUser" ) );
            assertEquals( "on", ((Properties) get( out, "getProperties" )).getProperty( "acme.flag" ) );
            assertEquals( Integer.valueOf( 7 ), get( out, "getMaxPoolSize" ) );
            assertEquals( "acme-pool", get( out, "getDataSourceName" ) );
            assertEquals( "1", ((Map) get( out, "getExtensions" )).get( "acme.extension" ) );
            Object wcpds = get( out, "getConnectionPoolDataSource" );
            assertEquals( WrapperConnectionPoolDataSource.class.getName(), wcpds.getClass().getName() );
            assertEquals( DriverManagerDataSource.class.getName(), get( wcpds, "getNestedDataSource" ).getClass().getName() );
        }
        finally
        { get( out, "close" ); }
    }

    public void testWrapperConnectionPoolDataSourceRoundTripsThroughItsReference() throws Exception
    {
        DriverManagerDataSource dmds = new DriverManagerDataSource();
        dmds.setDriverClass( "org.acme.Driver" );
        dmds.setJdbcUrl( "jdbc:acme://db.example.com/test" );
        dmds.setProperties( acmeProperties() );
        dmds.setUser( "acme" );
        WrapperConnectionPoolDataSource wcpds = new WrapperConnectionPoolDataSource();
        wcpds.setNestedDataSource( dmds );
        wcpds.setMaxStatements( 11 );
        wcpds.setPreferredTestQuery( "SELECT 1" );

        Object out = dereference( wcpds.getReference() );

        assertEquals( WrapperConnectionPoolDataSource.class.getName(), out.getClass().getName() );
        assertEquals( Integer.valueOf( 11 ), get( out, "getMaxStatements" ) );
        assertEquals( "SELECT 1", get( out, "getPreferredTestQuery" ) );
        Object nested = get( out, "getNestedDataSource" );
        assertEquals( DriverManagerDataSource.class.getName(), nested.getClass().getName() );
        assertSame( isolated, nested.getClass().getClassLoader() );
        assertEquals( "jdbc:acme://db.example.com/test", get( nested, "getJdbcUrl" ) );
        assertEquals( "acme", get( nested, "getUser" ) );
        assertEquals( "on", ((Properties) get( nested, "getProperties" )).getProperty( "acme.flag" ) );
    }

    public void testJndiRefConnectionPoolDataSourceRoundTripsThroughItsReference() throws Exception
    {
        JndiRefConnectionPoolDataSource jrcpds = new JndiRefConnectionPoolDataSource();
        jrcpds.setJndiName( new CompositeName( "acme/pool" ) );
        Hashtable env = new Hashtable();
        env.put( "acme.setting", "1" );
        jrcpds.setJndiEnv( env );
        jrcpds.setMaxPoolSize( 5 );

        Object out = dereference( jrcpds.getReference() );

        assertEquals( JndiRefConnectionPoolDataSource.class.getName(), out.getClass().getName() );
        assertEquals( new CompositeName( "acme/pool" ), get( out, "getJndiName" ) );
        assertEquals( env, get( out, "getJndiEnv" ) );
        assertEquals( Integer.valueOf( 5 ), get( out, "getMaxPoolSize" ) );
    }
}
