/*******************************************************************************
 * * Copyright 2012 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.kundera.persistence;

import java.util.HashMap;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityExistsException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityNotFoundException;
import javax.persistence.EntityTransaction;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContextType;
import javax.persistence.Query;
import javax.persistence.TransactionRequiredException;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.transaction.UserTransaction;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.kundera.Constants;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.cache.Cache;
import com.impetus.kundera.metadata.model.ApplicationMetadata;
import com.impetus.kundera.metadata.model.KunderaMetadata;
import com.impetus.kundera.persistence.context.PersistenceCache;
import com.impetus.kundera.persistence.jta.KunderaJTAUserTransaction;
import com.impetus.kundera.query.KunderaTypedQuery;
import com.impetus.kundera.query.QueryImpl;

/**
 * The Class EntityManagerImpl.
 * 
 * @author animesh.kumar
 */
public class EntityManagerImpl implements EntityManager, ResourceManager
{

    /** The Constant log. */
    private static Log logger = LogFactory.getLog(EntityManagerImpl.class);

    /** The factory. */
    private EntityManagerFactory factory;

    /** The closed. */
    private boolean closed = false;

    /** Flush mode for this EM, default is AUTO. */
    FlushModeType flushMode = FlushModeType.AUTO;

    /** The session. */
    private EntityManagerSession session;

    /** Properties provided by user at the time of EntityManager Creation. */
    private Map<String, Object> properties;

    /** Properties provided by user at the time of EntityManager Creation. */
    private PersistenceDelegator persistenceDelegator;

    /** Persistence Context Type (Transaction/ Extended) */
    private PersistenceContextType persistenceContextType;

    /** Transaction Type (JTA/ RESOURCE_LOCAL) */
    private PersistenceUnitTransactionType transactionType;

    private PersistenceCache persistenceCache;

    private UserTransaction utx;
    
    private EntityTransaction entityTransaction;
    
    /**
     * Instantiates a new entity manager impl.
     * 
     * @param factory
     *            the factory
     */
    public EntityManagerImpl(EntityManagerFactory factory, PersistenceUnitTransactionType transactionType,
            PersistenceContextType persistenceContextType)
    {
        this.factory = factory;
        if(logger.isDebugEnabled())
        {
            logger.debug("Creating EntityManager for persistence unit : " + getPersistenceUnit());
        }
        session = new EntityManagerSession((Cache) factory.getCache());
        persistenceCache = new PersistenceCache();
        persistenceCache.setPersistenceContextType(persistenceContextType);

        persistenceDelegator = new PersistenceDelegator(session, persistenceCache);

        for (String pu : ((EntityManagerFactoryImpl) this.factory).getPersistenceUnits())
        {
            persistenceDelegator.loadClient(pu);
        }
        this.persistenceContextType = persistenceContextType;
        this.transactionType = transactionType;
        this.entityTransaction = new KunderaEntityTransaction(this);
        
        if (logger.isDebugEnabled())
        {
            logger.debug("Created EntityManager for persistence unit : " + getPersistenceUnit());
        }
    }

    private void onLookUp(PersistenceUnitTransactionType transactionType)
    {
        if (transactionType != null && transactionType.equals(PersistenceUnitTransactionType.JTA))
        {
            Context ctx;
            try
            {

                ctx = new InitialContext();

                utx = (KunderaJTAUserTransaction) ctx.lookup("java:comp/UserTransaction");

                if (utx == null)
                {
                    throw new KunderaException(
                            "Lookup for UserTransaction returning null for :{java:comp/UserTransaction}");
                }
                if (!(utx instanceof KunderaJTAUserTransaction))
                {

                    throw new KunderaException("Please bind [" + KunderaJTAUserTransaction.class.getName()
                            + "] for :{java:comp/UserTransaction} lookup" + utx.getClass());

                } 
                
                if(((KunderaJTAUserTransaction)utx).isTransactionInProgress())
                {
                    entityTransaction.begin();
                }

                this.setFlushMode(FlushModeType.COMMIT);
                ((KunderaJTAUserTransaction) utx).setImplementor(this);
                
            }
            catch (NamingException e)
            {
                logger.error("Error during initialization of entity manager, Caused by:" + e.getMessage());
                throw new KunderaException(e);
            }

        }
    }

    /**
     * Instantiates a new entity manager impl.
     * 
     * @param factory
     *            the factory
     * @param properties
     *            the properties
     */
    public EntityManagerImpl(EntityManagerFactory factory, Map properties,
            PersistenceUnitTransactionType transactionType, PersistenceContextType persistenceContextType)
    {
        this(factory, transactionType, persistenceContextType);
        this.properties = properties;

        getPersistenceDelegator().populateClientProperties(this.properties);
    }

