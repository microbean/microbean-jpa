/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2018–2019 microBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jpa.weld;

import java.lang.annotation.Annotation;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import java.util.function.Supplier;

import javax.enterprise.inject.literal.NamedLiteral;

import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.InjectionPoint;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.PersistenceUnit;
import javax.persistence.SynchronizationType;

import javax.persistence.spi.PersistenceProvider;
import javax.persistence.spi.PersistenceUnitInfo;

import org.jboss.weld.manager.api.ExecutorServices;
import org.jboss.weld.manager.api.WeldManager;

import org.jboss.weld.injection.spi.ResourceReference;
import org.jboss.weld.injection.spi.ResourceReferenceFactory;

import org.microbean.development.annotation.Issue;

import static javax.persistence.spi.PersistenceUnitTransactionType.RESOURCE_LOCAL;

/**
 * A {@link org.jboss.weld.injection.spi.JpaInjectionServices}
 * implementation that integrates JPA functionality into Weld-based
 * CDI environments.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see org.jboss.weld.injection.spi.JpaInjectionServices
 */
@Issue(id = "WELD-2563", uri = "https://issues.jboss.org/browse/WELD-2563")
public final class JpaInjectionServices implements org.jboss.weld.injection.spi.JpaInjectionServices {


  /*
   * Static fields.
   */


  /*
   * Weld instantiates this class three times during normal execution
   * (see https://issues.jboss.org/browse/WELD-2563 for details).
   * Only one of those instances (the first) is actually used to
   * produce EntityManagers and EntityManagerFactories; the other two
   * are discarded.  The static INSTANCE and UNDERWAY fields ensure
   * that truly only one instance processes all incoming calls, and
   * that it is the one that is actually tracked and stored by Weld
   * itself in the return value of the WeldManager#getServices()
   * method.
   *
   * See the underway() method as well.
   */

  /**
   * The single officially sanctioned instance of this class.
   *
   * <p>This field may be {@code null}.</p>
   */
  @Issue(id = "WELD_2563", uri = "https://issues.jboss.org/browse/WELD-2563")
  static volatile JpaInjectionServices INSTANCE;

  @Issue(id = "WELD_2563", uri = "https://issues.jboss.org/browse/WELD-2563")
  private static volatile boolean UNDERWAY;


  /*
   * Instance fields.
   */


  private final Set<EntityManager> ems;

