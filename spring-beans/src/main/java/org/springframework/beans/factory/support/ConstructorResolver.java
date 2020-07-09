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

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Helper class for resolving constructors and factory methods.
 * Performs constructor resolution through argument matching.
 *
 * <p>Operates on an {@link AbstractBeanFactory} and an {@link InstantiationStrategy}.
 * Used by {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final String CONSTRUCTOR_PROPERTIES_CLASS_NAME = "java.beans.ConstructorProperties";

	private static final boolean constructorPropertiesAnnotationAvailable =
			ClassUtils.isPresent(CONSTRUCTOR_PROPERTIES_CLASS_NAME, ConstructorResolver.class.getClassLoader());

	private final AbstractAutowireCapableBeanFactory beanFactory;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or <code>null</code> if none)
	 * @param explicitArgs 明确的参数 argument values passed in programmatically via the getBean method,
	 * or <code>null</code> if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 *
	 * 有参构造器实例化bean
	 * 带有参数的实例化过程相当复杂，因为存在不确定性，所以在判断对应参数上做了大量的工作
	 * 主要精力都放在构造器与参数的匹配上
	 */
	public BeanWrapper autowireConstructor(
			final String beanName, final RootBeanDefinition mbd, Constructor[] chosenCtors, final Object[] explicitArgs) {

		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Constructor constructorToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		//explicitArgs 通过getBean 方法传入
		//如果getBean 方法调用的时候指定方法参数那么直接使用
		/**
		 * 1. 构造参数的确定
		 */
		if (explicitArgs != null) {
			/**
			 * 1.1 根据explicitArgs 参数判断
			 */
			argsToUse = explicitArgs;
		}
		else {
			//如果getBean 方法没有指定参数，则尝试从配置文件中解析
			Object[] argsToResolve = null;

			/**
			 * 1.2 尝试从缓存中获取
			 */
			synchronized (mbd.constructorArgumentLock) {
				constructorToUse = (Constructor) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					// 从缓存中取最终解析处理好的参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}

			//如果缓存中存在
			if (argsToResolve != null) {
				//解析参数类型，如给定方法的构造函数 A(int, int) 则通过此方法后，就会把配置中的 ("1", "1") 转换为 (1, 1)
				//缓存中的值可能是原始值也可能是最终值
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		/**
		 * 1.3 没有被缓存，则从配置文件中获取
		 * 不能通过传入的explicitArgs中获取，在缓存中也没有找到，那么只能开始新一轮的分析
		 */
		if (constructorToUse == null) {
			// Need to resolve the constructor.
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				/**
				 * 1.3.1 从获取配置文件中配置的构造器信息开始
				 */
				//提取配置文件中配置的构造器参数信息
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();

				//用于承载解析后的构造函数参数的值
				resolvedValues = new ConstructorArgumentValues();

				//能解析到的参数个数
				/**
				 * 1.3.2 有了配置中的信息，便可以获取对应的参数值信息了，获取参数值的信息包括直接指定值，
				 * 如：直接指定构造函数中某个值为原始类型String 类型，或者一个对其他bean 的引用，
				 * 而这一处理委托给 resolveConstructorArguments 方法，
				 * 并返回能解析到的参数的个数
				 */
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			/**
			 * 2. 构造函数的确定
			 *
			 * 经过第一步的分析，已经确定的了构造器的参数，
			 * 接下来的任务就是，根据构造器的参数在所有的构造器中锁定对应的构造器，
			 * 而匹配的方法就是根据参数个数匹配，所以要先排序，然后遍历。
			 * 配置文件中，可以通过参数位置索引去指定参数值，
			 * 也可以通过参数名称 <constructor-arg name="aa">，这种情况就需要首先确定构造器的参数名称
			 * 获取参数名称可以有两种方式：
			 * 1. 通过注解的方式直接获取
			 * 2. 使用spring中提供的工具类ParameterNameDiscoverer 来获取。
			 * 构造器、参数名称、参数类型、参数值都确定了，就可以锁定构造器以及对应转换的参数类型
			 */
			// Take specified constructors, if any.
			Constructor[] candidates = chosenCtors;
			if (candidates == null) {
				//如果没有传入构造器集合，则通过反射区获取类的构造器
				Class beanClass = mbd.getBeanClass();
				try {
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
									"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			/**
			 * 2.1 对构造器进行排序
			 */
			//排序给定的构造器，public构造器优先参数数量降序、非public构造器参数数量降序
			AutowireUtils.sortConstructors(candidates);

			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor> ambiguousConstructors = null; //不确定的构造器
			List<Exception> causes = null;

			/**
			 * 2.2 对构造器进行遍历
			 */
			for (int i = 0; i < candidates.length; i++) {
				Constructor<?> candidate = candidates[i];
				Class[] paramTypes = candidate.getParameterTypes();

				if (constructorToUse != null && argsToUse.length > paramTypes.length) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					// 如果已经找到选用的构造器或者
					// 需要的参数个数大于当前的构造器参数个数则终止，因为已经按照参数个数降序排序
					break;
				}

				//参数个数不相等，当前构造器的参数个数比最小需要的参数数量少，则跳过，取下一个构造器
				if (paramTypes.length < minNrOfArgs) {
					continue;
				}

				ArgumentsHolder argsHolder;
				/**
				 * 从配置文件中读取到的配置参数不为空，
				 * 配置文件中可能根据构造器的参数索引配置，
				 * 也可能根据构造器的参数名称配置
				 */
				if (resolvedValues != null) {
					//有参数则根据值构造对应参数类型的参数
					try {
						String[] paramNames = null;
						if (constructorPropertiesAnnotationAvailable) {
							//注释上获取参数名
							paramNames = ConstructorPropertiesChecker.evaluateAnnotation(candidate, paramTypes.length);
						}

						if (paramNames == null) {
							//获取参数名称探索器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								//获取指定构造器的参数名称
								paramNames = pnd.getParameterNames(candidate);
							}
						}

						//根据名称和数据类型创建参数持有者
						/**
						 * 2.3 根据确定的构造函数转换对应的参数类型
						 * 主要用Spring提供的类型转换器或者用户自定义的类型转换器
						 */
						argsHolder = createArgumentArray(
								beanName, mbd, resolvedValues, bw, paramTypes, paramNames, candidate, autowiring);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (this.beanFactory.logger.isTraceEnabled()) {
							this.beanFactory.logger.trace(
									"Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						if (i == candidates.length - 1 && constructorToUse == null) {
							if (causes != null) {
								for (Exception cause : causes) {
									this.beanFactory.onSuppressedException(cause);
								}
							}
							throw ex;
						}
						else {
							// Swallow and try next constructor.
							if (causes == null) {
								causes = new LinkedList<Exception>();
							}
							causes.add(ex);
							continue;
						}
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					// 当前构造器的参数数量和传入的参数数量不一致，则略过，取下一个构造器
					if (paramTypes.length != explicitArgs.length) {
						continue;
					}
					//构造函数没有参数的情况
					/**
					 * 2.3 根据确定的构造函数转换对应的参数类型
					 * 主要用Spring提供的类型转换器或者用户自定义的类型转换器
					 */
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				//探测是否有不确定性的构造函数存在，例如：不同构造函数的参数为父子关系
				/**
				 * 2.4 构造函数不确定验证
				 */
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));

				// Choose this constructor if it represents the closest match.
				// 如果它代表着当前最接近的匹配，则选择作为构造函数
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<Constructor>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			//遍历结束以后没有找到构造器
			if (constructorToUse == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				//存在不确定的构造器
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			if (explicitArgs == null) {
				//将解析的构造函数加入到缓存
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		/**
		 * 3. 根据实例化策略 以及 得到的构造函数 及 构造函数参数 实例化bean
		 */
		try {
			Object beanInstance;

			if (System.getSecurityManager() != null) {
				final Constructor ctorToUse = constructorToUse;
				final Object[] argumentsToUse = argsToUse;
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						return beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, beanFactory, ctorToUse, argumentsToUse);
					}
				}, beanFactory.getAccessControlContext());
			}
			else {
				beanInstance = this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}

			//将构造的实例加入到BeanWrapperImpl中
			bw.setWrappedInstance(beanInstance);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class factoryClass;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
		}
		else {
			factoryClass = mbd.getBeanClass();
		}
		factoryClass = ClassUtils.getUserClass(factoryClass);
		Method[] candidates = ReflectionUtils.getAllDeclaredMethods(factoryClass);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes())) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		synchronized (mbd.constructorArgumentLock) {
			mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or <code>null</code> if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(final String beanName, final RootBeanDefinition mbd, final Object[] explicitArgs) {
		BeanWrapperImpl bw = new BeanWrapperImpl();
		this.beanFactory.initBeanWrapper(bw);

		Object factoryBean;
		Class factoryClass;
		boolean isStatic;

		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (factoryBean == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"factory-bean '" + factoryBeanName + "' returned null");
			}
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}

		Method factoryMethodToUse = null;
		ArgumentsHolder argsHolderToUse = null;
		Object[] argsToUse = null;

		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);
			Method[] rawCandidates;

			final Class factoryClazz = factoryClass;
			if (System.getSecurityManager() != null) {
				rawCandidates = AccessController.doPrivileged(new PrivilegedAction<Method[]>() {
					public Method[] run() {
						return (mbd.isNonPublicAccessAllowed() ?
								ReflectionUtils.getAllDeclaredMethods(factoryClazz) : factoryClazz.getMethods());
					}
				});
			}
			else {
				rawCandidates = (mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClazz) : factoryClazz.getMethods());
			}
			
			List<Method> candidateSet = new ArrayList<Method>();
			for (Method candidate : rawCandidates) {
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic &&
						candidate.getName().equals(mbd.getFactoryMethodName()) &&
						mbd.isFactoryMethod(candidate)) {
					candidateSet.add(candidate);
				}
			}
			Method[] candidates = candidateSet.toArray(new Method[candidateSet.size()]);
			AutowireUtils.sortFactoryMethods(candidates);

			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			List<Exception> causes = null;

			for (int i = 0; i < candidates.length; i++) {
				Method candidate = candidates[i];
				Class[] paramTypes = candidate.getParameterTypes();

				if (paramTypes.length >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					if (resolvedValues != null) {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							argsHolder = createArgumentArray(
									beanName, mbd, resolvedValues, bw, paramTypes, paramNames, candidate, autowiring);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (this.beanFactory.logger.isTraceEnabled()) {
								this.beanFactory.logger.trace("Ignoring factory method [" + candidate +
										"] of bean '" + beanName + "': " + ex);
							}
							if (i == candidates.length - 1 && argsHolderToUse == null) {
								if (causes != null) {
									for (Exception cause : causes) {
										this.beanFactory.onSuppressedException(cause);
									}
								}
								throw ex;
							}
							else {
								// Swallow and try next overloaded factory method.
								if (causes == null) {
									causes = new LinkedList<Exception>();
								}
								causes.add(ex);
								continue;
							}
						}
					}

					else {
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}

					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<Method>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null) {
				boolean hasArgs = (resolvedValues.getArgumentCount() > 0);
				String argDesc = "";
				if (hasArgs) {
					List<String> argTypes = new ArrayList<String>();
					for (ValueHolder value : resolvedValues.getIndexedArgumentValues().values()) {
						String argType = (value.getType() != null ?
								ClassUtils.getShortName(value.getType()) : value.getValue().getClass().getSimpleName());
						argTypes.add(argType);
					}
					argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found: " +
						(mbd.getFactoryBeanName() != null ?
							"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(hasArgs ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class.equals(factoryMethodToUse.getReturnType())) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() +
						"': needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found in bean '" + beanName + "' " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		try {
			Object beanInstance;

			if (System.getSecurityManager() != null) {
				final Object fb = factoryBean;
				final Method factoryMethod = factoryMethodToUse;
				final Object[] args = argsToUse;
				beanInstance = AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						return beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, beanFactory, fb, factoryMethod, args);
					}
				}, beanFactory.getAccessControlContext());
			}
			else {
				beanInstance = beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, beanFactory, factoryBean, factoryMethodToUse, argsToUse);
			}
			
			if (beanInstance == null) {
				return null;
			}
			bw.setWrappedInstance(beanInstance);
			return bw;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * This method is also used for handling invocations of static factory methods.
	 */
	/**
	 * 有了配置文件中的信息，便可以获取构造器对应的参数值信息了，获取参数值的信息包括直接指定值，
	 * 如：直接指定构造函数中某个值为原始类型String 类型，或者一个对其他bean 的引用，
	 * 而这一处理委托给 resolveConstructorArguments 方法，
	 * 并返回能解析到的参数的个数
	 *
	 * @param beanName
	 * @param mbd
	 * @param bw
	 * @param cargs 配置文件中配置的构造器参数信息
	 * @param resolvedValues 用于承载解析后的构造函数参数的值
	 * @return
	 */
	private int resolveConstructorArguments(
			String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter converter = (this.beanFactory.getCustomTypeConverter() != null ?
				this.beanFactory.getCustomTypeConverter() : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		int minNrOfArgs = cargs.getArgumentCount();

		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class[] paramTypes, String[] paramNames, Object methodOrCtor,
			boolean autowiring) throws UnsatisfiedDependencyException {

		String methodType = (methodOrCtor instanceof Constructor ? "constructor" : "factory method");
		TypeConverter converter = (this.beanFactory.getCustomTypeConverter() != null ?
				this.beanFactory.getCustomTypeConverter() : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders =
				new HashSet<ConstructorArgumentValues.ValueHolder>(paramTypes.length);
		Set<String> autowiredBeanNames = new LinkedHashSet<String>(4);

		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : null);
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder =
					resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
			// If we couldn't find a direct match and are not supposed to autowire,
			// let's try the next generic, untyped argument value as fallback:
			// it could match after type conversion (for example, String -> int).
			if (valueHolder == null && !autowiring) {
				valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
			}
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					ConstructorArgumentValues.ValueHolder sourceHolder =
							(ConstructorArgumentValues.ValueHolder) valueHolder.getSource();
					Object sourceValue = sourceHolder.getValue();
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType,
								MethodParameter.forMethodOrConstructor(methodOrCtor, paramIndex));
						// TODO re-enable once race condition has been found (SPR-7423)
						/*
						if (originalValue == sourceValue || sourceValue instanceof TypedStringValue) {
							// Either a converted value or still the original one: store converted value.
							sourceHolder.setConvertedValue(convertedValue);
							args.preparedArguments[paramIndex] = convertedValue;
						}
						else {
						*/
							args.resolveNecessary = true;
							args.preparedArguments[paramIndex] = sourceValue;
						// }
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, paramIndex, paramType,
								"Could not convert " + methodType + " argument value of type [" +
								ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, paramIndex, paramType,
							"Ambiguous " + methodType + " argument types - " +
							"did you specify the correct bean references as " + methodType + " arguments?");
				}
				try {
					MethodParameter param = MethodParameter.forMethodOrConstructor(methodOrCtor, paramIndex);
					Object autowiredArgument = resolveAutowiredArgument(param, beanName, autowiredBeanNames, converter);
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = new AutowiredArgumentMarker();
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, paramIndex, paramType, ex);
				}
			}
		}

		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (this.beanFactory.logger.isDebugEnabled()) {
				this.beanFactory.logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + methodType + " to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(
			String beanName, RootBeanDefinition mbd, BeanWrapper bw, Member methodOrCtor, Object[] argsToResolve) {

		Class[] paramTypes = (methodOrCtor instanceof Method ?
				((Method) methodOrCtor).getParameterTypes() : ((Constructor) methodOrCtor).getParameterTypes());
		TypeConverter converter = (this.beanFactory.getCustomTypeConverter() != null ?
				this.beanFactory.getCustomTypeConverter() : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forMethodOrConstructor(methodOrCtor, argIndex);
			GenericTypeResolver.resolveParameterType(methodParam, methodOrCtor.getDeclaringClass());
			if (argValue instanceof AutowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				String methodType = (methodOrCtor instanceof Constructor ? "constructor" : "factory method");
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, argIndex, paramType,
						"Could not convert " + methodType + " argument value of type [" +
						ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 */
	protected Object resolveAutowiredArgument(
			MethodParameter param, String beanName, Set<String> autowiredBeanNames, TypeConverter typeConverter) {

		return this.beanFactory.resolveDependency(
				new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object rawArguments[];

		public final Object arguments[];

		public final Object preparedArguments[];

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return (rawTypeDiffWeight < typeDiffWeight ? rawTypeDiffWeight : typeDiffWeight);
		}

		public int getAssignabilityWeight(Class[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Object constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Marker for autowired arguments in a cached argument array.
 	 */
	private static class AutowiredArgumentMarker {
	}


	/**
	 * Inner class to avoid a Java 6 dependency.
	 */
	private static class ConstructorPropertiesChecker {

		public static String[] evaluateAnnotation(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}
}