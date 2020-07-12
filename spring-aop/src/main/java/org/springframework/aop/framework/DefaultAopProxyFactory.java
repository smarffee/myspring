/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.framework;

import java.io.Serializable;

import org.springframework.aop.SpringProxy;

/**
 * Default {@link AopProxyFactory} implementation,
 * creating either a CGLIB proxy or a JDK dynamic proxy.
 *
 * <p>Creates a CGLIB proxy if one the following is true
 * for a given {@link AdvisedSupport} instance:
 * <ul>
 * <li>the "optimize" flag is set
 * <li>the "proxyTargetClass" flag is set
 * <li>no proxy interfaces have been specified
 * </ul>
 *
 * <p>Note that the CGLIB library classes have to be present on
 * the class path if an actual CGLIB proxy needs to be created.
 *
 * <p>In general, specify "proxyTargetClass" to enforce a CGLIB proxy,
 * or specify one or more interfaces to use a JDK dynamic proxy.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 12.03.2004
 * @see AdvisedSupport#setOptimize
 * @see AdvisedSupport#setProxyTargetClass
 * @see AdvisedSupport#setInterfaces
 */
public class DefaultAopProxyFactory implements AopProxyFactory, Serializable {

	//AOP创建代理
	public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
		/**
		 * 3个方面影响着Spring在创建代理时，使用JDK 还是 CGLIB
		 *
		 * optimize: 用来控制通过CGLIB创建的代理，是否使用激进的优化策略。
		 * 除非完全了解AOP代理如果处理优化，否则不推荐用户使用这个设置，
		 * 目前这个属性仅用于CGLIB 代理，对于JDK 动态代理（缺省代理）无效。
		 *
		 * proxyTargetClass：这个属性为true时，目标类本身被代理而不是目标类的接口。
		 * 如果这个属性值被设为true，CGLIB代理将被创建，设置方式为：<aop:aspectj-autoproxy proxy-target-class="true"/>
		 *
		 * hasNoUserSuppliedProxyInterfaces：是否存在代理接口
		 *
		 * 下面是 JDK 与 cglib 方式的总结：
		 * 1. 如果目标对象实现了接口，默认情况下会采用jdk 的动态代理实现aop
		 * 2. 如果目标对象实现了接口，可以强制使用cglib 实现 aop
		 * 3. 如果目标对象没有实现接口，必须采用 cglib，Spring会自动在 JDK 与 cglib 之间动态切换
		 *
		 * 如果强制使用 cglib 实现 aop ?
		 * 1. 添加 cglib 库，Spring_HOME/cglib/*.jar
		 * 2. 在Spring配置文件中加入 <aop:aspectj-autoproxy proxy-target-class="true"/>
		 *
		 * jdk 动态代理和 cglib 字节码生成的区别？
		 * 1. jdk动态代理只能对实现了接口的类生成代理，而不能针对类。
		 * 2. cglib是类实现代理，主要是对指定的类生成一个子类，覆盖其中的一个方法，因为是继承，所以该类和方法最好不要声明成final
		 */
		if (config.isOptimize() || config.isProxyTargetClass() || hasNoUserSuppliedProxyInterfaces(config)) {
			Class targetClass = config.getTargetClass();
			if (targetClass == null) {
				throw new AopConfigException("TargetSource cannot determine target class: " +
						"Either an interface or a target is required for proxy creation.");
			}
			if (targetClass.isInterface()) {
				return new JdkDynamicAopProxy(config);
			}
			return CglibProxyFactory.createCglibProxy(config);
		}
		else {
			return new JdkDynamicAopProxy(config);
		}
	}

	/**
	 * Determine whether the supplied {@link AdvisedSupport} has only the
	 * {@link org.springframework.aop.SpringProxy} interface specified
	 * (or no proxy interfaces specified at all).
	 */
	private boolean hasNoUserSuppliedProxyInterfaces(AdvisedSupport config) {
		Class[] interfaces = config.getProxiedInterfaces();
		return (interfaces.length == 0 || (interfaces.length == 1 && SpringProxy.class.equals(interfaces[0])));
	}


	/**
	 * Inner factory class used to just introduce a CGLIB dependency
	 * when actually creating a CGLIB proxy.
	 */
	private static class CglibProxyFactory {

		public static AopProxy createCglibProxy(AdvisedSupport advisedSupport) {
			return new CglibAopProxy(advisedSupport);
		}
	}

}
