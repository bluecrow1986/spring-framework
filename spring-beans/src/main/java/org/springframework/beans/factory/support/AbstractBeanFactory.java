/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.PropertyEditor;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.log.LogMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @since 15 April 2001
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

	/**
	 * 父bean工厂, 用于bean继承支持
	 * <p>
	 * Parent bean factory, for bean inheritance support.
	 */
	@Nullable
	private BeanFactory parentBeanFactory;

	/**
	 * 必要时使用ClassLoader解析Bean类名称, 默认使用线程上下文类加载器
	 * <p>
	 * ClassLoader to resolve bean class names with, if necessary.
	 */
	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/**
	 * 必要时使用ClassLoader临时解析Bean类名称
	 * <p>
	 * ClassLoader to temporarily resolve bean class names with, if necessary.
	 * */
	@Nullable
	private ClassLoader tempClassLoader;

	/**
	 * true: 缓存bean元数据 <p>
	 * false: 每次访问重新获取它
	 * <p>
	 * Whether to cache bean metadata or rather reobtain it for every access.
	 */
	private boolean cacheBeanMetadata = true;

	/**
	 * bean定义值中表达式的解析策略 <p>
	 * SpringBoot默认使用的是StandardBeanExpressionResolver
	 * <p>
	 * Resolution strategy for expressions in bean definition values.
	 */
	@Nullable
	private BeanExpressionResolver beanExpressionResolver;

	/**
	 * 类型转换的服务接口, 这个是转换系统的入口.
	 * 调用{@link ConversionService#convert(Object, Class)}去执行一个线程安全的类型转换器使用此系统
	 * <p>
	 * Spring ConversionService to use instead of PropertyEditors.
	 */
	@Nullable
	private ConversionService conversionService;

	/**
	 * 定制PropertyEditorRegistrars应用于此工厂的bean集合
	 * <p>
	 * Custom PropertyEditorRegistrars to apply to the beans of this factory.
	 */
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars = new LinkedHashSet<>(4);

	/**
	 * 定制PropertyEditor应用于该工厂的bean
	 * <p>
	 * Custom PropertyEditors to apply to the beans of this factory.
	 */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors = new HashMap<>(4);

	/**
	 * 要使用的自定义类型转换器, 覆盖默认的PropertyEditor机制
	 * <p>
	 * A custom TypeConverter to use, overriding the default PropertyEditor mechanism.
	 */
	@Nullable
	private TypeConverter typeConverter;

	/**
	 * 字符串解析器; 适用于注解属性值
	 * <p>
	 * String resolvers to apply e.g. to annotation attribute values.
	 */
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/**
	 * BeanPosProcessor应用于createBean
	 * <p>
	 * BeanPostProcessors to apply.
	 */
	private final List<BeanPostProcessor> beanPostProcessors = new CopyOnWriteArrayList<>();

	/**
	 * 指示是否已经注册了任何 InstantiationAwareBeanPostProcessors 对象
	 * <p>
	 * Indicates whether any InstantiationAwareBeanPostProcessors have been registered.
	 */
	private volatile boolean hasInstantiationAwareBeanPostProcessors;

	/**
	 * 表明DestructionAwareBeanPostProcessors是否被注册
	 * <p>
	 * Indicates whether any DestructionAwareBeanPostProcessors have been registered.
	 */
	private volatile boolean hasDestructionAwareBeanPostProcessors;

	/**
	 * 从作用域表示符String映射到相应的作用域
	 * <p>
	 * Map from scope identifier String to corresponding Scope.
	 */
	private final Map<String, Scope> scopes = new LinkedHashMap<>(8);

	/**
	 * 与SecurityManager一起运行时使用的安全上下文
	 * <p>
	 * Security context used when running with a SecurityManager.
	 */
	@Nullable
	private SecurityContextProvider securityContextProvider;

	/**
	 * 从bean名称映射到合并的RootBeanDefinition
	 * <p>
	 * Map from bean name to merged RootBeanDefinition.
	 */
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

	/**
	 * 至少已经创建一次的bean名称
	 * <p>
	 * Names of beans that have already been created at least once.
	 */
	private final Set<String> alreadyCreated = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	/**
	 * 当前正在创建的bean名称
	 * <p>
	 * Names of beans that are currently in creation.
	 */
	private final ThreadLocal<Object> prototypesCurrentlyInCreation =
			new NamedThreadLocal<>("Prototype beans currently in creation");


	/**
	 * 默认无参构造器; 创建一个新的AbstractBeanFactory
	 * Create a new AbstractBeanFactory.
	 */
	public AbstractBeanFactory() {
	}

	/**
	 * 一个参数的构造器; 通过给定的父Bean工厂创建一个新的AbstractBeanFactory
	 * Create a new AbstractBeanFactory with the given parent.
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 * @see #getBean
	 */
	public AbstractBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	/**
	 * 获取bean的方法; 也是触发依赖注入的方法
	 * <p>
	 * @param name the name of the bean to retrieve
	 * @return 返回一个实例，该实例可以指定bean的共享或独立
	 * @throws BeansException
	 */
	@Override
	public Object getBean(String name) throws BeansException {
		return doGetBean(name, null, null, false);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		return doGetBean(name, requiredType, null, false);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		return doGetBean(name, null, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	public <T> T getBean(String name, @Nullable Class<T> requiredType, @Nullable Object... args)
			throws BeansException {

		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * 真正干活的方法; 返回一个实例，该实例可以指定bean的共享或独立
	 * <p>
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * @param name the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args arguments to use when creating a bean instance using explicit arguments
	 * (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @param typeCheckOnly whether the instance is obtained for a type check,
	 * not for actual use
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	@SuppressWarnings("unchecked")
	protected <T> T doGetBean(
			String name, @Nullable Class<T> requiredType, @Nullable Object[] args, boolean typeCheckOnly)
			throws BeansException {
		// 获取beanName, 规范名称
		// 1. 先去除name开头的'&'字符, 返回剩余的字符串得到转换后的Bean名称;
		// 2. 然后再遍历aliasMap(别名映射到规范名称集合), 得到最终规范名称
		String beanName = transformedBeanName(name);
		Object bean;

		// Eagerly check singleton cache for manually registered singletons.
		// 提前检查单例缓存中是否有手动注册的单例对象, 跟循环依赖有关联
		Object sharedInstance = getSingleton(beanName);
		// bean的单例对象找到了 && 没有创建bean实例时要使用的参数
		if (sharedInstance != null && args == null) {
			if (logger.isTraceEnabled()) {
				if (isSingletonCurrentlyInCreation(beanName)) {
					logger.trace("Returning eagerly cached instance of singleton bean '" + beanName +
							"' that is not fully initialized yet - a consequence of a circular reference");
				}
				else {
					logger.trace("Returning cached instance of singleton bean '" + beanName + "'");
				}
			}
			// 返回对象的实例; 当实现了FactoryBean接口的对象, 需要获取具体的对象的时候就需要此方法来进行获取了
			bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
		}

		else {
			// Fail if we're already creating this bean instance:
			// We're assumably within a circular reference.
			// 当对象都是单例的时候会尝试解决循环依赖的问题, 但是原型模式下如果存在循环依赖的情况, 那么直接抛出异常
			// beanName当前正在创建中(在当前线程内)
			if (isPrototypeCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(beanName);
			}

			// Check if bean definition exists in this factory.
			// 如果bean定义不存在, 就检查父Bean工厂是否有
			BeanFactory parentBeanFactory = getParentBeanFactory();
			// 父Bean工厂不为null && 该BeanFactory不包含beanName的BeanDefinition对象
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// Not found -> check parent.
				// 获取name对应的规范名称(全类名/最终别名); 如果name前面有"&", 则会返回"&"+规范名称(全类名)
				String nameToLookup = originalBeanName(name);
				// 如果父Bean工厂是AbstractBeanFactory的实例
				if (parentBeanFactory instanceof AbstractBeanFactory) {
					// 递归调用父工厂的doGetBean方法
					return ((AbstractBeanFactory) parentBeanFactory).doGetBean(
							nameToLookup, requiredType, args, typeCheckOnly);
				}
				// 创建bean实例时要使用的参数不为null
				else if (args != null) {
					// Delegation to parent with explicit args.
					// 使用父工厂获取该bean对象(通过bean全类名和创建bean实例时要使用的参数)
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				}
				// 要检索的bea的所需类型不为null
				else if (requiredType != null) {
					// No args -> delegate to standard getBean method.
					// 使用父工厂获取该bean对象(通过bean全类名和所需的bean类型)
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				}
				else {
					// 使用父工厂获取bean对象(通过bean全类名)
					return (T) parentBeanFactory.getBean(nameToLookup);
				}
			}
			// 如果不是做类型检查; 那么表示要创建bean, 此处在集合中做一个记录
			if (!typeCheckOnly) {
				// beanName标记为已经创建(或将要创建)
				markBeanAsCreated(beanName);
			}

			try {
				// 获取beanName合并后的本地RootBeanDefinition(此处要做类型转换, 如果是子类bean的话, 会合并父类的相关属性)
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				// 检查mbd的合法性, 不合格会引发验证异常(BeanIsAbstractException, Bean是抽象异常)
				checkMergedBeanDefinition(mbd, beanName, args);

				// Guarantee initialization of beans that the current bean depends on.
				// 如果存在依赖的bean的话, 那么则优先实例化依赖的bean
				String[] dependsOn = mbd.getDependsOn();
				// 如果存在依赖的bean
				if (dependsOn != null) {
					// 遍历
					for (String dep : dependsOn) {
						// 如果beanName已注册依赖于dependentBeanName的关系(内部递归调用)
						if (isDependent(beanName, dep)) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						// 注册各个bean的依赖关系; 方便进行销毁
						registerDependentBean(dep, beanName);
						try {
							// 递归调用, 优先实例化被依赖的Bean
							getBean(dep);
						}
						// 捕捉为找到BeanDefinition异常: "beanName依赖于缺少的bean dep"
						catch (NoSuchBeanDefinitionException ex) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"'" + beanName + "' depends on missing bean '" + dep + "'", ex);
						}
					}
				}

				// Create bean instance.
				// 创建bean的实例对象
				// mbd的作用域是单例模式("singleton")
				if (mbd.isSingleton()) {
					// 返回以beanName的(原始)单例对象; 如果尚未注册, 则使用singletonFactory创建并注册一个对象
					sharedInstance = getSingleton(beanName, () -> {
						try {
							// 为给定的合并后BeanDefinition(和参数)创建一个bean实例
							return createBean(beanName, mbd, args);
						}
						catch (BeansException ex) {
							// Explicitly remove instance from singleton cache: It might have been put there
							// eagerly by the creation process, to allow for circular reference resolution.
							// Also remove any beans that received a temporary reference to the bean.
							// 显示地从单例缓存中删除实例: 它可能是由创建过程急切地放在那里, 以允许循环引用解析.
							// 还要删除接收到该Bean临时引用的任何Bean销毁给定的bean.
							// 如果找到相应的一次性Bean实例, 则委托给destroyBean
							destroySingleton(beanName);
							// 重新抛出ex
							throw ex;
						}
					});
					// 从beanInstance中获取公开的Bean对象, 主要处理beanInstance是FactoryBean对象的情况;
					// 如果不是FactoryBean会直接返回beanInstance实例
					bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
				}
				// mbd的作用域是原型模式("prototype")
				else if (mbd.isPrototype()) {
					// It's a prototype -> create a new instance.
					// 定义prototype实例
					Object prototypeInstance = null;
					try {
						// 创建ProtoPype对象前的准备工作; 默认实现将beanName添加到prototypesCurrentlyInCreation中
						beforePrototypeCreation(beanName);
						// 为mbd(和参数)创建一个bean实例
						prototypeInstance = createBean(beanName, mbd, args);
					}
					finally {
						// 创建完prototype实例后的回调; 默认实现是将beanName从prototypesCurrentlyInCreation移除
						afterPrototypeCreation(beanName);
					}
					// 从beanInstance中获取公开的Bean对象, 主要处理beanInstance是FactoryBean对象的情况;
					// 如果不是FactoryBean会直接返回beanInstance实例
					bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
				}
				// 其他情况; request、session、global session
				else {
					// 获取作用域
					String scopeName = mbd.getScope();
					// 如果scopeName为空
					if (!StringUtils.hasLength(scopeName)) {
						// 抛出非法状态异常: 没有为bean定义范围名称
						throw new IllegalStateException("No scope name defined for bean ´" + beanName + "'");
					}
					// 在作用域集合中根据作用域名称获取
					Scope scope = this.scopes.get(scopeName);
					// 未查找到
					if (scope == null) {
						// 抛出非法状态异常: 没有为作用域名称注册作用域
						throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
					}
					try {
						// 从scope中获取beanName对应的实例对象
						Object scopedInstance = scope.get(beanName, () -> {
							// 创建ProtoPype对象前的准备工作; 默认实现将beanName添加到prototypesCurrentlyInCreation中
							beforePrototypeCreation(beanName);
							try {
								// 为mbd(和参数)创建一个bean实例
								return createBean(beanName, mbd, args);
							}
							finally {
								// 创建完prototype实例后的回调; 默认实现是将beanName从prototypesCurrentlyInCreation移除
								afterPrototypeCreation(beanName);
							}
						});
						// 从beanInstance中获取公开的Bean对象, 主要处理beanInstance是FactoryBean对象的情况;
						// 如果不是FactoryBean会直接返回beanInstance实例
						bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
					}
					// 捕捉非法状态异常
					catch (IllegalStateException ex) {
						// 抛出Bean创建异常: 作用域"scopeName"对于当前线程是不活动的; 如果您打算从单个实例引用它, 请考虑为此
						// beanDefinition一个作用域代理
						throw new BeanCreationException(beanName,
								"Scope '" + scopeName + "' is not active for the current thread; consider " +
								"defining a scoped proxy for this bean if you intend to refer to it from a singleton",
								ex);
					}
				}
			}
			// 捕捉获取Bean对象抛出的Bean异常
			catch (BeansException ex) {
				// 在Bean创建失败后, 对缓存的元数据执行适当的清理
				cleanupAfterBeanCreationFailure(beanName);
				// 重新抛出异常
				throw ex;
			}
		}

		// Check if required type matches the type of the actual bean instance.
		// 检查requiredType是否与实际Bean实例的类型匹配
		// 如果requiredType不为null && bean不是requiredType的实例
		if (requiredType != null && !requiredType.isInstance(bean)) {
			try {
				// 获取此BeanFactory使用的类型转换器, 并将bean转换为requiredType
				T convertedBean = getTypeConverter().convertIfNecessary(bean, requiredType);
				// 如果convertedBean为null
				if (convertedBean == null) {
					// 抛出Bean不是必要类型的异常
					throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
				}
				// 返回convertedBean
				return convertedBean;
			}
			// 捕获类型不匹配异常
			catch (TypeMismatchException ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Failed to convert bean '" + name + "' to required type '" +
							ClassUtils.getQualifiedName(requiredType) + "'", ex);
				}
				// 抛出Bean不是必需的类型异常
				throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
			}
		}
		// 将bean返回出去
		return (T) bean;
	}

	@Override
	public boolean containsBean(String name) {
		String beanName = transformedBeanName(name);
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// Not found -> check parent.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean) {
				return (BeanFactoryUtils.isFactoryDereference(name) || ((FactoryBean<?>) beanInstance).isSingleton());
			}
			else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// In case of FactoryBean, return singleton status of created object if not a dereference.
		if (mbd.isSingleton()) {
			if (isFactoryBean(beanName, mbd)) {
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					return true;
				}
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				return factoryBean.isSingleton();
			}
			else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isPrototype(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isPrototype()) {
			// In case of FactoryBean, return singleton status of created object if not a dereference.
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
		}

		// Singleton or scoped - not a prototype.
		// However, FactoryBean may still produce a prototype object...
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			return false;
		}
		if (isFactoryBean(beanName, mbd)) {
			FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(
						(PrivilegedAction<Boolean>) () ->
								((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
										!fb.isSingleton()),
						getAccessControlContext());
			}
			else {
				return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
						!fb.isSingleton());
			}
		}
		else {
			return false;
		}
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, typeToMatch, true);
	}

	/**
	 * 检查具有给定名称的bean是否与指定的类型匹配
	 * <p>
	 * Internal extended variant of {@link #isTypeMatch(String, ResolvableType)}
	 * to check whether the bean with the given name matches the specified type. Allow
	 * additional constraints to be applied to ensure that beans are not created early.
	 * @param name the name of the bean to query
	 * @param typeToMatch the type to match against (as a
	 * {@code ResolvableType})
	 * @return {@code true} if the bean type matches, {@code false} if it
	 * doesn't match or cannot be determined yet
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @since 5.2
	 * @see #getBean
	 * @see #getType
	 */
	protected boolean isTypeMatch(String name, ResolvableType typeToMatch, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {
		// 获取beanName, 规范名称
		// 1. 先去除name开头的'&'字符, 返回剩余的字符串得到转换后的Bean名称;
		// 2. 然后再遍历aliasMap(别名映射到规范名称集合), 得到最终规范名称
		String beanName = transformedBeanName(name);
		// 判断name是否为FactoryBean的解引用名, 即是否以'&'开头
		boolean isFactoryDereference = BeanFactoryUtils.isFactoryDereference(name);

		// Check manually registered singletons.
		// 返回在给定名称下注册的(原始)单例对象; 检查已经实例化的单例, 并允许提前引用当前创建的单例(不允许创建引用, 解决循环引用)
		Object beanInstance = getSingleton(beanName, false);
		// 如果成功获取到单例对象, 并且该单例对象的类型不是NullBean
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			// 如果实现了FactoryBean
			if (beanInstance instanceof FactoryBean) {
				// 如果name不是FactoryBean的解引用名(即不是以'&'开头)
				if (!isFactoryDereference) {
					// 直接获取beanInstance的创建出来的对象的类型
					Class<?> type = getTypeForFactoryBean((FactoryBean<?>) beanInstance);
					// 成功获取到beanInstance的创建出来的对象的类型 && 属于要匹配的类型
					return (type != null && typeToMatch.isAssignableFrom(type));
				}
				else {
					// 返回单例对象是否属于要匹配的类型的实例
					return typeToMatch.isInstance(beanInstance);
				}
			}
			// 如果name不是FactoryBean的解引用名
			else if (!isFactoryDereference) {
				// 单例对象属于要匹配的类型的实例
				if (typeToMatch.isInstance(beanInstance)) {
					// Direct match for exposed instance?
					// 直接匹配暴露的实例? 表示要查询的Bean名与要匹配的类型匹配
					return true;
				}
				// 要匹配的类型包含泛型参数 && 此bean工厂包含beanName所指的BeanDefinition定义
				else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// Generics potentially only match on the target class, not on the proxy...
					// 泛型可能仅在目标类上匹配, 而在代理上不匹配
					// 获取beanName所对应的合并RootBeanDefinition
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// 获取mbd的目标类型
					Class<?> targetType = mbd.getTargetType();
					// 成功获取到了mbd的目标类型 && 目标类型与单例对象的类型不同
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance)) {
						// Check raw class match as well, making sure it's exposed on the proxy.
						// 同时检查原始类匹配, 确保它在代理中公开
						// 获取TypeToMatch的封装Class对象
						Class<?> classToMatch = typeToMatch.resolve();
						// 成功获取Class对象 && 单例对象不是该Class对象的实例
						if (classToMatch != null && !classToMatch.isInstance(beanInstance)) {
							// 表示要查询的Bean名与要匹配的类型不匹配
							return false;
						}
						// 如果mbd的目标类型属于要匹配的类型
						if (typeToMatch.isAssignableFrom(targetType)) {
							// 表示要查询的Bean名与要匹配的类型匹配
							return true;
						}
					}
					// 获取mbd的目标类型
					ResolvableType resolvableType = mbd.targetType;
					// 如果获取mbd的目标类型失败
					if (resolvableType == null) {
						// 获取mbd的工厂方法返回类型作为mbd的目标类型
						resolvableType = mbd.factoryMethodReturnType;
					}
					// 如果成功获取到了mbd的目标类型 && 该目标类型属于要匹配的类型, 就返回true, 否则返回false
					return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
				}
			}
			// 如果beanName的单例对象不是FactoryBean的实例或者name是FactoryBean的解引用名
			return false;
		}
		// 该工厂的单例对象注册器(一级缓存)包含beanName所指的单例对象, 但该工厂没有beanName对应的BeanDefinition对象
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			// 注册了null实例, 即beanName对应的实例是NullBean实例;
			// 因前面已经处理了beanName不是NullBean的情况, 再加上该工厂没有对应beanName的BeanDefinition对象
			return false;
		}

		// No singleton instance found -> check bean definition.
		// 找不到单例实例 -> 检查bean定义
		// 获取该工厂的父级工厂
		BeanFactory parentBeanFactory = getParentBeanFactory();
		// 父级工厂不为null && 该工厂没有包含beanName的BeanDefinition
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// 在该工厂中找不到BeanDefinition -> 委托给父对象
			// 递归交给父工厂判断, 将判断结果返回出去
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}
		// Retrieve corresponding bean definition.
		// 检索相应的bean定义
		// 获取beanName合并后的本地RootBeanDefinition
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		// 获取mbd的BeanDefinitionHolder
		// BeanDefinitionHolder就是对BeanDefinition的持有，同时持有的包括BeanDefinition的名称和别名
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();

		// Setup the types that we want to match against
		// 设置我们要匹配的类型
		// 获取我们要匹配的class对象
		Class<?> classToMatch = typeToMatch.resolve();
		// 如果classToMatch为null
		if (classToMatch == null) {
			// 默认使用FactoryBean作为要匹配的class对象
			classToMatch = FactoryBean.class;
		}
		// 如果factoryBean不是要匹配的class对象, 要匹配的类数组会加上FactoryBean.class
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[] {classToMatch} : new Class<?>[] {FactoryBean.class, classToMatch});


		// Attempt to predict the bean type
		// 尝试预测bean类型
		Class<?> predictedType = null;

		// We're looking for a regular reference but we're a factory bean that has
		// a decorated bean definition. The target bean should be the same type
		// as FactoryBean would ultimately return.
		// 我们正在寻找常规参考, 但是我们是具有修饰的BeanDefinition的FactoryBean.
		// 目标bean类型应与factoryBean最终返回的类型相同.
		// 如果不是FactoryBean解引用 && mbd有配置BeanDefinitionHolder且beanName,mbd所指的bean是FactoryBean
		if (!isFactoryDereference && dbd != null && isFactoryBean(beanName, mbd)) {
			// We should only attempt if the user explicitly set lazy-init to true
			// and we know the merged bean definition is for a factory bean.
			// 只有在用户将lazy-init显示设置为true并且我们知道合并的BeanDefinition是针对FactoryBean的情况下, 才应该尝试;
			// mbd没有设置lazy-init || 允许FactoryBean初始化
			if (!mbd.isLazyInit() || allowFactoryBeanInit) {
				// 获取dbd的beanName, dbd的BeanDefinition, mbd所对应的合并后RootBeanDefinition
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				// 预测指定信息(dbd的beanName, tbd, typesToMatch)的Bean类型
				Class<?> targetType = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
				// 目标类型不为null && targetType不属于FactoryBean
				if (targetType != null && !FactoryBean.class.isAssignableFrom(targetType)) {
					// 预测bean类型就为该目标类型
					predictedType = targetType;
				}
			}
		}

		// If we couldn't use the target type, try regular prediction.
		// 如果我们无法使用目标类型, 请尝试常规预测
		// 如果无法获得预测bean类型
		if (predictedType == null) {
			// 预测指定信息(beanName, mbd, typesToMatch)的Bean类型
			predictedType = predictBeanType(beanName, mbd, typesToMatch);
			// 如果没有成功获取到预测bean类型, 返回false, 表示不匹配
			if (predictedType == null) {
				return false;
			}
		}

		// Attempt to get the actual ResolvableType for the bean.
		// 定义Bean的实际ResolvableType(Spring封装Java基础类型的元信息类)
		ResolvableType beanType = null;

		// If it's a FactoryBean, we want to look at what it creates, not the factory class.
		// 如果是FactoryBean, 我们要查看它创建的内容, 而不是工厂类
		// 如果predictedType属于FactoryBean
		if (FactoryBean.class.isAssignableFrom(predictedType)) {
			// 没有beanName的单例对象 && beanName不是指FactoryBean解引用
			if (beanInstance == null && !isFactoryDereference) {
				// 根据beanName, mbd获取FactoryBean定义的bean, 类型赋值给beanType
				beanType = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit);
				// 解析beanType以得到predictedType
				predictedType = beanType.resolve();
				if (predictedType == null) {
					// 返回false, 表示不匹配
					return false;
				}
			}
		}
		// 形参name是FactoryBean解引用
		else if (isFactoryDereference) {
			// Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
			// type but we nevertheless are being asked to dereference a FactoryBean...
			// Let's check the original bean class and proceed with it if it is a FactoryBean.
			// 特殊情况: SmartInstantiationAwareBeanPostProcessor返回非FactoryBean类型, 但是仍然要求我们取消引用FactoryBean...
			// 让我们检查原始bean类, 如果它是FactoryBean, 则继续进行处理
			// 预测mdb所指的bean的最终bean类型
			predictedType = predictBeanType(beanName, mbd, FactoryBean.class);
			// 预测不到 || 得到的预测类型属于FactoryBean
			if (predictedType == null || !FactoryBean.class.isAssignableFrom(predictedType)) {
				// 返回false, 表示不匹配
				return false;
			}
		}

		// We don't have an exact type but if bean definition target type or the factory
		// method return type matches the predicted type then we can use that.
		// 我们没有确切的类型, 但是如果bean定义目标类型或者工厂方法返回类型与预测的类型匹配, 则可以使用它
		// 如果没有取到beanType
		if (beanType == null) {
			// 声明一个定义类型, 默认使用mbd的目标类型
			ResolvableType definedType = mbd.targetType;
			// 定义类型为null
			if (definedType == null) {
				// 使用通用类型工厂方法的返回类型
				definedType = mbd.factoryMethodReturnType;
			}
			// 定义类型不为null && definedType所封装的Class对象与预测类型相同
			if (definedType != null && definedType.resolve() == predictedType) {
				// 将自定义类型赋值给beanType
				beanType = definedType;
			}
		}

		// If we have a bean type use it so that generics are considered
		// 如果我们有一个bean类型, 请使用它以便将泛型考虑在内
		// 如果取到了beanType
		if (beanType != null) {
			// 返回beanType是否属于typeToMatch的结果
			return typeToMatch.isAssignableFrom(beanType);
		}

		// If we don't have a bean type, fallback to the predicted type
		// 如果我们没有bean类型, 则回退到预测类型
		// 如果我们没有bean类型, 返回predictedType是否属于typeToMatch的结果
		return typeToMatch.isAssignableFrom(predictedType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		return getType(name, true);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			if (beanInstance instanceof FactoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				return getTypeForFactoryBean((FactoryBean<?>) beanInstance);
			}
			else {
				return beanInstance.getClass();
			}
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.getType(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// Check decorated bean definition, if any: We assume it'll be easier
		// to determine the decorated bean's type than the proxy's type.
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
		if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
			RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
			Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
			if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
				return targetClass;
			}
		}

		Class<?> beanClass = predictBeanType(beanName, mbd);

		// Check bean class whether we're dealing with a FactoryBean.
		if (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass)) {
			if (!BeanFactoryUtils.isFactoryDereference(name)) {
				// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
				return getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit).resolve();
			}
			else {
				return beanClass;
			}
		}
		else {
			return (!BeanFactoryUtils.isFactoryDereference(name) ? beanClass : null);
		}
	}

	@Override
	public String[] getAliases(String name) {
		String beanName = transformedBeanName(name);
		List<String> aliases = new ArrayList<>();
		boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
		String fullBeanName = beanName;
		if (factoryPrefix) {
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		if (!fullBeanName.equals(name)) {
			aliases.add(fullBeanName);
		}
		String[] retrievedAliases = super.getAliases(beanName);
		String prefix = factoryPrefix ? FACTORY_BEAN_PREFIX : "";
		for (String retrievedAlias : retrievedAliases) {
			String alias = prefix + retrievedAlias;
			if (!alias.equals(name)) {
				aliases.add(alias);
			}
		}
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null) {
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
		return StringUtils.toStringArray(aliases);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	/**
	 * 获取父bean工厂, 用于bean继承支持
	 * @return 父bean工厂
	 */
	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		if (this == parentBeanFactory) {
			throw new IllegalStateException("Cannot set parent bean factory to self");
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
		this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
	}

	@Override
	@Nullable
	public ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setTempClassLoader(@Nullable ClassLoader tempClassLoader) {
		this.tempClassLoader = tempClassLoader;
	}

	@Override
	@Nullable
	public ClassLoader getTempClassLoader() {
		return this.tempClassLoader;
	}

	@Override
	public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
		this.cacheBeanMetadata = cacheBeanMetadata;
	}

	@Override
	public boolean isCacheBeanMetadata() {
		return this.cacheBeanMetadata;
	}

	@Override
	public void setBeanExpressionResolver(@Nullable BeanExpressionResolver resolver) {
		this.beanExpressionResolver = resolver;
	}

	@Override
	@Nullable
	public BeanExpressionResolver getBeanExpressionResolver() {
		return this.beanExpressionResolver;
	}

	@Override
	public void setConversionService(@Nullable ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@Override
	@Nullable
	public ConversionService getConversionService() {
		return this.conversionService;
	}

	@Override
	public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
		Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
		this.propertyEditorRegistrars.add(registrar);
	}

	/**
	 * Return the set of PropertyEditorRegistrars.
	 */
	public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
		return this.propertyEditorRegistrars;
	}

	@Override
	public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
		Assert.notNull(requiredType, "Required type must not be null");
		Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
		this.customEditors.put(requiredType, propertyEditorClass);
	}

	@Override
	public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
		registerCustomEditors(registry);
	}

	/**
	 * Return the map of custom editors, with Classes as keys and PropertyEditor classes as values.
	 */
	public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
		return this.customEditors;
	}

	@Override
	public void setTypeConverter(TypeConverter typeConverter) {
		this.typeConverter = typeConverter;
	}

	/**
	 * 返回自定义的TypeConverter以使用(如果有)
	 * <p>
	 * Return the custom TypeConverter to use, if any.
	 * @return the custom TypeConverter, or {@code null} if none specified
	 */
	@Nullable
	protected TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	/**
	 * 获取此BeanFactory使用的类型转换器;
	 * 这可能是每次调用都有新实例, 因为TypeConverters通常不是线程安全的.
	 * <p>
	 * @return 此BeanFactory使用的类型转换器;
	 * 默认情况下优先返回自定义的类型转换器{@link #getCustomTypeConverter()}; 获取不到时, 返回一个新的SimpleTypeConverter对象
	 */
	@Override
	public TypeConverter getTypeConverter() {
		// 获取自定义的TypeConverter
		TypeConverter customConverter = getCustomTypeConverter();
		// 如果有自定义的TypeConverter
		if (customConverter != null) {
			// 返回该自定义的TypeConverter
			return customConverter;
		}
		else {
			// Build default TypeConverter, registering custom editors.
			// 构建默认的TypeConverter, 注册自定义编辑器
			// 新建一个SimpleTypeConverter对象(注: 每次调用该方法都会新建一个类型转换器, 因为SimpleTypeConverter不是线程安全的)
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			// 让typeConverter引用该工厂的类型转换的服务接口
			typeConverter.setConversionService(getConversionService());
			// 将工厂中所有PropertyEditor注册到typeConverter中
			registerCustomEditors(typeConverter);
			// 返回SimpleTypeConverter作为该工厂的默认类型转换器
			return typeConverter;
		}
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		return !this.embeddedValueResolvers.isEmpty();
	}

	@Override
	@Nullable
	public String resolveEmbeddedValue(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String result = value;
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			result = resolver.resolveStringValue(result);
			if (result == null) {
				return null;
			}
		}
		return result;
	}

	/**
	 * 后添加的BeanPostProcessor会覆盖之前的(先删除, 再添加)
	 * @param beanPostProcessor the post-processor to register
	 */
	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		// Remove from old position, if any
		// 如果存在就移除
		this.beanPostProcessors.remove(beanPostProcessor);
		// Track whether it is instantiation/destruction aware
		// 此处是为了设置某些状态变量, 这些状态变量会影响后续的执行流程;
		// 实例化类型的后置处理器
		if (beanPostProcessor instanceof InstantiationAwareBeanPostProcessor) {
			// 标识设置真, 表示的是已注册过
			this.hasInstantiationAwareBeanPostProcessors = true;
		}
		// 销毁类型的后置处理器
		if (beanPostProcessor instanceof DestructionAwareBeanPostProcessor) {
			this.hasDestructionAwareBeanPostProcessors = true;
		}
		// Add to end of list
		// 将处理后的BeanPostProcessor添加到缓存中
		this.beanPostProcessors.add(beanPostProcessor);
	}

	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
	}

	/**
	 * Return the list of BeanPostProcessors that will get applied
	 * to beans created with this factory.
	 */
	public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	/**
	 * Return whether this factory holds a InstantiationAwareBeanPostProcessor
	 * that will get applied to singleton beans on creation.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
	 */
	protected boolean hasInstantiationAwareBeanPostProcessors() {
		return this.hasInstantiationAwareBeanPostProcessors;
	}

	/**
	 * Return whether this factory holds a DestructionAwareBeanPostProcessor
	 * that will get applied to singleton beans on shutdown.
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean hasDestructionAwareBeanPostProcessors() {
		return this.hasDestructionAwareBeanPostProcessors;
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		Assert.notNull(scope, "Scope must not be null");
		if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
			throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
		}
		Scope previous = this.scopes.put(scopeName, scope);
		if (previous != null && previous != scope) {
			if (logger.isDebugEnabled()) {
				logger.debug("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
			}
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
			}
		}
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return StringUtils.toStringArray(this.scopes.keySet());
	}

	/**
	 * 获取给定作用域名称对应的作用域对象(如果有)
	 * @param scopeName the name of the scope
	 * @return 传入的作用域名对应的作用域对象
	 */
	@Override
	@Nullable
	public Scope getRegisteredScope(String scopeName) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		// 从映射的linkedHashMap中获取传入的作用域名对应的作用域对象并返回
		return this.scopes.get(scopeName);
	}

	/**
	 * Set the security context provider for this bean factory. If a security manager
	 * is set, interaction with the user code will be executed using the privileged
	 * of the provided security context.
	 */
	public void setSecurityContextProvider(SecurityContextProvider securityProvider) {
		this.securityContextProvider = securityProvider;
	}

	/**
	 * Delegate the creation of the access control context to the
	 * {@link #setSecurityContextProvider SecurityContextProvider}.
	 */
	@Override
	public AccessControlContext getAccessControlContext() {
		return (this.securityContextProvider != null ?
				this.securityContextProvider.getAccessControlContext() :
				AccessController.getContext());
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		Assert.notNull(otherFactory, "BeanFactory must not be null");
		setBeanClassLoader(otherFactory.getBeanClassLoader());
		setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
		setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
		setConversionService(otherFactory.getConversionService());
		if (otherFactory instanceof AbstractBeanFactory) {
			AbstractBeanFactory otherAbstractFactory = (AbstractBeanFactory) otherFactory;
			this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
			this.customEditors.putAll(otherAbstractFactory.customEditors);
			this.typeConverter = otherAbstractFactory.typeConverter;
			this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
			this.hasInstantiationAwareBeanPostProcessors = this.hasInstantiationAwareBeanPostProcessors ||
					otherAbstractFactory.hasInstantiationAwareBeanPostProcessors;
			this.hasDestructionAwareBeanPostProcessors = this.hasDestructionAwareBeanPostProcessors ||
					otherAbstractFactory.hasDestructionAwareBeanPostProcessors;
			this.scopes.putAll(otherAbstractFactory.scopes);
			this.securityContextProvider = otherAbstractFactory.securityContextProvider;
		}
		else {
			setTypeConverter(otherFactory.getTypeConverter());
			String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
			for (String scopeName : otherScopeNames) {
				this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
			}
		}
	}

	/**
	 * Return a 'merged' BeanDefinition for the given bean name,
	 * merging a child bean definition with its parent if necessary.
	 * <p>This {@code getMergedBeanDefinition} considers bean definition
	 * in ancestors as well.
	 * @param name the name of the bean to retrieve the merged definition for
	 * (may be an alias)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		// 获取name对应的规范名称
		String beanName = transformedBeanName(name);
		// Efficiently check whether bean definition exists in this factory.
		// 有效检查该工厂中是否存在bean定义
		// 如果当前bean工厂不包含具有beanName的bean定义 && 父工厂是ConfigurableBeanFactory的实例
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			// 使用父工厂返回beanName的合并BeanDefinition(如有必要，将子bean定义与其父级合并)
			return ((ConfigurableBeanFactory) getParentBeanFactory()).getMergedBeanDefinition(beanName);
		}
		// Resolve merged bean definition locally.
		// 本地解决合并的bean定义
		return getMergedLocalBeanDefinition(beanName);
	}

	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			return (beanInstance instanceof FactoryBean);
		}
		// No singleton instance found -> check bean definition.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((ConfigurableBeanFactory) getParentBeanFactory()).isFactoryBean(name);
		}
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	@Override
	public boolean isActuallyInCreation(String beanName) {
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * 返回指定的原型bean是否当前正在创建中(在当前线程内)
	 * <p>
	 * Return whether the specified prototype bean is currently in creation
	 * (within the current thread).
	 * @param beanName the name of the bean
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		// 获取当前正在创建的bean名称(线程本地)
		Object curVal = this.prototypesCurrentlyInCreation.get();
		// 当前正在创建的bean名称不为null && (当前正在创建的bean名称与beanName相同 || (当前正在创建的bean名称是Set集合 && 集合包含该beanName))
		// 就返回true, 表示beanName当前正在创建中(在当前线程内)
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set && ((Set<?>) curVal).contains(beanName))));
	}

	/**
	 * 创建ProtoPype对象前的准备工作; 默认实现将beanName添加到prototypesCurrentlyInCreation中
	 * <p>
	 * Callback before prototype creation.
	 * <p>The default implementation register the prototype as currently in creation.
	 * @param beanName the name of the prototype about to be created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void beforePrototypeCreation(String beanName) {
		// 从prototypesCurrentlyInCreation中获取线程安全的当前正在创建的Bean对象名
		Object curVal = this.prototypesCurrentlyInCreation.get();
		// 如果curlVal为null
		if (curVal == null) {
			// 将beanName设置到prototypesCurrentlyInCreation中
			this.prototypesCurrentlyInCreation.set(beanName);
		}
		// 如果curlVal为String类型
		else if (curVal instanceof String) {
			// 定义一个HashSet对象(长度为2), 存放prototypesCurrentlyInCreation原有Bean名和beanName
			Set<String> beanNameSet = new HashSet<>(2);
			// 将curlVal添加beanNameSet中
			beanNameSet.add((String) curVal);
			// 将beanName添加到beanNameSet中
			beanNameSet.add(beanName);
			// 将beanNameSet设置到prototypesCurrentlyInCreation中
			this.prototypesCurrentlyInCreation.set(beanNameSet);
		}
		// 其他情况, curlValue就只会是HashSet对象
		else {
			// 将curlVal强转为Set对象
			Set<String> beanNameSet = (Set<String>) curVal;
			// 将beanName添加到beanNameSet中
			beanNameSet.add(beanName);
		}
	}

	/**
	 * 创建完prototype实例后的回调; 默认实现是将beanName从prototypesCurrentlyInCreation移除
	 * <p>
	 * Callback after prototype creation.
	 * <p>The default implementation marks the prototype as not in creation anymore.
	 * @param beanName the name of the prototype that has been created
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void afterPrototypeCreation(String beanName) {
		// 从prototypesCurrentlyInCreation中获取线程安全的当前正在创建的Bean对象名
		Object curVal = this.prototypesCurrentlyInCreation.get();
		// 如果curlVal为String类型
		if (curVal instanceof String) {
			// 将curlVal从prototypesCurrentlyInCreation中移除
			this.prototypesCurrentlyInCreation.remove();
		}
		// 如果curlVal是Set对象
		else if (curVal instanceof Set) {
			// 将curValue强转为Set对象
			Set<String> beanNameSet = (Set<String>) curVal;
			// 将beanName从beanNameSet中移除
			beanNameSet.remove(beanName);
			// 如果beanNameSet已经没有元素了
			if (beanNameSet.isEmpty()) {
				// 将beanNameSet从prototypesCurrentlyInCreation中移除
				this.prototypesCurrentlyInCreation.remove();
			}
		}
	}

	@Override
	public void destroyBean(String beanName, Object beanInstance) {
		destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * Destroy the given bean instance (usually a prototype instance
	 * obtained from this factory) according to the given bean definition.
	 * @param beanName the name of the bean definition
	 * @param bean the bean instance to destroy
	 * @param mbd the merged bean definition
	 */
	protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
		new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), getAccessControlContext()).destroy();
	}

	@Override
	public void destroyScopedBean(String beanName) {
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isSingleton() || mbd.isPrototype()) {
			throw new IllegalArgumentException(
					"Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
		}
		String scopeName = mbd.getScope();
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
		}
		Object bean = scope.remove(beanName);
		if (bean != null) {
			destroyBean(beanName, bean, mbd);
		}
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * 获取beanName, 规范名称<p>
	 * 1. 先去除name开头的'&'字符, 返回剩余的字符串得到转换后的Bean名称;<p>
	 * 2. 然后再遍历aliasMap(别名映射到规范名称集合), 得到最终规范名称<p>
	 * Return the bean name, stripping out the factory dereference prefix if necessary,
	 * and resolving aliases to canonical names.
	 * @param name the user-specified name
	 * @return the transformed bean name
	 */
	protected String transformedBeanName(String name) {
		// 去除开头的'&'字符, 返回剩余的字符串得到转换后的Bean名称 -> BeanFactoryUtils.transformedBeanName(name);
		// 遍历aliasMap(别名映射到规范名称集合), 得到最终规范名称;
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * 获取name对应的规范名称(全类名/最终别名);
	 * 如果name前面有"&", 则会返回"&"+规范名称(全类名)
	 * <p>
	 * Determine the original bean name, resolving locally defined aliases to canonical names.
	 * @param name the user-specified name
	 * @return the original bean name
	 */
	protected String originalBeanName(String name) {
		// 获取beanName, 规范名称
		// 1. 先去除name开头的'&'字符, 返回剩余的字符串得到转换后的Bean名称;
		// 2. 然后再遍历aliasMap(别名映射到规范名称集合), 得到最终规范名称
		String beanName = transformedBeanName(name);
		// 如果name以"&"开头
		if (name.startsWith(FACTORY_BEAN_PREFIX)) {
			// beanName前再拼接"&"
			beanName = FACTORY_BEAN_PREFIX + beanName;
		}
		// 返回规范的beanName
		return beanName;
	}

	/**
	 * Initialize the given BeanWrapper with the custom editors registered
	 * with this factory. To be called for BeanWrappers that will create
	 * and populate bean instances.
	 * <p>The default implementation delegates to {@link #registerCustomEditors}.
	 * Can be overridden in subclasses.
	 * @param bw the BeanWrapper to initialize
	 */
	protected void initBeanWrapper(BeanWrapper bw) {
		bw.setConversionService(getConversionService());
		registerCustomEditors(bw);
	}

	/**
	 * Initialize the given PropertyEditorRegistry with the custom editors
	 * that have been registered with this BeanFactory.
	 * <p>To be called for BeanWrappers that will create and populate bean
	 * instances, and for SimpleTypeConverter used for constructor argument
	 * and factory method type conversion.
	 * @param registry the PropertyEditorRegistry to initialize
	 */
	protected void registerCustomEditors(PropertyEditorRegistry registry) {
		PropertyEditorRegistrySupport registrySupport =
				(registry instanceof PropertyEditorRegistrySupport ? (PropertyEditorRegistrySupport) registry : null);
		if (registrySupport != null) {
			registrySupport.useConfigValueEditors();
		}
		if (!this.propertyEditorRegistrars.isEmpty()) {
			for (PropertyEditorRegistrar registrar : this.propertyEditorRegistrars) {
				try {
					registrar.registerCustomEditors(registry);
				}
				catch (BeanCreationException ex) {
					Throwable rootCause = ex.getMostSpecificCause();
					if (rootCause instanceof BeanCurrentlyInCreationException) {
						BeanCreationException bce = (BeanCreationException) rootCause;
						String bceBeanName = bce.getBeanName();
						if (bceBeanName != null && isCurrentlyInCreation(bceBeanName)) {
							if (logger.isDebugEnabled()) {
								logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
										"] failed because it tried to obtain currently created bean '" +
										ex.getBeanName() + "': " + ex.getMessage());
							}
							onSuppressedException(ex);
							continue;
						}
					}
					throw ex;
				}
			}
		}
		if (!this.customEditors.isEmpty()) {
			this.customEditors.forEach((requiredType, editorClass) ->
					registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass)));
		}
	}


	/**
	 * 获取beanName合并后的本地RootBeanDefinition
	 * <p>
	 * Return a merged RootBeanDefinition, traversing the parent bean definition
	 * if the specified bean corresponds to a child bean definition.
	 * @param beanName the name of the bean to retrieve the merged definition for
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
		// Quick check on the concurrent map first, with minimal locking.
		// 首先以最小的锁定(ConcurrentHashMap)快速检测并发映射
		// 根据beanName映射到合并的RootBeanDefinition的集合中获取beanName对应的RootBeanDefinition
		RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
		// 如果mbd不为null且不需要重新合并定义
		if (mbd != null && !mbd.stale) {
			return mbd;
		}
		// 先通过父类获取该工厂beanName的BeanDefinition对象
		// 再进行合并, 如果beanName对应的BeanDefinition是子BeanDefinition,
		return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
	}

	/**
	 * 获取beanName对应的合并后的RootBeanDefinition
	 * 调用getMergedBeanDefinition(String, BeanDefinition,BeanDefinition)处理，第三个参数传null
	 *
	 * Return a RootBeanDefinition for the given top-level bean, by merging with
	 * the parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition
	 * @param bd the original bean definition (Root/ChildBeanDefinition)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {

		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * 获取beanName对应的合并后的RootBeanDefinition
	 * <p>
	 * Return a RootBeanDefinition for the given bean, by merging with the
	 * parent if the given bean's definition is a child bean definition.
	 * @param beanName the name of the bean definition
	 * @param bd the original bean definition (Root/ChildBeanDefinition)
	 * @param containingBd the containing bean definition in case of inner bean,
	 * or {@code null} in case of a top-level bean
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, @Nullable BeanDefinition containingBd)
			throws BeanDefinitionStoreException {
		// 从bean名称映射到合并的RootBeanDefinition集合进行加锁
		synchronized (this.mergedBeanDefinitions) {
			// 用于存储bd的MergedBeanDefinition
			RootBeanDefinition mbd = null;
			// 从bean名称映射到合并的RootBeanDefinition集合中取到的mbd且该mbd需要重新合并定义
			RootBeanDefinition previous = null;

			// Check with full lock now in order to enforce the same merged instance.
			// 立即检查完全锁定, 以强制执行相同的合并实例, 如果没有包含bean定义
			if (containingBd == null) {
				// 从bean名称映射到合并的RootBeanDefinition集合中获取beanName对应的BeanDefinition作为mbd
				mbd = this.mergedBeanDefinitions.get(beanName);
			}
			// 如果mbd为null或者mdb需要重新合并定义
			if (mbd == null || mbd.stale) {
				// 将mdn作为previous
				previous = mbd;
				// 如果获取不到原始BeanDefinition的父Bean名
				if (bd.getParentName() == null) {
					// Use copy of given root bean definition.
					// 使用给定的RootBeanDefinition的副本, 如果原始BeanDefinition是RootBeanDefinition对象
					if (bd instanceof RootBeanDefinition) {
						// 克隆一份bd的Bean定义赋值给mdb
						mbd = ((RootBeanDefinition) bd).cloneBeanDefinition();
					}
					else {
						// 创建一个新的RootBeanDefinition作为bd的深层副本并赋值给mbd
						mbd = new RootBeanDefinition(bd);
					}
				}
				else {
					// Child bean definition: needs to be merged with parent.
					// 子bean定义: 需要与父bean合并, 定义一个父级BeanDefinition变量
					BeanDefinition pbd;
					try {
						// 获取bd的父级Bean对应的规范名称
						String parentBeanName = transformedBeanName(bd.getParentName());
						// 如果当前bean名称与父级bean名称不同
						if (!beanName.equals(parentBeanName)) {
							// 获取parentBeanName的"合并的"BeanDefinition赋值给pdb
							pbd = getMergedBeanDefinition(parentBeanName);
						}
						// 如果当前bean名称与父级bean名称相同(只有在存在父BeanFactory的情况下, 否则走不到这里; 就是上边的将自己设置为父定义)
						else {
							// 拿到父BeanFactory
							BeanFactory parent = getParentBeanFactory();
							// 如果父BeanFactory是ConfigurableBeanFactory, 则通过父BeanFactory获取父定义的MergedBeanDefinition
							if (parent instanceof ConfigurableBeanFactory) {
								pbd = ((ConfigurableBeanFactory) parent).getMergedBeanDefinition(parentBeanName);
							}
							else {
								// 如果父工厂不是ConfigurableBeanFactory, 抛出没有此类bean定义异常
								// 父级bean名为parentBeanName等于名为beanName的bean名
								// 没有AbstractBeanFactory父级无法解决
								throw new NoSuchBeanDefinitionException(parentBeanName,
										"Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
										"': cannot be resolved without a ConfigurableBeanFactory parent");
							}
						}
					}
					catch (NoSuchBeanDefinitionException ex) {
						throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
								"Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
					}
					// Deep copy with overridden values.
					// 使用父定义pbd构建一个新的RootBeanDefinition对象
					mbd = new RootBeanDefinition(pbd);
					// 使用原始的BeanDefinition定义信息覆盖父级的定义信息; 具有覆盖值(有效值, 有的需要判空)的深拷贝
					// 1. 给定的信息不为空, 则覆盖: beanClass(Name), scope, factoryBeanName, factoryMethodName;
					// 2. 覆盖给定的信息(不做判空处理): abstractFlag, role, source, attributes;
					// 3. 如果给定的BeanDefinition实现了AbstractBeanDefinition:
					//    3.1 给定的信息不为空, 则覆盖: beanClass(Name), lazyInit, initMethodName, enforceInitMethod,
					//        destroyMethodName, enforceDestroyMethod;
					//    3.2 覆盖给定的信息(不做判空处理): autowireMode, dependencyCheck, dependsOn, autowireCandidate,
					//        primary, instanceSupplier, nonPublicAccessAllowed, lenientConstructorResolution, synthetic,
					//        resource(设置bean定义来自的资源<为了在出现错误时显示上下文>);
					//    3.3 补充(合并): indexedArgumentValues, genericArgumentValues, propertyValueList, overrides, qualifiers;
					// 4. 如果给定的BeanDefinition未实现AbstractBeanDefinition:
					//    4.1 给定的信息不为空, 则覆盖: lazyInit;
					//    4.2 覆盖给定的信息(不做判空处理): this.resource = (resourceDescription != null ? new DescriptiveResource(resourceDescription) : null);
					//    4.3 补充(合并): indexedArgumentValues, genericArgumentValues, propertyValueList;
					mbd.overrideFrom(bd);
				}

				// Set default singleton scope, if not configured before.
				// 设置默认的单例作用域(如果之前未配置)
				if (!StringUtils.hasLength(mbd.getScope())) {
					// singleton
					mbd.setScope(SCOPE_SINGLETON);
				}

				// A bean contained in a non-singleton bean cannot be a singleton itself.
				// Let's correct this on the fly here, since this might be the result of
				// parent-child merging for the outer bean, in which case the original inner bean
				// definition will not have inherited the merged outer bean's singleton status.
				// 给定Bean非顶级 && 给定Bean非单例 && MergedBeanDefinition单例	(容错机制)
				// 包含在非单例bean中的bean本身不能是单例。
				// 让我们在这里纠正这个问题, 因为这可能是外部bean的父子合并的结果, 在这种情况下, 原始内部bean定义将不会继承合并的外部bean的单例状态。
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					// 将mbd的作用域设置为跟containingBd的作用域一样
					mbd.setScope(containingBd.getScope());
				}

				// Cache the merged bean definition for the time being
				// (it might still get re-merged later on in order to pick up metadata changes)
				// 暂时缓存合并的bean定义(稍后可能仍会重新合并以获取元数据更正), 如果没有传入包含bean定义且当前工厂是同意缓存bean元数据
				if (containingBd == null && isCacheBeanMetadata()) {
					// 将beanName和mbd的关系添加到从bean名称映射到合并的RootBeanDefinition集合中
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}
			// 如果存在上一个从bean名称映射到合并的RootBeanDefinition集合中取出的mbd
			if (previous != null) {
				// 用previous来对mdb进行重新合并定义:
				// 1. 设置mbd的目标类型为previous的目标类型
				// 2. 设置mbd的工厂bean标记为previous的工厂bean标记
				// 3. 设置mbd的用于缓存给定bean定义的确定的Class为previous的用于缓存给定bean定义的确定的Class
				// 4. 设置mbd的工厂方法返回类型为previous的工厂方法返回类型
				// 5. 设置mbd的用于缓存用于自省的唯一工厂方法候选为previous的用于缓存用于自省的唯一工厂方法候选
				copyRelevantMergedBeanDefinitionCaches(previous, mbd);
			}
			return mbd;
		}
	}

	/**
	 * 复制相关的合并bean定义缓存(用previous对mdb进行重新合并定义)
	 * @param previous 从bean名称映射到合并的RootBeanDefinition集合中取到的mbd
	 * @param mbd MergedBeanDefinition合并后的Bean定义
	 */
	private void copyRelevantMergedBeanDefinitionCaches(RootBeanDefinition previous, RootBeanDefinition mbd) {
		// 工具类补充: ObjectUtils.nullSafeEquals
		// 确定给定对象是否相等, 如果两者都为null 则返回true; 如果只有一个为null, 则返回false;
		// 将数组与Arrays.equals进行比较, 基于数组元素而不是数组引用执行相等检查
		// mbd与previous的属性比较 -> 对应属性均为空 || (Bean类名称相同 && 工厂Bean名称相同 && 工厂方法名相同)
		if (ObjectUtils.nullSafeEquals(mbd.getBeanClassName(), previous.getBeanClassName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryBeanName(), previous.getFactoryBeanName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryMethodName(), previous.getFactoryMethodName())) {
			// 获取mbd的目标类型
			ResolvableType targetType = mbd.targetType;
			// 获取previous的目标类型
			ResolvableType previousTargetType = previous.targetType;
			// mbd的目标类型为空 || mbd的目标类型与previous的目标类型相同
			if (targetType == null || targetType.equals(previousTargetType)) {
				// 设置mbd的目标类型为previous的目标类型
				mbd.targetType = previousTargetType;
				// 设置mbd的工厂bean标记为previous的工厂bean标记
				mbd.isFactoryBean = previous.isFactoryBean;
				// 设置mbd的用于缓存给定bean定义的确定的Class为previous的用于缓存给定bean定义的确定的Class
				mbd.resolvedTargetType = previous.resolvedTargetType;
				// 设置mbd的工厂方法返回类型为previous的工厂方法返回类型
				mbd.factoryMethodReturnType = previous.factoryMethodReturnType;
				// 设置mbd的用于缓存用于自省的唯一工厂方法候选为previous的用于缓存用于自省的唯一工厂方法候选
				mbd.factoryMethodToIntrospect = previous.factoryMethodToIntrospect;
			}
		}
	}

	/**
	 * 检测当前BeanDefinition是否是抽象的; 如果是抽象的, 那么就抛出异常
	 * Check the given merged bean definition,
	 * potentially throwing validation exceptions.
	 * @param mbd the merged bean definition to check
	 * @param beanName the name of the bean
	 * @param args the arguments for bean creation, if any
	 * @throws BeanDefinitionStoreException in case of validation failure
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object[] args)
			throws BeanDefinitionStoreException {
		// 如果mbd所配置的bean是抽象的
		if (mbd.isAbstract()) {
			// 抛出Bean为抽象异常
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * 将beanName对应的合并后RootBeanDefinition对象标记为重新合并定义
	 * <p>
	 * Remove the merged bean definition for the specified bean,
	 * recreating it on next access.
	 * @param beanName the bean name to clear the merged definition for
	 */
	protected void clearMergedBeanDefinition(String beanName) {
		// 从合并后BeanDefinition集合缓存中获取beanName对应的合并后RootBeanDefinition对象
		RootBeanDefinition bd = this.mergedBeanDefinitions.get(beanName);
		// 如果成功获取到了bd
		if (bd != null) {
			// 将bd标记为需要重新合并定义
			bd.stale = true;
		}
	}

	/**
	 * Clear the merged bean definition cache, removing entries for beans
	 * which are not considered eligible for full metadata caching yet.
	 * <p>Typically triggered after changes to the original bean definitions,
	 * e.g. after applying a {@code BeanFactoryPostProcessor}. Note that metadata
	 * for beans which have already been created at this point will be kept around.
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		this.mergedBeanDefinitions.forEach((beanName, bd) -> {
			if (!isBeanEligibleForMetadataCaching(beanName)) {
				bd.stale = true;
			}
		});
	}

	/**
	 * 为指定的bean定义解析bean类, 将bean类名解析为Class引用(如果需要), 并将解析后的Class存储在bean定义中以备将来使用
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 * @param mbd the merged bean definition to determine the class for
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the resolved bean class (or {@code null} if none)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 */
	@Nullable
	protected Class<?> resolveBeanClass(RootBeanDefinition mbd, String beanName, Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {

		try {
			// 判断mbd的定义信息中是否包含beanClass, 并且是Class类型的; 如果是直接返回, 否则的话进行详细的解析
			if (mbd.hasBeanClass()) {
				// mbd指定的bean类
				return mbd.getBeanClass();
			}
			// 有安全管理器
			if (System.getSecurityManager() != null) {
				// 通过JVM的访问控制器进行权限控制及获取mbd配置的bean类名
				return AccessController.doPrivileged((PrivilegedExceptionAction<Class<?>>)
						// 调用真正干活的方法; 获取mbd配置的bean类名; 进行详细的处理解析过程
						() -> doResolveBeanClass(mbd, typesToMatch), getAccessControlContext());
			}
			else {
				// 调用真正干活的方法; 获取mbd配置的bean类名; 进行详细的处理解析过程
				return doResolveBeanClass(mbd, typesToMatch);
			}
		}
		catch (PrivilegedActionException pae) {
			ClassNotFoundException ex = (ClassNotFoundException) pae.getException();
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (LinkageError err) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
	}

	/**
	 * 真正的干活的方法; 获取mbd配置的bean类名, 将bean类名解析为Class对象, 并将解析后的Class对象缓存在mdb中以备将来使用
	 * @param mbd 根Bean定义
	 * @param typesToMatch 要匹配的类型
	 * @return 解析后的Class对象
	 * @throws ClassNotFoundException Class查找不到的异常
	 */
	@Nullable
	private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {
		// 获取该工厂的加载bean用的类加载器
		ClassLoader beanClassLoader = getBeanClassLoader();
		// 初始化动态类加载器为该工厂的加载bean用的类加载器, 如果该工厂有临时类加载器器时, 该动态类加载器就是该工厂的临时类加载器
		ClassLoader dynamicLoader = beanClassLoader;
		// mdb的配置的bean类名是否需要重新被dynamicLoader加载的标记; 默认不需要
		boolean freshResolve = false;

		// 如果有传入要匹配的类型
		if (!ObjectUtils.isEmpty(typesToMatch)) {
			// When just doing type checks (i.e. not creating an actual instance yet),
			// use the specified temporary class loader (e.g. in a weaving scenario).
			// 仅进行类型检查时(即尚未创建实际实例), 请使用指定的临时类加载器获取该工厂的临时类加载器, 该临时类加载器专门用于类型匹配
			ClassLoader tempClassLoader = getTempClassLoader();
			// 成功获取到临时类加载器
			if (tempClassLoader != null) {
				// 以该工厂的临时类加载器作为动态类加载器
				dynamicLoader = tempClassLoader;
				// 标记mdb的配置的bean类名需要重新被dynamicLoader加载
				freshResolve = true;
				// DecoratingClassLoader: 装饰ClassLoader的基类, 提供对排除的包和类的通用处理
				// 如果临时类加载器是DecoratingClassLoader的基类
				if (tempClassLoader instanceof DecoratingClassLoader) {
					// 将临时类加载器强转为DecoratingClassLoader实例
					DecoratingClassLoader dcl = (DecoratingClassLoader) tempClassLoader;
					// 对要匹配的类型进行在装饰类加载器中的排除, 以交由父ClassLoader以常规方式处理
					for (Class<?> typeToMatch : typesToMatch) {
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}
		// 从mbd中获取配置的bean类名
		String className = mbd.getBeanClassName();
		// 如果能成功获得配置的bean类名
		if (className != null) {
			// 评估beanDefinition中包含的className,如果className是可解析表达式，会对其进行解析，否则直接返回className
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			// className不等于计算出的表达式的结果, 那么判断evaluated的类型
			if (!className.equals(evaluated)) {
				// A dynamically resolved expression, supported as of 4.2...
				// 如果evaluated属于Class实例
				if (evaluated instanceof Class) {
					// 返回强转结果
					return (Class<?>) evaluated;
				}
				// 如果evaluated属于String实例
				else if (evaluated instanceof String) {
					// 将evaluated作为className的值
					className = (String) evaluated;
					// 标记mdb的配置的bean类名需要重新被dynamicLoader加载
					freshResolve = true;
				}
				else {
					// 抛出非法状态异常 [无效的类名表达式结果: evaluated]
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			// mdb的配置的bean类名需要重新被dynamicLoader加载
			if (freshResolve) {
				// When resolving against a temporary class loader, exit early in order
				// to avoid storing the resolved Class in the bean definition.
				// 当使用临时类加载器进行解析时, 请尽早退出以避免将已解析的类存储在BeanDefinition中
				// 如果动态类加载器不为null
				if (dynamicLoader != null) {
					try {
						// 使用dynamicLoader加载className对应的类型, 并返回加载成功的Class对象
						return dynamicLoader.loadClass(className);
					}
					// 捕捉未找到类异常
					catch (ClassNotFoundException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Could not load class [" + className + "] from " + dynamicLoader + ": " + ex);
						}
					}
				}
				// 使用classLoader加载name对应的Class对象,
				// 该方式是Spring用于代替Class.forName()的方法,
				// 支持返回原始的类实例("int")和数组类名("String[]");
				// 此外, 它还能够以Java source样式解析内部类名(如: "java.lang.Thread.State", 而不是"java.lang.Thread$State")
				return ClassUtils.forName(className, dynamicLoader);
			}
		}

		// Resolve regularly, caching the result in the BeanDefinition...
		// 定期解析，将结果缓存在BeanDefinition中...
		// 使用classLoader加载当前BeanDefinition对象所配置的Bean类名的Class对象
		// (每次调用都会重新加载, 可通过AbstractBeanDefinition#getBeanClass获取缓存）
		return mbd.resolveBeanClass(beanClassLoader);
	}

	/**
	 * 通过表达式处理器解析beanDefinition中给定的字符串
	 * <p>
	 * Evaluate the given String as contained in a bean definition,
	 * potentially resolving it as an expression.
	 * @param value the value to check
	 * @param beanDefinition the bean definition that the value comes from
	 * @return the resolved value
	 * @see #setBeanExpressionResolver
	 */
	@Nullable
	protected Object evaluateBeanDefinitionString(@Nullable String value, @Nullable BeanDefinition beanDefinition) {
		// 如果该工厂没有设置bean定义值中表达式的解析策略
		if (this.beanExpressionResolver == null) {
			// 直接返回要检查的值
			return value;
		}
		// 值所来自的bean定义的当前目标作用域
		Scope scope = null;
		// 如果有传入值所来自的bean定义
		if (beanDefinition != null) {
			// 获取值所来自的bean定义的当前目标作用域名
			String scopeName = beanDefinition.getScope();
			// 如果成功获得值所来自的bean定义的当前目标作用域名
			if (scopeName != null) {
				// 获取scopeName对应的Scope对象
				scope = getRegisteredScope(scopeName);
			}
		}
		// 评估value作为表达式(如果适用); 否则按原样返回值
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * 预测mdb所指的bean的最终bean类型(已处理bean实例的类型)
	 * <p>
	 * Predict the eventual bean type (of the processed bean instance) for the
	 * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
	 * Does not need to handle FactoryBeans specifically, since it is only
	 * supposed to operate on the raw bean type.
	 * <p>This implementation is simplistic in that it is not able to
	 * handle factory methods and InstantiationAwareBeanPostProcessors.
	 * It only predicts the bean type correctly for a standard bean.
	 * To be overridden in subclasses, applying more sophisticated type detection.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition to determine the type for
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type of the bean, or {@code null} if not predictable
	 */
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		// 获取mbd的目标类型
		Class<?> targetType = mbd.getTargetType();
		// 如果成功获得mbd的目标类型
		if (targetType != null) {
			// 返回 mbd的目标类型
			return targetType;
		}
		// 如果有设置mbd的工厂方法名
		if (mbd.getFactoryMethodName() != null) {
			// 返回null, 表示不可预测
			return null;
		}
		// 为mbd解析bean类, 将beanName解析为Class引用(如果需要), 并将解析后的Class存储在mbd中以备将来使用
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * 根据名字和bean定义信息判断是否是FactoryBean;
	 * 如果定义本身定义了isFactoryBean, 那么直接返回结果; 否则需要进行类型预测, 会通过反射来判断名字对应的类是否是FactoryBean类型,
	 * 如果是返回true，如果不是返回false
	 *  <p>
	 * Check whether the given bean is defined as a {@link FactoryBean}.
	 * @param beanName the name of the bean
	 * @param mbd the corresponding bean definition
	 */
	protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
		// 定义一个存储mbd是否是FactoryBean的标记
		Boolean result = mbd.isFactoryBean;
		// 如果没有配置mbd的工厂Bean
		if (result == null) {
			// 根据预测指定bean的最终bean类型
			Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
			// 成功获取最终bean类型 && 最终bean类型属于FactoryBean类型
			result = (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
			// 将result缓存在mbd中
			mbd.isFactoryBean = result;
		}
		// 如果有配置mbd的工厂Bean, 直接返回
		return result;
	}

	/**
	 * 获取beanName, mbd所指的FactoryBean要创建的bean类型
	 * <p>
	 * 返回ResolvableType.NONE的情况:<p>
	 * 1. 如果无法通过mbd中的属性名FactoryBean.OBJECT_TYPE_ATTRIBUTE的值来确定类型;<p>
	 * 2. 如果不允许初始化FactoryBean或者mbd不是配置成单例;<p>
	 * 3. 如果允许初始化FactoryBean并且mbd配置了单例, 尝试该beanName的BeanFactory对象来获取factoryBean的创建出来的对象的类型时,
	 * 如果获取失败(没有可用的值);<p>
	 * 4. 在3的情况时, 抛出了尝试从Bean定义创建Bean时BeanFactory遇到错误时引发的异常;<p>
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean
	 * already. The implementation is allowed to instantiate the target factory bean if
	 * {@code allowInit} is {@code true} and the type cannot be determined another way;
	 * otherwise it is restricted to introspecting signatures and related metadata.
	 * <p>If no {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} if set on the bean definition
	 * and {@code allowInit} is {@code true}, the default implementation will create
	 * the FactoryBean via {@code getBean} to call its {@code getObjectType} method.
	 * Subclasses are encouraged to optimize this, typically by inspecting the generic
	 * signature of the factory bean class or the factory method that creates it.
	 * If subclasses do instantiate the FactoryBean, they should consider trying the
	 * {@code getObjectType} method without fully populating the bean. If this fails,
	 * a full FactoryBean creation as performed by this implementation should be used
	 * as fallback.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param allowInit if initialization of the FactoryBean is permitted if the type
	 * cannot be determined another way
	 * @return the type for the bean if determinable, otherwise {@code ResolvableType.NONE}
	 * @since 5.2
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 */
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// 通过检查FactoryBean.OBJECT_TYPE_ATTRIBUTE值的属性来确定FactoryBean的bean类型
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		// 成功获取(得到的bean类型不是ResolvableType.NONE)
		if (result != ResolvableType.NONE) {
			// 返回该Bean类型
			return result;
		}
		// 允许初始化FactoryBean && mbd配置了单例
		if (allowInit && mbd.isSingleton()) {
			try {
				// 获取该beanName的BeanFactory对象
				FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
				// 获取factoryBean的创建出来的对象的类型
				Class<?> objectType = getTypeForFactoryBean(factoryBean);
				// 如果成功得到对象类型就将其封装成ResolvableType对象, 否则返回ResolvableType.NONE(没有可用的值)
				return (objectType != null ? ResolvableType.forClass(objectType) : ResolvableType.NONE);
			}
			// 捕捉尝试从Bean定义创建Bean时BeanFactory遇到错误时引发的异常
			catch (BeanCreationException ex) {
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					logger.trace(LogMessage.format("Bean currently in creation on FactoryBean type check: %s", ex));
				}
				else if (mbd.isLazyInit()) {
					logger.trace(LogMessage.format("Bean creation exception on lazy FactoryBean type check: %s", ex));
				}
				else {
					logger.debug(LogMessage.format("Bean creation exception on eager FactoryBean type check: %s", ex));
				}
				// 调用父类方法, 将要注册的异常对象添加到抑制异常列表中
				onSuppressedException(ex);
			}
		}
		// 返回ResolvableType.NONE的情况:
		// 1. 如果无法通过mbd中的属性名FactoryBean.OBJECT_TYPE_ATTRIBUTE的值来确定类型;
		// 2. 如果不允许初始化FactoryBean或者mbd不是配置成单例;
		// 3. 如果允许初始化FactoryBean并且mbd配置了单例, 尝试该beanName的BeanFactory对象来获取factoryBean的创建出来的对象的类型时,
		// 如果获取失败(没有可用的值);
		// 4. 在3的情况时, 抛出了尝试从Bean定义创建Bean时BeanFactory遇到错误时引发的异常;
		return ResolvableType.NONE;
	}

	/**
	 * 通过检查{@link FactoryBean#OBJECT_TYPE_ATTRIBUTE}值的属性来确定FactoryBean的bean类型
	 * <p>
	 * Determine the bean type for a FactoryBean by inspecting its attributes for a
	 * {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} value.
	 * @param attributes the attributes to inspect
	 * @return a {@link ResolvableType} extracted from the attributes or
	 * {@code ResolvableType.NONE}
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		// 获取FactoryBean.OBJECT_TYPE_ATTRIBUTE(factoryBeanObjectType)在BeanDefinition对象的属性值
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		// 如果属性值是ResolvableType的实例
		if (attribute instanceof ResolvableType) {
			// 强转返回
			return (ResolvableType) attribute;
		}
		// 如果属性值是Class实例
		if (attribute instanceof Class) {
			// 使用ResolvableType封装属性值后返回
			return ResolvableType.forClass((Class<?>) attribute);
		}
		// 其他情况则返回 ResolvableType.NONE
		// 说明未成功获取FactoryBean.OBJECT_TYPE_ATTRIBUTE(factoryBeanObjectType)在BeanDefinition对象的属性值
		return ResolvableType.NONE;
	}

	/**
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean already.
	 * <p>The default implementation creates the FactoryBean via {@code getBean}
	 * to call its {@code getObjectType} method. Subclasses are encouraged to optimize
	 * this, typically by just instantiating the FactoryBean but not populating it yet,
	 * trying whether its {@code getObjectType} method already returns a type.
	 * If no type found, a full FactoryBean creation as performed by this implementation
	 * should be used as fallback.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 * @deprecated since 5.2 in favor of {@link #getTypeForFactoryBean(String, RootBeanDefinition, boolean)}
	 */
	@Nullable
	@Deprecated
	protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
		return getTypeForFactoryBean(beanName, mbd, true).resolve();
	}

	/**
	 * 为指定的Bean标记为已经创建(或将要创建)
	 * <p>
	 * Mark the specified bean as already created (or about to be created).
	 * <p>This allows the bean factory to optimize its caching for repeated
	 * creation of the specified bean.
	 * @param beanName the name of the bean
	 */
	protected void markBeanAsCreated(String beanName) {
		// 如果beanName还没有创建
		if (!this.alreadyCreated.contains(beanName)) {
			// 锁mergedBenDefinitions, 保证线程安全
			synchronized (this.mergedBeanDefinitions) {
				// 双重检查
				if (!this.alreadyCreated.contains(beanName)) {
					// Let the bean definition get re-merged now that we're actually creating
					// the bean... just in case some of its metadata changed in the meantime.
					// 清除合并的Bean定义(将beanName对应的合并后RootBeanDefinition对象标记为重新合并定义)
					clearMergedBeanDefinition(beanName);
					// 将该beanName添加到至少已经创建一次的bean名称的集合里
					this.alreadyCreated.add(beanName);
				}
			}
		}
	}

	/**
	 * 在Bean创建失败后, 对缓存的元数据执行适当的清理
	 * <p>
	 * Perform appropriate cleanup of cached metadata after bean creation failed.
	 * @param beanName the name of the bean
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		// 锁"从bean名称映射到合并的RootBeanDefinition", 保证线程安全
		synchronized (this.mergedBeanDefinitions) {
			// 将此beanName在至少已经创建一次的bean名称的集合中移除
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * Determine whether the specified bean is eligible for having
	 * its bean definition metadata cached.
	 * @param beanName the name of the bean
	 * @return {@code true} if the bean's metadata may be cached
	 * at this point already
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * Remove the singleton instance (if any) for the given bean name,
	 * but only if it hasn't been used for other purposes than type checking.
	 * @param beanName the name of the bean
	 * @return {@code true} if actually removed, {@code false} otherwise
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			removeSingleton(beanName);
			return true;
		}
		else {
			return false;
		}
	}

	/**
	 * Check whether this factory's bean creation phase already started,
	 * i.e. whether any bean has been marked as created in the meantime.
	 * @since 4.2.2
	 * @see #markBeanAsCreated
	 */
	protected boolean hasBeanCreationStarted() {
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * 从beanInstance中获取公开的Bean对象, 主要处理beanInstance是FactoryBean对象的情况, 如果不是FactoryBean会直接返回beanInstance实例
	 * <p>
	 * Get the object for the given bean instance, either the bean
	 * instance itself or its created object in case of a FactoryBean.
	 * @param beanInstance the shared bean instance
	 * @param name the name that may include factory dereference prefix
	 * @param beanName the canonical bean name
	 * @param mbd the merged bean definition
	 * @return the object to expose for the bean
	 */
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// Don't let calling code try to dereference the factory if the bean isn't a factory.
		// 如果Bean不是工厂, 不要让调用代码尝试取消对工厂的引用
		// 如果有传入bean名且bean名是以'&'开头, 则返回true, 表示是BeanFactory的解引用
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			// 如果beanInstance是NullBean实例
			if (beanInstance instanceof NullBean) {
				// 返回beanInstance
				return beanInstance;
			}
			// 如果beanInstance不是FactoryBean实例
			if (!(beanInstance instanceof FactoryBean)) {
				// 抛出Bean不是一个Factory异常
				throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
			}
			// 如果mbd不为null
			if (mbd != null) {
				// 设置mbd是否是FactoryBean标记为true
				mbd.isFactoryBean = true;
			}
			// 返回beanInstance
			return beanInstance;
		}

		// Now we have the bean instance, which may be a normal bean or a FactoryBean.
		// If it's a FactoryBean, we use it to create a bean instance, unless the
		// caller actually wants a reference to the factory.
		// 现在我们有了Bean实例, 他可能是一个普通的Bean或FactoryBean.
		// 如果它是FactoryBean, 我们使用它来创建一个Bean实例, 除非调用者确实需要对工厂的引用.
		// 如果beanInstance不是FactoryBean实例
		if (!(beanInstance instanceof FactoryBean)) {
			// 直接返回
			return beanInstance;
		}
		// 定义为bean公开的对象, 初始化为null
		Object object = null;
		// 如果mbd不为null
		if (mbd != null) {
			// 更新mbd的是否是FactoryBean标记为true
			mbd.isFactoryBean = true;
		}
		else {
			// 从FactoryBean获得的对象缓存集中获取beanName对应的Bean对象
			object = getCachedObjectForFactoryBean(beanName);
		}
		// 如果object为null
		if (object == null) {
			// Return bean instance from factory.
			// 将形参beanInstance(共享bean实例)强转成FactoryBean
			FactoryBean<?> factory = (FactoryBean<?>) beanInstance;
			// Caches object obtained from FactoryBean if it is a singleton.
			// 如果是单例, 则缓存从FactoryBean获得的对象
			// mbd为null && 该BeanFactory包含beanName的BeanDefinition对象
			if (mbd == null && containsBeanDefinition(beanName)) {
				// 获取beanName合并后的本地RootBeanDefinition对象
				mbd = getMergedLocalBeanDefinition(beanName);
			}
			// mbd不为null && 返回此bean定义是"synthetic"(一般是指只有AOP相关的pointCut配置或者Advice配置才会将synthetic设置为true)
			boolean synthetic = (mbd != null && mbd.isSynthetic());
			// 从BeanFactory对象中获取管理的对象.如果不是synthetic会对其对象进行该工厂的后置处理
			object = getObjectFromFactoryBean(factory, beanName, !synthetic);
		}
		// 返回bean公开的对象
		return object;
	}

	/**
	 * Determine whether the given bean name is already in use within this factory,
	 * i.e. whether there is a local bean or alias registered under this name or
	 * an inner bean created with this name.
	 * @param beanName the name to check
	 */
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * Determine whether the given bean requires destruction on shutdown.
	 * <p>The default implementation checks the DisposableBean interface as well as
	 * a specified destroy method and registered DestructionAwareBeanPostProcessors.
	 * @param bean the bean instance to check
	 * @param mbd the corresponding bean definition
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		return (bean.getClass() != NullBean.class &&
				(DisposableBeanAdapter.hasDestroyMethod(bean, mbd) || (hasDestructionAwareBeanPostProcessors() &&
						DisposableBeanAdapter.hasApplicableProcessors(bean, getBeanPostProcessors()))));
	}

	/**
	 * Add the given bean to the list of disposable beans in this factory,
	 * registering its DisposableBean interface and/or the given destroy method
	 * to be called on factory shutdown (if applicable). Only applies to singletons.
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 * @param mbd the bean definition for the bean
	 * @see RootBeanDefinition#isSingleton
	 * @see RootBeanDefinition#getDependsOn
	 * @see #registerDisposableBean
	 * @see #registerDependentBean
	 */
	protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
		AccessControlContext acc = (System.getSecurityManager() != null ? getAccessControlContext() : null);
		if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
			if (mbd.isSingleton()) {
				// Register a DisposableBean implementation that performs all destruction
				// work for the given bean: DestructionAwareBeanPostProcessors,
				// DisposableBean interface, custom destroy method.
				registerDisposableBean(beanName,
						new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
			}
			else {
				// A bean with a custom scope...
				Scope scope = this.scopes.get(mbd.getScope());
				if (scope == null) {
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
				scope.registerDestructionCallback(beanName,
						new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
			}
		}
	}


	//---------------------------------------------------------------------
	// Abstract methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * 该BeanFactory是否包含beanName的BeanDefinition对象.
	 * 不考虑工厂可能参与的任何层次结构.
	 * 未找到缓存的单例实例时，由{@code containsBean}调用
	 * <p>
	 * Check if this bean factory contains a bean definition with the given name.
	 * Does not consider any hierarchy this factory may participate in.
	 * Invoked by {@code containsBean} when no cached singleton instance is found.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to look for
	 * @return if this bean factory contains a bean definition with the given name
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * Return the bean definition for the given bean name.
	 * Subclasses should normally implement caching, as this method is invoked
	 * by this class every time bean definition metadata is needed.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * @param beanName the name of the bean to find a definition for
	 * @return the BeanDefinition for this prototype name (never {@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException
	 * if the bean definition cannot be resolved
	 * @throws BeansException in case of errors
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * 为给定的合并后BeanDefinition(和参数)创建一个bean实例
	 * <p>
	 * Create a bean instance for the given merged bean definition (and arguments).
	 * The bean definition will already have been merged with the parent definition
	 * in case of a child definition.
	 * <p>All bean retrieval methods delegate to this method for actual bean creation.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param args explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 */
	protected abstract Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException;

}