    /**
     * Make an instance managed and persistent.
     * 
     * @param entity
     * @throws EntityExistsException
     *             if the entity already exists. (If the entity already exists,
     *             the EntityExistsException may be thrown when the persist
     *             operation is invoked, or the EntityExistsException or another
     *             PersistenceException may be thrown at flush or commit time.)
     * @throws IllegalArgumentException
     *             if the instance is not an entity
     * @throws TransactionRequiredException
     *             if invoked on a container-managed entity manager of type
     *             PersistenceContextType.TRANSACTION and there is no
     *             transaction
     */
    @Override
    public final void persist(Object e)
    {
        checkClosed();
        checkTransactionNeeded();

        try
        {
            getPersistenceDelegator().persist(e);
        }
        catch (Exception ex)
        {
            // onRollBack.
            doRollback();
            throw new KunderaException(ex);
        }
    }

    /**
     * Merge the state of the given entity into the current persistence context.
     * 
     * @param entity
     * @return the managed instance that the state was merged to
     * @throws IllegalArgumentException
     *             if instance is not an entity or is a removed entity
     * @throws TransactionRequiredException
     *             if invoked on a container-managed entity manager of type
     *             PersistenceContextType.TRANSACTION and there is no
     *             transaction
     * @see javax.persistence.EntityManager#merge(java.lang.Object)
     */
    @Override
    public final <E> E merge(E e)
    {
        checkClosed();
        checkTransactionNeeded();

        if (e == null)
        {
            getPersistenceDelegator().rollback();
            throw new IllegalArgumentException("Entity to be merged must not be null.");
        }

        try
        {
            return getPersistenceDelegator().merge(e);
        }
        catch (Exception ex)
        {
			// on Rollback            
			doRollback();
            throw new KunderaException(ex);
        }
    }

    /**
     * Remove the entity instance.
     * 
     * @param entity
     * @throws IllegalArgumentException
     *             if the instance is not an entity or is a detached entity
     * @throws TransactionRequiredException
     *             if invoked on a container-managed entity manager of type
     *             PersistenceContextType.TRANSACTION and there is no
     *             transaction
     */
    @Override
    public final void remove(Object e)
    {
        checkClosed();
        checkTransactionNeeded();

        // TODO Check for validity also as per JPA
        if (e == null)
        {
            throw new IllegalArgumentException("Entity to be removed must not be null.");
        }

        try
        {
            getPersistenceDelegator().remove(e);
        }
        catch (Exception ex)
        {
            // on rollback.
            doRollback();
            throw new KunderaException(ex);
        }
    }

    /**
     * Find by primary key. Search for an entity of the specified class and
     * primary key. If the entity instance is contained in the persistence
     * context it is returned from there.
     * 
     * @param entityClass
     * @param primaryKey
     * @return the found entity instance or null if the entity does not exist
     * @throws IllegalArgumentException
     *             if the first argument does not denote an entity type or the
     *             second argument is is not a valid type for that entity’s
     *             primary key or is null
     * @see javax.persistence.EntityManager#find(java.lang.Class,
     *      java.lang.Object)
     */

    @Override
    public final <E> E find(Class<E> entityClass, Object primaryKey)
    {
        checkClosed();
        checkTransactionNeeded();
        // TODO Check for validity also as per JPA
        if (primaryKey == null)
        {
            throw new IllegalArgumentException("PrimaryKey value must not be null for object you want to find.");
        }

        return getPersistenceDelegator().findById(entityClass, primaryKey);
    }

