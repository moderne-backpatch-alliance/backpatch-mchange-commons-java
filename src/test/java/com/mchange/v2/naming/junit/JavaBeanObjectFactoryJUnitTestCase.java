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

import java.io.InvalidClassException;
import java.io.Serializable;
import java.util.*;
import javax.naming.*;
import junit.framework.TestCase;
import com.mchange.v2.naming.JavaBeanObjectFactory;
import com.mchange.v2.naming.JavaBeanReferenceMaker;
import com.mchange.v2.ser.SerializableUtils;

public final class JavaBeanObjectFactoryJUnitTestCase extends TestCase
{
    // JavaBeanReferenceMaker.REF_PROPS_KEY is package-private.
    static final String REF_PROPS_KEY = "com.mchange.v2.naming.JavaBeanReferenceMaker.REF_PROPS_KEY";

    static final String FACTORY = JavaBeanObjectFactory.class.getName();

    static int uninitializedBeanInitializations = 0;

    public static final class AcmeUninitializedBean
    {
        static { ++uninitializedBeanInitializations; }

        private String name;

        public String getName()            { return name; }
        public void setName( String name ) { this.name = name; }
    }

    public static final class AcmeHandwrittenReferenceable implements Referenceable
    {
        private String name;

        public String getName()            { return name; }
        public void setName( String name ) { this.name = name; }

        public Reference getReference() throws NamingException
        {
            Reference ref = new Reference( AcmeHandwrittenReferenceable.class.getName(), FACTORY, null );
            ref.add( new StringRefAddr( "name", name ) );
            return ref;
        }
    }

    public static final class AcmeBean implements Referenceable, Serializable
    {
        static final JavaBeanReferenceMaker referenceMaker = new JavaBeanReferenceMaker();

        private String     name;
        private int        size;
        private Properties properties;
        private Map        extensions;
        private Object     value;

        public String getName()                        { return name; }
        public void setName( String name )             { this.name = name; }
        public int getSize()                           { return size; }
        public void setSize( int size )                { this.size = size; }
        public Properties getProperties()              { return properties; }
        public void setProperties( Properties p )      { this.properties = p; }
        public Map getExtensions()                     { return extensions; }
        public void setExtensions( Map extensions )    { this.extensions = extensions; }
        public Object getValue()                       { return value; }
        public void setValue( Object value )           { this.value = value; }

        public Reference getReference() throws NamingException
        { return referenceMaker.createReference( this ); }
    }

    public static final class AcmeExplicitPropertiesBean implements Referenceable
    {
        static final JavaBeanReferenceMaker referenceMaker = new JavaBeanReferenceMaker();

        static
        {
            referenceMaker.addReferenceProperty( "name" );
        }

        private String name;

        public String getName()            { return name; }
        public void setName( String name ) { this.name = name; }

        public Reference getReference() throws NamingException
        { return referenceMaker.createReference( this ); }
    }

    private static Object dereference( Reference ref ) throws Exception
    { return new JavaBeanObjectFactory().getObjectInstance( ref, null, null, null ); }

    private static Reference replacingAddr( Reference ref, RefAddr replacement )
    {
        Reference out = new Reference( ref.getClassName(), ref.getFactoryClassName(), ref.getFactoryClassLocation() );
        for (Enumeration e = ref.getAll(); e.hasMoreElements(); )
        {
            RefAddr addr = (RefAddr) e.nextElement();
            out.add( addr.getType().equals( replacement.getType() ) ? replacement : addr );
        }
        return out;
    }

    private static AcmeBean acmeBean( String name, Object value )
    {
        AcmeBean bean = new AcmeBean();
        bean.setName( name );
        bean.setSize( 3 );
        Properties p = new Properties();
        p.setProperty( "acme.flag", "on" );
        bean.setProperties( p );
        bean.setValue( value );
        return bean;
    }

