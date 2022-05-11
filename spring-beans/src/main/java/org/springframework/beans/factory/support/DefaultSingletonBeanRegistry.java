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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** Maximum number of suppressed exceptions to preserve. */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/**
	 * 一级缓存;
	 * 用于保存BeanName和创建Bean实例之间的关系
	 * <p>
	 * Cache of singleton objects: bean name to bean instance.
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/**
	 * 三级缓存;
	 * 用于保存BeanName和创建Bean工厂之间的关系
	 * <p>
	 * Cache of singleton factories: bean name to ObjectFactory.
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/**
	 * 二级缓存;
	 * 保存BeanName和创建Bean实例之间的关系;
	 * 与SingletonFactories的不同之处在于, 当一个单例bean被放到这里之后,
	 * 那么当bean还在创建过程中就可以通过getBean方法获取到，可以方便进行循环依赖的检测
	 * <p>
	 * Cache of early singleton objects: bean name to bean instance.
	 */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/**
	 * 用来保存当前所有已经注册的单例的bean
	 * <p>
	 * Set of registered singletons, containing the bean names in registration order.
	 */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/**
	 * 正在创建过程中的beanName集合
	 * <p>
	 * Names of beans that are currently in creation.
	 */
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * 当前在创建检查中排除的bean名
	 * <p>
	 * Names of beans currently excluded from in creation checks.
	 */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * 抑制的异常列表, 可用于关联相关原因
	 * <p>
	 * Collection of suppressed Exceptions, available for associating related causes.
	 */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/**
	 * 指示我们当前是否在destroySingletons中的标志
	 * <p>
	 * Flag that indicates whether we're currently within destroySingletons.
	 */
	private boolean singletonsCurrentlyInDestruction = false;

	/**
	 * 一次性Bean实例: bean名称 -> DisposableBean实例
	 * <p>
	 * Disposable bean instances: bean name to disposable instance.
	 */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/**
	 * 在包含的Bean名称之间映射: bean名称 -> Bean包含的Bean名称集
	 * <p>
	 * Map between containing bean names: bean name to Set of bean names that the bean contains.
	 */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/**
	 * 存储bean名到该bean名所要依赖的bean名的Map(换句话讲就是: 记录一个bean被多少bean依赖): bean名称 -> Bean包含的Bean名称集
	 * <p>
	 * 被@Service、@Component注解修饰
	 * <p>
	 * Map between dependent bean names: bean name to Set of dependent bean names.
	 */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/**
	 * 存储bean名到依赖于该bean名的bean名的Map(换句话讲就是: 记录一个bean依赖了多少bean): bean名称 -> Bean包含的Bean名称集
	 * <p>
	 * 被@Autowired、@Resource修饰
	 * <p>
	 * Map between depending bean names: bean name to Set of bean names for the bean's dependencies.
	 */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);

	/**
	 * 注册单例对象的入口, 根据名称注册到全局的缓存当中
	 * @param beanName the name of the bean
	 * @param singletonObject the existing singleton object
	 * @throws IllegalStateException 非法状态异常
	 */
	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		// 锁"this.singletonObjects", 这是一个map, 存储所有的单例对象; 保证线程安全
		synchronized (this.singletonObjects) {
			// 根据名称在单例的map当中获取对象
			Object oldObject = this.singletonObjects.get(beanName);
			// 如果对象不为空
			if (oldObject != null) {
				// 返回状态异常: 不能注册对象[singletonObject], 在bean名'beanName'下, 已经有对象[oldObject]
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			// 将beanName和singletonObject的映射关系添加到该工厂的单例缓存中
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * 将beanName和singletonObject的映射关系添加到该工厂的单例缓存中<p>
	 *
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		// 添加单例缓存map过程当中, 将该缓存加锁, 保证线程安全
		synchronized (this.singletonObjects) {
			// 将映射关系添加到单例对象的高速缓存中(一级缓存, ConcurrentHashMap)
			this.singletonObjects.put(beanName, singletonObject);
			// 移除beanName在单例工厂缓存中的数据(三级缓存, HashMap)
			this.singletonFactories.remove(beanName);
			// 移除beanName在早期单例对象的高速缓存的数据(二级缓存, ConcurrentHashMap)
			this.earlySingletonObjects.remove(beanName);
			// 将beanName添加到已注册的单例集中(LinkedHashSet)
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	/**
	 * 获取beanName的单例对象, 并允许创建早期引用
	 * @param beanName the name of the bean to look for
	 * @return 返回beanName对应的单例对象
	 */
	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		// 获取beanName的单例对象, 并允许创建早期引用
		return getSingleton(beanName, true);
	}

	/**
	 * 返回在给定名称下注册的(原始)单例对象; 检查已经实例化的单例, 并允许提前引用当前创建的单例(不允许创建引用, 解决循环引用)<p>
	 * 查找规则:<p>
	 * 1. 先从一级缓存中查找bean, 如果没有, 从二级缓存获取;<p>
	 * 2. 如果二级缓存也没有, 从三级缓存中获取singletonFactory并创建实例放入二级缓存中;<p>
	 * 3. 最后从三级缓存中移除.<p>
	 *
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not
	 * @return the registered singleton object, or {@code null} if none found
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		// Quick check for existing instance without full singleton lock
		// 从单例对象缓存(一级缓存)中获取beanName对应的单例对象
		Object singletonObject = this.singletonObjects.get(beanName);
		// 如果单例对象缓存中没有, 并且该beanName对应的单例bean正在创建中
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			// 从早期单例对象缓存(二级缓存)中获取单例对象
			// (之所称成为早期单例对象，是因为earlySingletonObjects里的对象的都是通过提前曝光的ObjectFactory创建出来的, 还未进行属性填充等操作）
			singletonObject = this.earlySingletonObjects.get(beanName);
			// 如果在早期单例对象缓存(二级缓存)中也没有, 并且允许创建早期单例对象引用
			if (singletonObject == null && allowEarlyReference) {
				// 锁一级缓存, 防止期间有别的线程更新
				synchronized (this.singletonObjects) {
					// Consistent creation of early reference within full singleton lock
					// 从单例对象缓存(一级缓存)中获取beanName对应的单例对象; 用于下方的双重检查
					singletonObject = this.singletonObjects.get(beanName);
					// 双重检查(标准的线程安全的单例写法)
					if (singletonObject == null) {
						// 再从早期单例对象缓存(二级缓存)中获取单例对象
						singletonObject = this.earlySingletonObjects.get(beanName);
						// 如果还是为空, 下方就是实例化过程了
						if (singletonObject == null) {
							// 当某些方法需要提前初始化的时候则会调用addSingletonFactory方法将对应的ObjectFactory初始化策略存储在singletonFactories
							// 其实就是在三级缓存中取出
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							// 如果单例工厂(三级缓存)不为空
							if (singletonFactory != null) {
								// 通过工厂创建一个单例对象
								singletonObject = singletonFactory.getObject();
								// 记录在二级缓存中，二级缓存和三级缓存的对象不能同时存在
								this.earlySingletonObjects.put(beanName, singletonObject);
								// 从三级缓存中移除
								this.singletonFactories.remove(beanName);
							}
						}
					}
				}
			}
		}
		return singletonObject;
	}

	/**
	 * 返回以给定名称注册的(原始)单例对象; 如果尚未注册, 则创建并注册一个新对象<p>
	 *
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		// 建言, beanName不能为空
		Assert.notNull(beanName, "Bean name must not be null");
		// 锁一级缓存, 防止期间有别的线程更新
		synchronized (this.singletonObjects) {
			// 从一级缓存中获取
			Object singletonObject = this.singletonObjects.get(beanName);
			// 如果为空
			if (singletonObject == null) {
				// 如果当前是在destroySingletons中
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				// 创建单例之前的回调, 默认实现将单例注册为当前正在创建中
				beforeSingletonCreation(beanName);
				// 生成了新的单例对象的标记, 默认为false; 表示没有生成新的单例对象
				boolean newSingleton = false;
				// 有抑制异常记录标记, 没有时为true, 否则为false
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				// 如果没有抑制异常记录
				if (recordSuppressedExceptions) {
					// 对抑制的异常列表进行实例化(LinkedHashSet)
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					// 尝试在三级缓存中获取
					singletonObject = singletonFactory.getObject();
					// 获取成功的话, 生成了新的单例对象的标记置为true
					newSingleton = true;
				}
				// 捕获非法状态异常(单例对象隐式出现)
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					// 在一级缓存中获取
					singletonObject = this.singletonObjects.get(beanName);
					// 如果获取失败, 抛出异常
					if (singletonObject == null) {
						throw ex;
					}
				}
				// 捕捉Bean创建异常
				catch (BeanCreationException ex) {
					// 如果没有抑制异常记录
					if (recordSuppressedExceptions) {
						// 遍历抑制的异常列表
						for (Exception suppressedException : this.suppressedExceptions) {
							// 将抑制的异常对象添加到bean创建异常中; 这样做就是相当于"因XXX异常导致了Bean创建异常"
							ex.addRelatedCause(suppressedException);
						}
					}
					// 抛出异常
					throw ex;
				}
				// 最后
				finally {
					// 如果没有抑制异常记录
					if (recordSuppressedExceptions) {
						// 将抑制的异常列表置为null(suppressedExceptions是对应单个bean的异常记录)
						this.suppressedExceptions = null;
					}
					// 创建单例后的回调; 默认实现将单例标记为不在创建中
					afterSingletonCreation(beanName);
				}
				// 生成了新的单例对象
				if (newSingleton) {
					// 将beanName和singletonObject的映射关系添加到该工厂的单例缓存中
					addSingleton(beanName, singletonObject);
				}
			}
			// 返回该单例对象
			return singletonObject;
		}
	}

	/**
	 * 将要注册的异常对象添加到抑制异常列表中; 列表最大长度是100({@code SUPPRESSED_EXCEPTIONS_LIMIT}),
	 * 注意抑制异常列表{@link BeanCreationException}是Set集合
	 * <p>
	 * Register an exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>The default implementation preserves any given exception in this registry's
	 * collection of suppressed exceptions, up to a limit of 100 exceptions, adding
	 * them as related causes to an eventual top-level {@link BeanCreationException}.
	 * @param ex the Exception to register
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		// 锁一级缓存, 保证线程安全
		synchronized (this.singletonObjects) {
			// 抑制异常列表不为null && 抑制异常列表数量小于最大值(SUPPRESSED_EXCEPTIONS_LIMIT=100)
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				// 将要注册的异常对象添加到抑制异常列表中
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * 从该工厂缓存(一级、二级、三级……)中删除具有给定名称的Bean;
	 * 如果创建失败, 则能够清理饿汉式注册的单例
	 * <p>
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		// 锁一级缓存, 保证线程安全
		synchronized (this.singletonObjects) {
			// 一级缓存中删除对应的bean
			this.singletonObjects.remove(beanName);
			// 三级缓存中删除对应的beanFactory
			this.singletonFactories.remove(beanName);
			// 二级缓存中删除对应的bean
			this.earlySingletonObjects.remove(beanName);
			// 已注册的单例集中删除对应的beanName
			this.registeredSingletons.remove(beanName);
		}
	}

	/**
	 * 判断beanName是否在该BeanFactory的单例对象的高速缓存Map(一级缓存)集合中
	 * @param beanName the name of the bean to look for
	 * @return 返回beanName是否在该BeanFactory的单例对象的高速缓存Map集合中
	 */
	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * 返回指定的单例bean当前是否正在创建(在整个工厂内)
	 * <p>
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		// 从当前正在创建的bean名称set集合中判断beanName是否在集合中
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * 创建单例之前的回调<p>
	 * <p>
	 * 回调条件: <p>
	 * 1. 如果当前在创建检查中的排除bean名列表(inCreationCheckExclusions)中不包含该beanName<p>
	 * 2. 将beanName添加到当前正在创建的bean名称列表(singletonsCurrentlyInCreation)成功<p>
	 * {@link Set#add(Object)} -> 返回值: 如果此集合尚未包含指定元素, 则为true	<p>
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			// 抛出当前正在创建的Bean异常
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * 创建单例后的回调<p>
	 * 默认实现将单例标记为不在创建中<p>
	 * <p>
	 * 回调条件: <p>
	 * 1. 如果当前在创建检查中的排除bean名列表(inCreationCheckExclusions)中不包含该beanName<p>
	 * 2. 将beanName不在当前正在创建的bean名称列表(singletonsCurrentlyInCreation)中<p>
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			// 抛出非法状态异常: 单例beanName不在当前创建中
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * 注册beanName与dependentBeanNamed的依赖关系
	 * <p>
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		// 获取name的最终别名或者是全类名
		String canonicalName = canonicalName(beanName);
		// 锁存储bean名到该bean名所要依赖的bean名的Map, 保证线程安全
		synchronized (this.dependentBeanMap) {
			// 获取canonicalName对应的用于存储依赖Bean名的Set集合, 如果没有就创建一个LinkedHashSet, 并与canonicalName绑定到dependentBeans中
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			// 如果dependentBeans已经添加过来了dependentBeanName, 就结束该方法, 不执行后面操作
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}
		// 锁存储bean名到依赖于该bean名的bean名的Map, 保证线程安全
		synchronized (this.dependenciesForBeanMap) {
			//添加dependentBeanName依赖于canonicalName的映射关系到存储bean名到依赖于该bean名的bean名的Map中
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * 判断beanName是否已注册依赖于dependentBeanName的关系
	 * <p>
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		// 锁存储bean名到该bean名所要依赖的bean名的Map, 保证线程安全
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	/**
	 * 确定指定的依赖bean是否已注册为依赖于给定bean或其任何传递依赖
	 * @param beanName bean名称
	 * @param dependentBeanName 依赖Bean名称
	 * @param alreadySeen 已经查看过的集合
	 * @return 是否注册为依赖于给定bean或其任何传递依赖
	 */
	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		// 如果alreadySeen已经包含该beanName, 直接返回false, 表示不依赖
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		// 获取beanName的最终别名或者是全类名
		String canonicalName = canonicalName(beanName);
		// 从依赖bean关系Map中获取canonicalName的依赖bean名集合
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		// 未获取到依赖bean名, 返回false, 表示不依赖
		if (dependentBeans == null) {
			return false;
		}
		// 如果依赖bean名中包含dependentBeanName, 返回true, 表示是依赖
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		// 遍历依赖Bean名集合
		for (String transitiveDependency : dependentBeans) {
			// 如果alreadySeen为null, 就实例化一个新的HashSet
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			// 将beanName添加到alreadySeen中
			alreadySeen.add(beanName);
			// 递归调用, 检查dependentBeanName是否依赖transitiveDependency, 是就返回true
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		// 返回false, 表示不依赖
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		clearSingletonCache();
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * 销毁给定的bean; 如果找到相应的一次性Bean实例, 则委托给{@code destroyBean}
	 * <p>
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		// 缓存中删除给定名称的已注册的单例(如果有)
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		// DisposableBean: 要在销毁时释放资源的bean所实现的接口. 包括已注册为一次性的内部bean.
		// 销毁相应的DisposableBean实例
		DisposableBean disposableBean;
		// 锁一次性Bean实例, 保证线程安全
		synchronized (this.disposableBeans) {
			// 从disposableBeans移除出disposableBean对象
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);
	}

	/**
	 * 销毁给定bean, 必须先销毁依赖于给定bean的bean, 然后再销毁bean, 不应抛出任何异常
	 * <p>
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		// 首先触发销毁依赖的bean
		Set<String> dependencies;
		// 锁"记录一个bean被多少bean依赖", 保证线程安全
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			// 从dependentBeanMap中移除出beanName对应的依赖beanName集
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		// 移除成功
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			// 遍历
			for (String dependentBeanName : dependencies) {
				// 销毁依赖该bean的bean(destroySingleton里还会再调回该destroyBean方法, 所以此处是递归调用)
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		// 当前bean的销毁
		if (bean != null) {
			try {
				// 调用该bean的销毁方法
				bean.destroy();
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// Trigger destruction of contained beans...
		// 触发销毁所包含的bean
		Set<String> containedBeans;
		// 锁"在包含的Bean名称之间映射", 保证线程安全
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			// 从dependentBeanMap中移除出beanName对应的依赖beanName集
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		// 移除成功
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				// 销毁与该bean有映射关系的bean(destroySingleton里还会再调回该destroyBean方法, 所以此处是递归调用)
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		// 从其他bean的依赖项中删除需要销毁的bean
		// 锁"记录一个bean被多少bean依赖", 保证线程安全
		synchronized (this.dependentBeanMap) {
			// 遍历dependentBeanMap中的元素
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				// 取出依赖该bean的集合
				Set<String> dependenciesToClean = entry.getValue();
				// 在依赖该bean的集合中移除该bean
				dependenciesToClean.remove(beanName);
				// 依赖该bean的集合为空
				if (dependenciesToClean.isEmpty()) {
					// 将整个映射关系都删除
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		// 在当前bean依赖的bean集合中移除该bean(映射关系)
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
