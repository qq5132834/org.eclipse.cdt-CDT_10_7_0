/*******************************************************************************
 * Copyright (c) 2005, 2009 Intel Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Intel Corporation - Initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.core.envvar;

/**
 * this interface represents the given environment variable
 * @since 3.0
 * @noextend This interface is not intended to be extended by clients.
 * @noimplement This interface is not intended to be implemented by clients.
 */
public interface IEnvironmentVariable {
	public static final int ENVVAR_REPLACE = 1;
	public static final int ENVVAR_REMOVE = 2;
	public static final int ENVVAR_PREPEND = 3;
	public static final int ENVVAR_APPEND = 4;

	/**
	 *
	 * @return the variable name
	 */
	public String getName();

	/**
	 *
	 * @return the variable value
	 */
	public String getValue();

	/**
	 * @return one of the IBuildEnvironmentVariable.ENVVAR_* operation types
	 */
	public int getOperation();

	/**
	 * @return if the variable can hold the list of values this method returns the String representing
	 * the delimiter that is used to separate values. This information is used for the following:
	 *
	 * 1. in append and prepend operations:
	 * If the variable already exists and contains some value the new
	 * value will be calculated in the following way:
	 * For the "prepend" operation:
	 * 	&lt;New value&gt; = &lt;the value from the getValue() method&gt;&lt;delimiter&gt;&lt;Old value&gt;
	 * For the "append" operation:
	 * 	&lt;New value&gt; = &lt;Old value&gt;&lt;delimiter&gt;&lt;the value from the getValue() method&gt;
	 *
	 * The Environment Variable Provider will also remove the duplicates of "sub-values"
	 * in the resulting value.
	 * For example:
	 * If the current value is "string1:string2:string3", the getDelimiter() method returns ":"
	 * and getValue() method returns "string4:string2" the new value will contain:
	 * For the "prepend" operation: "string4:string2:string1:string3"
	 * For the "append" operation: "string1:string3:string4:string2"
	 *
	 * 2. Since the environment variables are also treated as build macros the delimiter is also used
	 * by the BuildMacroProvider to determine the type of the macro used to represent the
	 * given environment variable. If the variable has the delimiter it is treated as the Text-List macro
	 * otherwise it is treated as the Text macro. (See Build Macro design for more details)
	 *
	 * To specify that no delimiter should be used, the getDelimiter() method should
	 * return null or an empty string
	 */
	public String getDelimiter();
}
