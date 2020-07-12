/*
 * Copyright 2002-2010 the original author or authors.
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

package org.springframework.aop.config;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.w3c.dom.Element;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.ParserContext;

/**
 * Utility class for handling registration of auto-proxy creators used internally
 * by the '<code>aop</code>' namespace tags.
 *
 * <p>Only a single auto-proxy creator can be registered and multiple tags may wish
 * to register different concrete implementations. As such this class delegates to
 * {@link AopConfigUtils} which wraps a simple escalation protocol. Therefore classes
 * may request a particular auto-proxy creator and know that class, <i>or a subclass
 * thereof</i>, will eventually be resident in the application context.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.0
 * @see AopConfigUtils
 */
public abstract class AopNamespaceUtils {

	/**
	 * The <code>proxy-target-class</code> attribute as found on AOP-related XML tags.
	 *
	 * Spring AOP 部分使用JDK 动态代理或者CGLIB 来为目标对象创建代理。（建议尽量使用jdk代理）。
	 * 如果被代理的目标对象实现了至少一个接口，则会使用JDK动态代理。所有该目标类型实现的接口都经被代理类。
	 * 若该目标对象没有实现任何接口，则创建一个CGLIB代理。
	 * 如果你希望强制使用CGLIB代理，（例如希望代理目标对象的所有方法，而不只是实现自接口的方法）那也可以，
	 * 但是需要考虑一下两个问题：
	 * 1. 无法通知（advise）Final 方法，因为他们不能被覆盖。
	 * 2. 你需要将CGLIB二进制发行包放在classpath 下面。
	 *
	 * 与之相交，JDK本身就提供了动态代理，
	 * 强制使用CGLIB 代理需要将 <aop:config> proxy-target-class 属性设置为true
	 * <aop:config proxy-target-class="true">.....</aop:config>
	 *
	 * 当需要使用CGLIB代理和@AspectJ 自动代理支持，
	 * 可以按照以下方式设置 <aop:aspect-autoproxy> 的 proxy-target-class属性：
	 * <aop:aspect-autoproxy proxy-target-class="true"/>
	 *
	 * 而实际使用过程中才会发现细节问题的差别，The devil in the detail
	 * 1. JDK动态代理：其代理对象必须是某个接口的实现，
	 * 它是通过在运行期间，创建一个接口的实现类，来完成对目标对象的代理
	 * 2. CGLIB代理：实现原理类似于JDK 动态代理，只是它在运行期间生成代理对象是针对目标类扩展的子类
	 * CGLIB是高效的代码生成包，底层依靠ASM（开源的Java字节码编辑类库）操作自己么实现的，性能比JDK强。
	 * 3.expose-proxy：有时候目标对象内部的自我调用将无法实施切面中的增强。实例如下：
	 *
	 * public interface AService {
	 *     public void a();
	 *     public void b();
	 * }
	 *
	 * @Service
	 * public class AServiceImpl implements AService {
	 *     @Transactional(propagation = Propagation.REQUIRED)
	 *     public void a() {
	 *         this.b();
	 *     }
	 *     @Transactional(propagation = Propagation.REQUIRED)
	 * 	   public void a() {
	 * 	   }
	 * }
	 *
	 * 此处的this指向目标对象，因此调用this.b()将不会执行b事务切面，及不会执行事务增强，
	 * 因此b 方法的事务定义将不会实施，为了解决这个问题，我们可以这样做：
	 * <aop:aspect-autoproxy expose-proxy="true"/>
	 * 然后将以上代码的this.b() 修改为 ((AService)AopContext.currentProxy()).b()即可，
	 * 通过以上的修改，便可以完成对 a和b 方法的同时增强
	 */
	public static final String PROXY_TARGET_CLASS_ATTRIBUTE = "proxy-target-class";

	/**
	 * The <code>expose-proxy</code> attribute as found on AOP-related XML tags.
	 */
	private static final String EXPOSE_PROXY_ATTRIBUTE = "expose-proxy";


	public static void registerAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {

		BeanDefinition beanDefinition = AopConfigUtils.registerAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	public static void registerAspectJAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {

		BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	/**
	 * 注册{@link AnnotationAwareAspectJAutoProxyCreator}
	 */
	public static void registerAspectJAnnotationAutoProxyCreatorIfNecessary(
			ParserContext parserContext, Element sourceElement) {

		/**
		 * 1.注册或升级 AutoProxyCreator，
		 * 定义 beanName 为 {@link AopConfigUtils#AUTO_PROXY_CREATOR_BEAN_NAME}
		 * 对于AOP的实现，基本上都是靠{@link AnnotationAwareAspectJAutoProxyCreator} 去完成，
		 * 它可以根据@Point 注解定义的切点，来自动代理相匹配的bean，
		 * 但是为了配置简便，Spring使用了自定义配置来帮助我们自动注册{@link AnnotationAwareAspectJAutoProxyCreator}，
		 * 其注册过程，就在这里实现
		 */
		BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), parserContext.extractSource(sourceElement));

		// 2.对于 proxy-target-class 以及 expose-proxy 属性的处理
		useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);

		/**
		 * 3.注册组件并通知，便于监听器做进一步处理，
		 * 其中 beanDefinition 的 className 为 {@link AnnotationAwareAspectJAutoProxyCreator}
		 */
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	/**
	 * @deprecated since Spring 2.5, in favor of
	 * {@link #registerAutoProxyCreatorIfNecessary(ParserContext, Element)} and
	 * {@link AopConfigUtils#registerAutoProxyCreatorIfNecessary(BeanDefinitionRegistry, Object)}
	 */
	@Deprecated
	public static void registerAutoProxyCreatorIfNecessary(ParserContext parserContext, Object source) {
		BeanDefinition beanDefinition = AopConfigUtils.registerAutoProxyCreatorIfNecessary(
				parserContext.getRegistry(), source);
		registerComponentIfNecessary(beanDefinition, parserContext);
	}

	/**
	 * @deprecated since Spring 2.5, in favor of
	 * {@link AopConfigUtils#forceAutoProxyCreatorToUseClassProxying(BeanDefinitionRegistry)}
	 */
	@Deprecated
	public static void forceAutoProxyCreatorToUseClassProxying(BeanDefinitionRegistry registry) {
		AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
	}

	/**
	 * 实现了对 proxy-target-class 和 expose-proxy 属性的处理
	 */
	private static void useClassProxyingIfNecessary(BeanDefinitionRegistry registry, Element sourceElement) {
		if (sourceElement != null) {
			//对于proxy-target-class 属性的处理
			boolean proxyTargetClass = Boolean.valueOf(sourceElement.getAttribute(PROXY_TARGET_CLASS_ATTRIBUTE));
			if (proxyTargetClass) {
				AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
			}

			//对于expose-proxy 属性的处理
			boolean exposeProxy = Boolean.valueOf(sourceElement.getAttribute(EXPOSE_PROXY_ATTRIBUTE));
			if (exposeProxy) {
				AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
			}
		}
	}

	private static void registerComponentIfNecessary(BeanDefinition beanDefinition, ParserContext parserContext) {
		if (beanDefinition != null) {
			BeanComponentDefinition componentDefinition =
					new BeanComponentDefinition(beanDefinition, AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME);
			parserContext.registerComponent(componentDefinition);
		}
	}

}