    /**
     * Find by primary key, using the specified properties. Search for an entity
     * of the specified class and primary key. If the entity instance is
     * contained in the persistence context it is returned from there. If a
     * vendor-specific property or hint is not recognized, it is silently
     * ignored.
     * 
     * @param entityClass
     * @param primaryKey
     * @param properties
     *            standard and vendor-specific properties and hints
     * @return the found entity instance or null if the entity does not exist
     * @throws IllegalArgumentException
     *             if the first argument does not denote an entity type or the
     *             second argument is is not a valid type for that entity’s
     *             primary key or is null
     * @see javax.persistence.EntityManager#find(java.lang.Class,
     *      java.lang.Object, java.util.Map)
     */
    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, Map<String, Object> properties)
    {
        checkClosed();
        checkTransactionNeeded();

        if (primaryKey == null)
        {
            throw new IllegalArgumentException("PrimaryKey value must not be null for object you want to find.");
        }

        // Store current properties in a variable for post-find reset
        Map<String, Object> currentProperties = getProperties();

        // Populate properties in client
        getPersistenceDelegator().populateClientProperties(properties);
        T result = find(entityClass, primaryKey);

        // Reset Client properties
        getPersistenceDelegator().populateClientProperties(currentProperties);
        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#find(java.lang.Class,
     * java.lang.Object, javax.persistence.LockModeType)
     */
    @Override
    public <T> T find(Class<T> paramClass, Object paramObject, LockModeType paramLockModeType)
    {
        throw new NotImplementedException("Lock mode type currently not supported by Kundera");
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#find(java.lang.Class,
     * java.lang.Object, javax.persistence.LockModeType, java.util.Map)
     */
    @Override
    public <T> T find(Class<T> arg0, Object arg1, LockModeType arg2, Map<String, Object> arg3)
    {
        throw new NotImplementedException("Lock mode type currently not supported by Kundera");
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#clear()
     */
    @Override
    public final void clear()
    {
        checkClosed();
        session.clear();

        // TODO Do we need a client and persistenceDelegator close here?
        if (!PersistenceUnitTransactionType.JTA.equals(transactionType))
        {
            persistenceDelegator.clear();
        }
    }

    @Override
    public final void close()
    {
        checkClosed();
        session.clear();
        session = null;
        persistenceDelegator.close();

        if (!PersistenceUnitTransactionType.JTA.equals(transactionType))
        {
            persistenceDelegator.clear();
        }
        closed = true;
    }

    /**
     * Check if the instance is a managed entity instance belonging to the
     * current persistence context.
     * 
     * @param entity
     * @return boolean indicating if entity is in persistence context
     * @throws IllegalArgumentException
     *             if not an entity
     * @see javax.persistence.EntityManager#contains(java.lang.Object)
     */
    @Override
    public final boolean contains(Object entity)
    {
        checkClosed();

        return getPersistenceDelegator().contains(entity);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createQuery(java.lang.String)
     */
    @Override
    public final Query createQuery(String query)
    {
        checkTransactionNeeded();
        return persistenceDelegator.createQuery(query);
    }

    @Override
    public final void flush()
    {
        checkClosed();
        persistenceDelegator.doFlush();
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getDelegate()
     */
    @Override
    public final Object getDelegate()
    {
        return persistenceDelegator.getDelegate();
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createNamedQuery(java.lang.String)
     */
    @Override
    public final Query createNamedQuery(String name)
    {
        checkTransactionNeeded();
        return persistenceDelegator.createQuery(name);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createNativeQuery(java.lang.String)
     */
    @Override
    public final Query createNativeQuery(String sqlString)
    {
        throw new NotImplementedException("Please use createNativeQuery(String sqlString, Class resultClass) instead.");
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createNativeQuery(java.lang.String,
     * java.lang.Class)
     */
    @Override
    public final Query createNativeQuery(String sqlString, Class resultClass)
    {
        checkTransactionNeeded();
        // Add to meta data first.
        ApplicationMetadata appMetadata = KunderaMetadata.INSTANCE.getApplicationMetadata();

        if (appMetadata.getQuery(sqlString) == null)
        {
            appMetadata.addQueryToCollection(sqlString, sqlString, true, resultClass);
        }

        return persistenceDelegator.createQuery(sqlString);

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createNativeQuery(java.lang.String,
     * java.lang.String)
     */
    @Override
    public final Query createNativeQuery(String sqlString, String resultSetMapping)
    {
        throw new NotImplementedException("ResultSetMapping currently not supported by Kundera. "
                + "Please use createNativeQuery(String sqlString, Class resultClass) instead.");
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getReference(java.lang.Class,
     * java.lang.Object)
     */
    @Override
    public final <T> T getReference(Class<T> entityClass, Object primaryKey)
    {
        throw new NotImplementedException("getReference currently not supported by Kundera");
    }

    @Override
    public final FlushModeType getFlushMode()
    {
        return this.flushMode;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getTransaction()
     */
    @Override
    public final EntityTransaction getTransaction()
    {
        if (this.transactionType == PersistenceUnitTransactionType.JTA)
        {
            throw new IllegalStateException("A JTA EntityManager cannot use getTransaction()");
        }
        return this.entityTransaction;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#joinTransaction()
     */
    @Override
    public final void joinTransaction()
    {
        if (utx != null)
        {
            return;
        }
        else
        {
            throw new TransactionRequiredException("No transaction in progress");
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#lock(java.lang.Object,
     * javax.persistence.LockModeType)
     */
    @Override
    public final void lock(Object entity, LockModeType lockMode)
    {
        throw new NotImplementedException("lock currently not supported by Kundera");
    }

    /**
     * Refresh the state of the instance from the database, overwriting changes
     * made to the entity, if any.
     * 
     * @param entity
     * @throws IllegalArgumentException
     *             if the instance is not an entity or the entity is not managed
     * @throws TransactionRequiredException
     *             if invoked on a container-managed entity manager of type
     *             PersistenceContextType.TRANSACTION and there is no
     *             transaction
     * @throws EntityNotFoundException
     *             if the entity no longer exists in the database
     * @see javax.persistence.EntityManager#refresh(java.lang.Object)
     */
    @Override
    public final void refresh(Object entity)
    {
        checkClosed();

        checkTransactionNeeded();

        if (!getPersistenceDelegator().contains(entity))
        {
            throw new IllegalArgumentException("This is not a valid or managed entity, can't be refreshed");
        }

        getPersistenceDelegator().refresh(entity);
    }

    /**
     * Refresh the state of the instance from the database, using the specified
     * properties, and overwriting changes made to the entity, if any. If a
     * vendor-specific property or hint is not recognized, it is silently
     * ignored.
     * 
     * @param entity
     * @param properties
     *            standard and vendor-specific properties and hints
     * @throws IllegalArgumentException
     *             if the instance is not an entity or the entity is not managed
     * @throws TransactionRequiredException
     *             if invoked on a container-managed entity manager of type
     *             PersistenceContextType.TRANSACTION and there is no
     *             transaction
     * @throws EntityNotFoundException
     *             if the entity no longer exists in the database
     * @see javax.persistence.EntityManager#refresh(java.lang.Object,
     *      java.util.Map)
     */
    @Override
    public void refresh(Object entity, Map<String, Object> properties)
    {
        // Store current properties in a variable for post-find reset
        Map<String, Object> currentProperties = getProperties();

        // Populate properties in client
        getPersistenceDelegator().populateClientProperties(properties);

        // Refresh state of entity
        refresh(entity);

        // Reset Client properties
        getPersistenceDelegator().populateClientProperties(currentProperties);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#lock(java.lang.Object,
     * javax.persistence.LockModeType, java.util.Map)
     */
    @Override
    public void lock(Object paramObject, LockModeType paramLockModeType, Map<String, Object> paramMap)
    {
        throw new NotImplementedException("lock currently not supported by Kundera");
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#refresh(java.lang.Object,
     * javax.persistence.LockModeType)
     */
    @Override
    public void refresh(Object paramObject, LockModeType paramLockModeType)
    {
        throw new NotImplementedException("Lock mode type currently not supported by Kundera");

    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#refresh(java.lang.Object,
     * javax.persistence.LockModeType, java.util.Map)
     */
    @Override
    public void refresh(Object paramObject, LockModeType paramLockModeType, Map<String, Object> paramMap)
    {
        throw new NotImplementedException("LockModeType currently not supported by Kundera");
    }

    /**
     * Remove the given entity from the persistence context, causing a managed
     * entity to become detached. Unflushed changes made to the entity if any
     * (including removal of the entity), will not be synchronized to the
     * database. Entities which previously referenced the detached entity will
     * continue to reference it.
     * 
     * @param entity
     * @throws IllegalArgumentException
     *             if the instance is not an entity
     * @see javax.persistence.EntityManager#detach(java.lang.Object)
     */
    @Override
    public void detach(Object entity)
    {
        checkClosed();

        getPersistenceDelegator().detach(entity);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getLockMode(java.lang.Object)
     */
    @Override
    public LockModeType getLockMode(Object paramObject)
    {
        throw new NotImplementedException("Lock mode type currently not supported by Kundera");
    }

    /**
     * Set an entity manager property or hint. If a vendor-specific property or
     * hint is not recognized, it is silently ignored.
     * 
     * @param propertyName
     *            name of property or hint
     * @param value
     * @throws IllegalArgumentException
     *             if the second argument is not valid for the implementation
     * @see javax.persistence.EntityManager#setProperty(java.lang.String,
     *      java.lang.Object)
     */
    @Override
    public void setProperty(String paramString, Object paramObject)
    {
        if (getProperties() == null)
        {
            this.properties = new HashMap<String, Object>();
        }

        this.properties.put(paramString, paramObject);
        getPersistenceDelegator().populateClientProperties(this.properties);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * javax.persistence.EntityManager#createQuery(javax.persistence.criteria
     * .CriteriaQuery)
     */
    @Override
    public <T> TypedQuery<T> createQuery(CriteriaQuery<T> paramCriteriaQuery)
    {
        throw new NotImplementedException("Criteria Query currently not supported by Kundera");
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createQuery(java.lang.String,
     * java.lang.Class)
     */
    @Override
    public <T> TypedQuery<T> createQuery(String paramString, Class<T> paramClass)
    {
        Query q = createQuery(paramString);
        return onTypedQuery(paramClass, q);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#createNamedQuery(java.lang.String,
     * java.lang.Class)
     */
    @Override
    public <T> TypedQuery<T> createNamedQuery(String paramString, Class<T> paramClass)
    {
        Query q = createNamedQuery(paramString);
        return onTypedQuery(paramClass, q);
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#unwrap(java.lang.Class)
     */
    @Override
    public <T> T unwrap(Class<T> paramClass)
    {
        throw new NotImplementedException("unwrap currently not supported by Kundera");
    }

    @Override
    public final void setFlushMode(FlushModeType flushMode)
    {
        this.flushMode = flushMode;
        persistenceDelegator.setFlushMode(flushMode);
    }

    /**
     * Get the properties and hints and associated values that are in effect for
     * the entity manager. Changing the contents of the map does not change the
     * configuration in effect.
     * 
     * @return map of properties and hints in effect
     */
    @Override
    public Map<String, Object> getProperties()
    {
        return properties;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getEntityManagerFactory()
     */
    @Override
    public EntityManagerFactory getEntityManagerFactory()
    {
        return factory;
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getCriteriaBuilder()
     */
    @Override
    public CriteriaBuilder getCriteriaBuilder()
    {
        return factory.getCriteriaBuilder();
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#getMetamodel()
     */
    @Override
    public Metamodel getMetamodel()
    {
        return factory.getMetamodel();
    }

    /*
     * (non-Javadoc)
     * 
     * @see javax.persistence.EntityManager#isOpen()
     */
    @Override
    public final boolean isOpen()
    {
        return !closed;
    }

    /**
     * Check closed.
     */
    private void checkClosed()
    {
        if (!isOpen())
        {
            throw new IllegalStateException("EntityManager has already been closed.");
        }
    }

    private void checkTransactionNeeded()
    {
        onLookUp(transactionType);

        if ((this.persistenceContextType != PersistenceContextType.TRANSACTION)
                || (persistenceDelegator.isTransactionInProgress()))
            return;

        throw new TransactionRequiredException(
                "no transaction is in progress for a TRANSACTION type persistence context");
    }

    /**
     * Returns Persistence unit (or comma separated units) associated with EMF.
     * 
     * @return the persistence unit
     */
    private String getPersistenceUnit()
    {
        return (String) this.factory.getProperties().get(Constants.PERSISTENCE_UNIT_NAME);
    }

    /**
     * Gets the session.
     * 
     * @return the session
     */
    private EntityManagerSession getSession()
    {
        return session;
    }

    /**
     * Gets the persistence delegator.
     * 
     * @return the persistence delegator
     */
    PersistenceDelegator getPersistenceDelegator()
    {
        return persistenceDelegator;
    }

    /**
     * @return the persistenceContextType
     */
    public PersistenceContextType getPersistenceContextType()
    {
        return persistenceContextType;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.EntityImplementor#doCommit()
     */
    @Override
    public void doCommit()
    {
        checkClosed();
        this.entityTransaction.commit();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.persistence.EntityImplementor#doRollback()
     */
    @Override
    public void doRollback()
    {
        checkClosed();
        this.entityTransaction.rollback();
    }

    /**
     * Validates if expected result class is matching with supplied one, else
     * throws {@link IllegalArgumentException}
     * 
     * @param <T>
     *            object type
     * @param paramClass
     *            expected result class
     * @param q
     *            query
     * @return typed query instance.
     */
    private <T> TypedQuery<T> onTypedQuery(Class<T> paramClass, Query q)
    {
        if (paramClass.equals(((QueryImpl) q).getKunderaQuery().getEntityClass()) || paramClass.equals(Object.class))
        {
            return new KunderaTypedQuery<T>(q);
        }

        throw new IllegalArgumentException("Mismatch in expected return type. Expected:" + paramClass
                + " But actual class is:" + ((QueryImpl) q).getKunderaQuery().getEntityClass());
    }

}