  // @GuardedBy("this")
  private volatile Map<String, EntityManagerFactory> emfs;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link JpaInjectionServices}.
   */
  public JpaInjectionServices() {
    super();
    synchronized (JpaInjectionServices.class) {
      // See https://issues.jboss.org/browse/WELD-2563.  Make sure
      // only the first instance is "kept" as it's the one tracked by
      // WeldManager's ServiceRegistry.  The others are discarded.
      if (INSTANCE == null) {
        assert !UNDERWAY;
        INSTANCE = this;
      } else if (UNDERWAY) {
        throw new IllegalStateException();
      }
    }
    this.ems = ConcurrentHashMap.newKeySet();
  }

  @Issue(id = "WELD_2563", uri = "https://issues.jboss.org/browse/WELD-2563")
  private static synchronized final void underway() {
    UNDERWAY = true;
  }

  /**
   * Called by the ({@code private}) {@code
   * JpaInjectionServicesExtension} class when a JTA transaction is
   * begun.
   *
   * <p>The Narayana CDI integration this class is often deployed with
   * will fire such events.  These events serve as an indication that
   * a call to {@link TransactionManager#begin()} has been made.</p>
   *
   * <p>{@link EntityManager}s created by this class will have their
   * {@link EntityManager#joinTransaction()} methods called if the
   * supplied object is non-{@code null}.</p>
   */
  final void jtaTransactionBegun() {
    assert this == INSTANCE;
    this.ems.forEach(em -> em.joinTransaction());
  }

  /**
   * Returns a {@link ResourceReferenceFactory} whose {@link
   * ResourceReferenceFactory#createResource()} method will be invoked
   * appropriately by Weld later.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * @param injectionPoint the {@link InjectionPoint} annotated with
   * {@link PersistenceContext}; must not be {@code null}
   *
   * @return a non-{@code null} {@link ResourceReferenceFactory} whose
   * {@link ResourceReferenceFactory#createResource()} method will
   * create {@link EntityManager} instances
   *
   * @exception NullPointerException if {@code injectionPoint} is
   * {@code null}
   *
   * @see ResourceReferenceFactory#createResource()
   */
  @Override
  public final ResourceReferenceFactory<EntityManager> registerPersistenceContextInjectionPoint(final InjectionPoint injectionPoint) {
    underway();
    assert this == INSTANCE;
    final ResourceReferenceFactory<EntityManager> returnValue;
    Objects.requireNonNull(injectionPoint);
    final Annotated annotatedMember = injectionPoint.getAnnotated();
    assert annotatedMember != null;
    final PersistenceContext persistenceContextAnnotation = annotatedMember.getAnnotation(PersistenceContext.class);
    if (persistenceContextAnnotation == null) {
      throw new IllegalArgumentException("injectionPoint.getAnnotated().getAnnotation(PersistenceContext.class) == null");
    }
    final String name;
    final String n = persistenceContextAnnotation.unitName();
    if (n.isEmpty()) {
      if (annotatedMember instanceof AnnotatedField) {
        name = ((AnnotatedField<?>)annotatedMember).getJavaMember().getName();
      } else {
        name = n;
      }
    } else {
      name = n;
    }
    final SynchronizationType synchronizationType = persistenceContextAnnotation.synchronization();
    assert synchronizationType != null;
    synchronized (this) {
      if (this.emfs == null) {
        this.emfs = new ConcurrentHashMap<>();
      }
    }
    returnValue = () -> new EntityManagerResourceReference(name, synchronizationType);
    return returnValue;
  }

  /**
   * Returns a {@link ResourceReferenceFactory} whose {@link
   * ResourceReferenceFactory#createResource()} method will be invoked
   * appropriately by Weld later.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * @param injectionPoint the {@link InjectionPoint} annotated with
   * {@link PersistenceUnit}; must not be {@code null}
   *
   * @return a non-{@code null} {@link ResourceReferenceFactory} whose
   * {@link ResourceReferenceFactory#createResource()} method will
   * create {@link EntityManagerFactory} instances
   *
   * @exception NullPointerException if {@code injectionPoint} is
   * {@code null}
   *
   * @see ResourceReferenceFactory#createResource()
   */
  @Override
  public final ResourceReferenceFactory<EntityManagerFactory> registerPersistenceUnitInjectionPoint(final InjectionPoint injectionPoint) {
    underway();
    assert this == INSTANCE;
    final ResourceReferenceFactory<EntityManagerFactory> returnValue;
    Objects.requireNonNull(injectionPoint);
    final Annotated annotatedMember = injectionPoint.getAnnotated();
    assert annotatedMember != null;
    final PersistenceUnit persistenceUnitAnnotation = annotatedMember.getAnnotation(PersistenceUnit.class);
    if (persistenceUnitAnnotation == null) {
      throw new IllegalArgumentException("injectionPoint.getAnnotated().getAnnotation(PersistenceUnit.class) == null");
    }
    final String name;
    final String n = persistenceUnitAnnotation.unitName();
    if (n.isEmpty()) {
      if (annotatedMember instanceof AnnotatedField) {
        name = ((AnnotatedField<?>)annotatedMember).getJavaMember().getName();
      } else {
        name = n;
      }
    } else {
      name = n;
    }
    synchronized (this) {
      if (this.emfs == null) {
        this.emfs = new ConcurrentHashMap<>();
      }
    }
    returnValue = () -> new EntityManagerFactoryResourceReference(this.emfs, name);
    return returnValue;
  }

  /**
   * Invoked by Weld automatically to clean up any resources held by
   * this class.
   */
  @Override
  public final void cleanup() {
    // cleanup() can get invoked multiple times at will by Weld.
    // Specifically, the same Service instance can be stored in
    // multiple BeanManagerImpls, and each one can call its cleanup()
    // method, so it must be idempotent.
    //
    // See
    // https://github.com/weld/core/blob/06fcaf4a6f625f101be5804208c1eb3a32884773/impl/src/main/java/org/jboss/weld/Container.java#L143-L145
    // and
    // https://github.com/weld/core/blob/06fcaf4a6f625f101be5804208c1eb3a32884773/impl/src/main/java/org/jboss/weld/manager/BeanManagerImpl.java#L1173.
    if (UNDERWAY) {
      assert this == INSTANCE;

      // this.ems should be empty already.  If for some reason it is
      // not, we just clear() it (rather than, say, calling em.close()
      // on each element).  This is for two reasons: one, we're being
      // cleaned up so the whole container is going down anyway.  Two,
      // it is forbidden by JPA's contract to call close() on a
      // container-managed EntityManager...which is the only kind of
      // EntityManager placed in this collection.
      this.ems.clear();

      final Map<? extends String, ? extends EntityManagerFactory> emfs = this.emfs;
      if (emfs != null && !emfs.isEmpty()) {
        final Collection<? extends EntityManagerFactory> values = emfs.values();
        assert values != null;
        assert !values.isEmpty();
        final Iterator<? extends EntityManagerFactory> iterator = values.iterator();
        assert iterator != null;
        assert iterator.hasNext();
        while (iterator.hasNext()) {
          final EntityManagerFactory emf = iterator.next();
          assert emf != null;
          if (emf.isOpen()) {
            emf.close();
          }
          iterator.remove();
        }
      }
    }
    assert this.ems.isEmpty();
    assert this.emfs == null || this.emfs.isEmpty();
    synchronized (JpaInjectionServices.class) {
      UNDERWAY = false;
      INSTANCE = null;
    }
  }

  /**
   * Calls the {@link
   * #registerPersistenceContextInjectionPoint(InjectionPoint)} method
   * and invokes {@link ResourceReference#getInstance()} on its return
   * value and returns the result.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * @param injectionPoint an {@link InjectionPoint} annotated with
   * {@link PersistenceContext}; must not be {@code null}
   *
   * @return a non-{@code null} {@link EntityManager}
   *
   * @see #registerPersistenceContextInjectionPoint(InjectionPoint)
   *
   * @deprecated See the documentation for the {@link
   * org.jboss.weld.injection.spi.JpaInjectionServices#resolvePersistenceContext(InjectionPoint)}
   * method.
   */
  @Deprecated
  @Override
  public final EntityManager resolvePersistenceContext(final InjectionPoint injectionPoint) {
    return this.registerPersistenceContextInjectionPoint(injectionPoint).createResource().getInstance();
  }

  /**
   * Calls the {@link
   * #registerPersistenceUnitInjectionPoint(InjectionPoint)} method
   * and invokes {@link ResourceReference#getInstance()} on its return
   * value and returns the result.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * @param injectionPoint an {@link InjectionPoint} annotated with
   * {@link PersistenceUnit}; must not be {@code null}
   *
   * @return a non-{@code null} {@link EntityManagerFactory}
   *
   * @see #registerPersistenceUnitInjectionPoint(InjectionPoint)
   *
   * @deprecated See the documentation for the {@link
   * org.jboss.weld.injection.spi.JpaInjectionServices#resolvePersistenceUnit(InjectionPoint)}
   * method.
   */
  @Deprecated
  @Override
  public final EntityManagerFactory resolvePersistenceUnit(final InjectionPoint injectionPoint) {
    return this.registerPersistenceUnitInjectionPoint(injectionPoint).createResource().getInstance();
  }


  /*
   * Static methods.
   */


  private static final PersistenceProvider getPersistenceProvider(final PersistenceUnitInfo persistenceUnitInfo) {
    final String providerClassName = Objects.requireNonNull(persistenceUnitInfo).getPersistenceProviderClassName();
    final PersistenceProvider persistenceProvider;
    if (providerClassName == null) {
      persistenceProvider = CDI.current().select(PersistenceProvider.class).get();
    } else {
      try {
        persistenceProvider =
          (PersistenceProvider)CDI.current().select(Class.forName(providerClassName,
                                                                  true,
                                                                  Thread.currentThread().getContextClassLoader())).get();
      } catch (final ReflectiveOperationException exception) {
        throw new PersistenceException(exception.getMessage(), exception);
      }
    }
    return persistenceProvider;
  }

  private static final PersistenceUnitInfo getPersistenceUnitInfo(final String name) {
    return CDI.current().select(PersistenceUnitInfo.class,
                                NamedLiteral.of(Objects.requireNonNull(name))).get();
  }

  private static final EntityManagerFactory getOrCreateEntityManagerFactory(final Map<String, EntityManagerFactory> emfs,
                                                                            final PersistenceUnitInfo persistenceUnitInfo,
                                                                            final String name) {
    Objects.requireNonNull(emfs);
    Objects.requireNonNull(name);
    final EntityManagerFactory returnValue;
    if (persistenceUnitInfo == null) {
      returnValue =
        emfs.computeIfAbsent(name, n -> Persistence.createEntityManagerFactory(n));
    } else {
      final PersistenceProvider persistenceProvider = getPersistenceProvider(persistenceUnitInfo);
      assert persistenceProvider != null;
      returnValue =
        emfs.computeIfAbsent(name,
                             n -> {
                               final CDI<Object> cdi = CDI.current();
                               assert cdi != null;
                               final BeanManager beanManager = cdi.getBeanManager();
                               assert beanManager != null;
                               final Map<String, Object> properties = new HashMap<>();
                               properties.put("javax.persistence.bean.manager",
                                              beanManager);
                               Class<?> validatorFactoryClass = null;
                               try {
                                 validatorFactoryClass = Class.forName("javax.validation.ValidatorFactory");
                               } catch (final ClassNotFoundException classNotFoundException) {

                               }
                               if (validatorFactoryClass != null) {
                                 final Bean<?> validatorFactoryBean =
                                   getValidatorFactoryBean(beanManager,
                                                           validatorFactoryClass);
                                 if (validatorFactoryBean != null) {
                                   properties.put("javax.validation.ValidatorFactory",
                                                  beanManager.getReference(validatorFactoryBean,
                                                                           validatorFactoryClass,
                                                                           beanManager.createCreationalContext(validatorFactoryBean)));
                                 }
                               }
                               return
                                 persistenceProvider.createContainerEntityManagerFactory(persistenceUnitInfo,
                                                                                         properties);
                             });
    }
    return returnValue;
  }

  private static final Bean<?> getValidatorFactoryBean(final BeanManager beanManager,
                                                       final Class<?> validatorFactoryClass) {
    return getValidatorFactoryBean(beanManager, validatorFactoryClass, null);
  }

  private static final Bean<?> getValidatorFactoryBean(final BeanManager beanManager,
                                                       final Class<?> validatorFactoryClass,
                                                       final Set<Annotation> qualifiers) {
    Bean<?> returnValue = null;
    if (beanManager != null && validatorFactoryClass != null) {
      final Set<Bean<?>> beans;
      if (qualifiers == null) {
        beans = beanManager.getBeans(validatorFactoryClass);
      } else {
        beans = beanManager.getBeans(validatorFactoryClass, qualifiers.toArray(new Annotation[qualifiers.size()]));
      }
      if (beans != null && !beans.isEmpty()) {
        returnValue = beanManager.resolve(beans);
      }
    }
    return returnValue;
  }


  /*
   * Inner and nested classes.
   */


  private static final class EntityManagerFactoryResourceReference implements ResourceReference<EntityManagerFactory> {

    private final Map<String, EntityManagerFactory> emfs;

    private final String name;

    private final PersistenceUnitInfo persistenceUnitInfo;

    private EntityManagerFactoryResourceReference(final Map<String, EntityManagerFactory> emfs,
                                                  final String name) {
      super();
      this.emfs = Objects.requireNonNull(emfs);
      this.name = Objects.requireNonNull(name);
      this.persistenceUnitInfo = getPersistenceUnitInfo(name);
      assert this.persistenceUnitInfo != null;
    }

    @Override
    public final EntityManagerFactory getInstance() {
      final EntityManagerFactory returnValue;
      if (RESOURCE_LOCAL.equals(persistenceUnitInfo.getTransactionType())) {
        returnValue = getOrCreateEntityManagerFactory(emfs, null, this.name);
      } else {
        returnValue = getOrCreateEntityManagerFactory(emfs, this.persistenceUnitInfo, this.name);
      }
      return returnValue;
    }

    @Override
    public final void release() {
      final EntityManagerFactory emf = this.emfs.remove(this.name);
      if (emf != null && emf.isOpen()) {
        emf.close();
      }
    }
  }

  private final class EntityManagerResourceReference implements ResourceReference<EntityManager> {

    private final String name;

    private final SynchronizationType synchronizationType;

    private final PersistenceUnitInfo persistenceUnitInfo;

    // @GuardedBy("this")
    private EntityManager em;

    private final Future<EntityManagerFactory> emfFuture;

    private final Supplier<EntityManager> emSupplier;

    private EntityManagerResourceReference(final String name,
                                           final SynchronizationType synchronizationType) {
      super();
      this.name = Objects.requireNonNull(name);
      this.synchronizationType = Objects.requireNonNull(synchronizationType);
      this.persistenceUnitInfo = getPersistenceUnitInfo(name);
      assert this.persistenceUnitInfo != null;
      final ExecutorService taskExecutorService = ((WeldManager)CDI.current().getBeanManager()).getServices().get(ExecutorServices.class).getTaskExecutor();
      assert taskExecutorService != null;
      if (this.isResourceLocal()) {
        // Kick off the lengthy process of setting up an
        // EntityManagerFactory in the background with the optimistic
        // assumption, possibly incorrect, that someone will call
        // getInstance() at some point.
        this.emfFuture = taskExecutorService.submit(() -> {
            return getOrCreateEntityManagerFactory(emfs, null, this.name);
          });
        this.emSupplier = () -> {
          try {
            return emfFuture.get().createEntityManager();
          } catch (final ExecutionException executionException) {
            throw new RuntimeException(executionException.getMessage(), executionException);
          } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException.getMessage(), interruptedException);
          }
        };
      } else {
        // Kick off the lengthy process of setting up an
        // EntityManagerFactory in the background with the optimistic
        // assumption, possibly incorrect, that someone will call
        // getInstance() at some point.
        this.emfFuture = taskExecutorService.submit(() -> {
            return getOrCreateEntityManagerFactory(emfs, this.persistenceUnitInfo, this.name);
          });
        this.emSupplier = () -> {
          try {
            final EntityManager em = emfFuture.get().createEntityManager(this.synchronizationType);
            JpaInjectionServices.this.ems.add(em);
            return em;
          } catch (final ExecutionException executionException) {
            throw new RuntimeException(executionException.getMessage(), executionException);
          } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException.getMessage(), interruptedException);
          }
        };
      }


    }

    private final boolean isResourceLocal() {
      return RESOURCE_LOCAL.equals(this.persistenceUnitInfo.getTransactionType());
    }

    @Override
    public synchronized final EntityManager getInstance() {
      if (this.em == null) {
        this.em = this.emSupplier.get();
      }
      return this.em;
    }

    @Override
    public final void release() {
      final EntityManager em;
      synchronized (this) {
        em = this.em;
        this.em = null;
      }
      if (em != null) {
        if (em.isOpen() && this.isResourceLocal()) {
          // Note that according to the javadocs on
          // EntityManager#close(), you're never supposed to call
          // EntityManager#close() on a container-managed
          // EntityManager; hence the isResourceLocal() check here.
          em.close();
        }
        JpaInjectionServices.this.ems.remove(em);
      }
      if (!this.emfFuture.isDone()) {
        this.emfFuture.cancel(true);
      }
    }

  }

}