    public void testReferenceNamingAClassThatIsNotReferenceableIsRefusedWithoutInitializingIt() throws Exception
    {
        Reference ref = new Reference( AcmeUninitializedBean.class.getName(), FACTORY, null );
        ref.add( new StringRefAddr( "name", "acme" ) );
        try
        {
            Object o = dereference( ref );
            fail( "Expected NamingException for a class that is not Referenceable, got " + o );
        }
        catch ( NamingException expected )
        {}
        assertEquals( 0, uninitializedBeanInitializations );
    }

    public void testReferenceableClassWithoutAJavaBeanReferenceMakerIsRefused() throws Exception
    {
        AcmeHandwrittenReferenceable bean = new AcmeHandwrittenReferenceable();
        bean.setName( "acme" );
        try
        {
            Object o = dereference( bean.getReference() );
            fail( "Expected NamingException for a Referenceable class without a JavaBeanReferenceMaker, got " + o );
        }
        catch ( NamingException expected )
        {}
    }

    public void testBinaryRefAddrHoldingAClassOutsideTheAcceptedValueTypesIsRefused() throws Exception
    {
        Reference ref = acmeBean( "acme", new Date( 0L ) ).getReference();
        assertTrue( ref.get( "value" ) instanceof BinaryRefAddr );
        try
        {
            Object o = dereference( ref );
            fail( "Expected InvalidClassException for a java.util.Date property value, got " + o );
        }
        catch ( InvalidClassException expected )
        { assertEquals( Date.class.getName(), expected.classname ); }
    }

    public void testReferencePropertiesSetOfAClassOutsideTheAcceptedValueTypesIsRefused() throws Exception
    {
        AcmeExplicitPropertiesBean bean = new AcmeExplicitPropertiesBean();
        bean.setName( "acme" );
        TreeSet refProps = new TreeSet();
        refProps.add( "name" );
        Reference ref = replacingAddr( bean.getReference(), new BinaryRefAddr( REF_PROPS_KEY, SerializableUtils.toByteArray( refProps ) ) );
        try
        {
            Object o = dereference( ref );
            fail( "Expected InvalidClassException for a java.util.TreeSet of reference properties, got " + o );
        }
        catch ( InvalidClassException expected )
        { assertEquals( TreeSet.class.getName(), expected.classname ); }
    }

    public void testBeanWithValueAndNestedBeanPropertiesRoundTripsThroughItsReference() throws Exception
    {
        AcmeBean nested = acmeBean( "acme-nested", "a string value" );
        AcmeBean bean = acmeBean( "acme", nested );
        Map extensions = new HashMap();
        extensions.put( "acme.extension", Integer.valueOf( 42 ) );
        bean.setExtensions( extensions );

        AcmeBean out = (AcmeBean) dereference( bean.getReference() );

        assertEquals( "acme", out.getName() );
        assertEquals( 3, out.getSize() );
        assertEquals( "on", out.getProperties().getProperty( "acme.flag" ) );
        assertEquals( extensions, out.getExtensions() );
        AcmeBean outNested = (AcmeBean) out.getValue();
        assertNotSame( nested, outNested );
        assertEquals( "acme-nested", outNested.getName() );
        assertEquals( "on", outNested.getProperties().getProperty( "acme.flag" ) );
        assertEquals( "a string value", outNested.getValue() );
        assertNull( outNested.getExtensions() );
    }

    public void testBeanWithExplicitReferencePropertiesRoundTripsThroughItsReference() throws Exception
    {
        AcmeExplicitPropertiesBean bean = new AcmeExplicitPropertiesBean();
        bean.setName( "acme" );
        Reference ref = bean.getReference();
        assertTrue( ref.get( REF_PROPS_KEY ) instanceof BinaryRefAddr );

        AcmeExplicitPropertiesBean out = (AcmeExplicitPropertiesBean) dereference( ref );

        assertEquals( "acme", out.getName() );
    }
}
