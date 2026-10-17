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

package com.mchange.v2.naming;

import java.beans.*;
import java.io.*;
import java.util.*;
import javax.naming.*;
import com.mchange.v2.log.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.naming.spi.ObjectFactory;
import com.mchange.v2.beans.BeansUtils;
import com.mchange.v2.lang.Coerce;
import com.mchange.v2.ser.IndirectlySerialized;

public class JavaBeanObjectFactory implements ObjectFactory
{
    private final static MLogger logger = MLog.getLogger( JavaBeanObjectFactory.class );

    final static Object NULL_TOKEN = new Object();

    public Object getObjectInstance(Object refObj, Name name, Context nameCtx, Hashtable env)
	throws Exception
    {
	if (refObj instanceof Reference)
	    {
		Reference ref = (Reference) refObj;
		Map refAddrsMap = new HashMap();
		for (Enumeration e = ref.getAll(); e.hasMoreElements(); )
		    {
			RefAddr addr = (RefAddr) e.nextElement();
			refAddrsMap.put( addr.getType(), addr );
		    }
		Class beanClass = Class.forName( ref.getClassName(), false, JavaBeanObjectFactory.class.getClassLoader() );
		if (! isReferenceBeanClass( beanClass ))
		    throw new NamingException( this.getClass().getName() + " only restores Referenceable JavaBeans whose References a " +
					       JavaBeanReferenceMaker.class.getName() + " makes; refusing " + beanClass.getName() + "." );
		Set refProps = null;
		RefAddr refPropsRefAddr = (BinaryRefAddr) refAddrsMap.remove( JavaBeanReferenceMaker.REF_PROPS_KEY );
		if ( refPropsRefAddr != null )
		    refProps = (Set) deserializeRefAddrContent( beanClass, (byte[]) refPropsRefAddr.getContent() );
		Map propMap = createPropertyMap( beanClass, refAddrsMap );
		return findBean( beanClass, propMap, refProps );
	    }
	else
	    return null;
    }

    private Map createPropertyMap( Class beanClass, Map refAddrsMap ) throws Exception
    {
	BeanInfo bi = Introspector.getBeanInfo( beanClass );
	PropertyDescriptor[] pds = bi.getPropertyDescriptors();

	Map out = new HashMap();
	for (int i = 0, len = pds.length; i < len; ++i)
	    {
		PropertyDescriptor pd = pds[i];
		String propertyName = pd.getName();
		Class  propertyType = pd.getPropertyType();
		Object addr = refAddrsMap.remove( propertyName );
		if (addr != null)
		    {
			if ( addr instanceof StringRefAddr )
			    {
				String content = (String) ((StringRefAddr) addr).getContent();
				if ( Coerce.canCoerce( propertyType ) )
				    out.put( propertyName, Coerce.toObject( content, propertyType ) );
				else
				    {
					PropertyEditor pe = BeansUtils.findPropertyEditor( pd );
					pe.setAsText( content );
					out.put( propertyName, pe.getValue() );
				    }
			    }
			else if ( addr instanceof BinaryRefAddr )
			    {
				byte[] content = (byte[]) ((BinaryRefAddr) addr).getContent();
				if ( content.length == 0 )
				    out.put( propertyName, NULL_TOKEN ); //we use an empty array to mean null
				else
				    out.put( propertyName, deserializeRefAddrContent( beanClass, content ) ); //this will handle "indirectly serialized" objects.
			    }
			else
			    {
				if (logger.isLoggable( MLevel.WARNING ))
				    logger.warning(this.getClass().getName() + " -- unknown RefAddr subclass: " + addr.getClass().getName());
			    }
		    }
	    }
	for ( Iterator ii = refAddrsMap.keySet().iterator(); ii.hasNext(); )
	    {
		String type = (String) ii.next();
		if (logger.isLoggable( MLevel.WARNING ))
		    logger.warning(this.getClass().getName() + " -- RefAddr for unknown property: " + type);
	    }
	return out;
    }

    // Referenceable, with a JavaBeanReferenceMaker field in its hierarchy: the beans PropertyReferenceableExtension
    // generates, and hand-written ones following that pattern. Must not initialize the class.
    static boolean isReferenceBeanClass( Class c )
    {
	if ( c.isArray() || c.isPrimitive() || ! Referenceable.class.isAssignableFrom( c ) )
	    return false;
	try
	    {
		for (Class k = c; k != null; k = k.getSuperclass())
		    {
			Field[] fields = k.getDeclaredFields();
			for (int i = 0, len = fields.length; i < len; ++i)
			    if ( JavaBeanReferenceMaker.class.isAssignableFrom( fields[i].getType() ) )
				return true;
		    }
		return false;
	    }
	catch ( LinkageError e )
	    { return false; }
	catch ( SecurityException e )
	    { return false; }
    }

    private static Object deserializeRefAddrContent( Class beanClass, byte[] content ) throws IOException, ClassNotFoundException
    {
	ObjectInputStream in = new RefAddrContentInputStream( beanClass, content );
	try
	    {
		Object out = in.readObject();
		if (out instanceof IndirectlySerialized)
		    out = ((IndirectlySerialized) out).getObject();
		return out;
	    }
	finally
	    { in.close(); }
    }

    private final static Set ACCEPTABLE_VALUE_CLASS_NAMES = new HashSet( Arrays.asList( new String[] {
	"java.lang.String", "java.lang.Number", "java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short",
	"java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
	"java.util.Hashtable", "java.util.Properties", "java.util.HashMap", "java.util.LinkedHashMap",
	"java.util.HashSet", "java.util.LinkedHashSet", "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector",
	"java.util.Arrays$ArrayList", "java.util.CollSer",
	"java.util.Collections$UnmodifiableCollection", "java.util.Collections$UnmodifiableSet", "java.util.Collections$UnmodifiableList",
	"java.util.Collections$UnmodifiableRandomAccessList", "java.util.Collections$UnmodifiableMap",
	"java.util.Collections$SynchronizedCollection", "java.util.Collections$SynchronizedSet", "java.util.Collections$SynchronizedList",
	"java.util.Collections$SynchronizedRandomAccessList", "java.util.Collections$SynchronizedMap",
	"java.util.Collections$EmptySet", "java.util.Collections$EmptyList", "java.util.Collections$EmptyMap",
	"java.util.Collections$SingletonSet", "java.util.Collections$SingletonList", "java.util.Collections$SingletonMap",
	"javax.naming.Reference", "javax.naming.RefAddr", "javax.naming.StringRefAddr", "javax.naming.BinaryRefAddr",
	"javax.naming.CompositeName", "javax.naming.CompoundName", "javax.naming.ldap.LdapName",
	"com.mchange.v2.naming.ReferenceIndirector$ReferenceSerialized" // dereferenced only through ReferenceableUtils' guards
    } ) );

    private final static class RefAddrContentInputStream extends ObjectInputStream
    {
	final List anchors  = new ArrayList();
	final Set  admitted = new HashSet();

	RefAddrContentInputStream( Class beanClass, byte[] content ) throws IOException
	{
	    super( new ByteArrayInputStream( content ) );
	    addAnchors( beanClass );
	}

	private void addAnchors( Class c )
	{
	    for (Class k = c; k != null; k = k.getSuperclass())
		{
		    String name = k.getName();
		    if ( Referenceable.class.isAssignableFrom( k ) && ! name.startsWith( "java." ) && ! name.startsWith( "javax." ) )
			anchors.add( k );
		}
	}

	private static String packageOf( Class c )
	{
	    String name = c.getName();
	    int dot = name.lastIndexOf( '.' );
	    return ( dot < 0 ? "" : name.substring( 0, dot ) );
	}

	private boolean inAnchorPackage( Class c )
	{
	    String pkg = packageOf( c );
	    for (int i = 0, len = anchors.size(); i < len; ++i)
		{
		    Class anchor = (Class) anchors.get( i );
		    if ( anchor.getClassLoader() == c.getClassLoader() && packageOf( anchor ).equals( pkg ) )
			return true;
		}
	    return false;
	}

	private boolean acceptable( Class c )
	{
	    if ( admitted.contains( c ) )
		return true;
	    if ( c.isArray() )
		{
		    Class component = c.getComponentType();
		    while ( component.isArray() )
			component = component.getComponentType();
		    return component.isPrimitive() || component == Object.class || acceptable( component );
		}
	    if ( ACCEPTABLE_VALUE_CLASS_NAMES.contains( c.getName() ) )
		return true;
	    if ( inAnchorPackage( c ) )
		return true;
	    if ( isReferenceBeanClass( c ) )
		{
		    addAnchors( c );
		    return true;
		}
	    return false;
	}

	protected Class resolveClass( ObjectStreamClass desc ) throws IOException, ClassNotFoundException
	{
	    Class c = super.resolveClass( desc );
	    if (! acceptable( c ))
		throw new InvalidClassException( c.getName(), "not acceptable in the BinaryRefAddr content of a JavaBean Reference" );
	    for (Class k = c; k != null; k = k.getSuperclass())
		admitted.add( k );
	    return c;
	}

	protected Class resolveProxyClass( String[] interfaces ) throws IOException, ClassNotFoundException
	{ throw new InvalidClassException( "proxy", "not acceptable in the BinaryRefAddr content of a JavaBean Reference" ); }
    }

    protected Object createBlankInstance(Class beanClass) throws Exception
    { return beanClass.newInstance(); }

    protected Object findBean(Class beanClass, Map propertyMap, Set refProps ) throws Exception
    {
	Object bean = createBlankInstance( beanClass );
	BeanInfo bi = Introspector.getBeanInfo( bean.getClass() );
	PropertyDescriptor[] pds = bi.getPropertyDescriptors();
	
	for (int i = 0, len = pds.length; i < len; ++i)
	    {
		PropertyDescriptor pd = pds[i];
		String propertyName = pd.getName();
		Object value = propertyMap.get( propertyName );
		Method setter = pd.getWriteMethod();
		if (value != null)
		    {
			if (setter != null)
			    setter.invoke( bean, new Object[] { (value == NULL_TOKEN ? null : value) } );
			else
			    {
				//System.err.println(this.getClass().getName() + ": Could not restore read-only property '" + propertyName + "'.");
				if (logger.isLoggable( MLevel.WARNING ))
				    logger.warning(this.getClass().getName() + ": Could not restore read-only property '" + propertyName + "'.");
			    }
		    }
		else
		    {
			if (setter != null)
			    {
				if (refProps == null || refProps.contains( propertyName ))
				    {
					//System.err.println(this.getClass().getName() +
					//": WARNING -- Expected writable property '" + propertyName + "' left at default value");
					if (logger.isLoggable( MLevel.WARNING ))
					    logger.warning(this.getClass().getName() + " -- Expected writable property ''" + propertyName + "'' left at default value");
				    }
			    }
		    }
	    }
	
	return bean;
    }
}
