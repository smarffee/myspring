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

package org.springframework.transaction;

import java.sql.Connection;

/**
 * Interface that defines Spring-compliant transaction properties.
 * Based on the propagation behavior definitions analogous to EJB CMT attributes.
 *
 * <p>Note that isolation level and timeout settings will not get applied unless
 * an actual new transaction gets started. As only {@link #PROPAGATION_REQUIRED},
 * {@link #PROPAGATION_REQUIRES_NEW} and {@link #PROPAGATION_NESTED} can cause
 * that, it usually doesn't make sense to specify those settings in other cases.
 * Furthermore, be aware that not all transaction managers will support those
 * advanced features and thus might throw corresponding exceptions when given
 * non-default values.
 *
 * <p>The {@link #isReadOnly() read-only flag} applies to any transaction context,
 * whether backed by an actual resource transaction or operating non-transactionally
 * at the resource level. In the latter case, the flag will only apply to managed
 * resources within the application, such as a Hibernate <code>Session</code>.
 *
 * @author Juergen Hoeller
 * @since 08.05.2003
 * @see PlatformTransactionManager#getTransaction(TransactionDefinition)
 * @see org.springframework.transaction.support.DefaultTransactionDefinition
 * @see org.springframework.transaction.interceptor.TransactionAttribute
 */
public interface TransactionDefinition {

	/**
	 * Support a current transaction; create a new one if none exists.
	 * Analogous to the EJB transaction attribute of the same name.
	 * <p>This is typically the default setting of a transaction definition,
	 * and typically defines a transaction synchronization scope.
	 *
	 * 表示当前方法必须运行在事务中。
	 * 如果当前事务存在，方法将会该事务中运行。否则，会启动一个新的事务。
	 * 如果有，一起用。没有就新建。
	 */
	int PROPAGATION_REQUIRED = 0;

	/**
	 * Support a current transaction; execute non-transactionally if none exists.
	 * Analogous to the EJB transaction attribute of the same name.
	 * <p><b>NOTE:</b> For transaction managers with transaction synchronization,
	 * <code>PROPAGATION_SUPPORTS</code> is slightly different from no transaction
	 * at all, as it defines a transaction scope that synchronization might apply to.
	 * As a consequence, the same resources (a JDBC <code>Connection</code>, a
	 * Hibernate <code>Session</code>, etc) will be shared for the entire specified
	 * scope. Note that the exact behavior depends on the actual synchronization
	 * configuration of the transaction manager!
	 * <p>In general, use <code>PROPAGATION_SUPPORTS</code> with care! In particular, do
	 * not rely on <code>PROPAGATION_REQUIRED</code> or <code>PROPAGATION_REQUIRES_NEW</code>
	 * <i>within</i> a <code>PROPAGATION_SUPPORTS</code> scope (which may lead to
	 * synchronization conflicts at runtime). If such nesting is unavoidable, make sure
	 * to configure your transaction manager appropriately (typically switching to
	 * "synchronization on actual transaction").
	 * @see org.springframework.transaction.support.AbstractPlatformTransactionManager#setTransactionSynchronization
	 * @see org.springframework.transaction.support.AbstractPlatformTransactionManager#SYNCHRONIZATION_ON_ACTUAL_TRANSACTION
	 *
	 * 表示当前方法不需要事务上下文。
	 * 如果存在当前事务的话，那么该方法会在这个事务中运行。
	 * 如果有，一起用。没有就不用。
	 */
	int PROPAGATION_SUPPORTS = 1;

	/**
	 * Support a current transaction; throw an exception if no current transaction
	 * exists. Analogous to the EJB transaction attribute of the same name.
	 * <p>Note that transaction synchronization within a <code>PROPAGATION_MANDATORY</code>
	 * scope will always be driven by the surrounding transaction.
	 *
	 * 	表示该方法必须在事务中运行。
	 * 	如果当前事务不存在，会抛出一个异常。
	 * 	如果有，一起用。没有，不新建，抛异常。
	 */
	int PROPAGATION_MANDATORY = 2;

	/**
	 * Create a new transaction, suspending the current transaction if one exists.
	 * Analogous to the EJB transaction attribute of the same name.
	 * <p><b>NOTE:</b> Actual transaction suspension will not work out-of-the-box
	 * on all transaction managers. This in particular applies to
	 * {@link org.springframework.transaction.jta.JtaTransactionManager},
	 * which requires the <code>javax.transaction.TransactionManager</code>
	 * to be made available it to it (which is server-specific in standard J2EE).
	 * <p>A <code>PROPAGATION_REQUIRES_NEW</code> scope always defines its own
	 * transaction synchronizations. Existing synchronizations will be suspended
	 * and resumed appropriately.
	 * @see org.springframework.transaction.jta.JtaTransactionManager#setTransactionManager
	 *
	 * PROPAGATION_REQUIRES_NEW
	 * 标识当前方法必须在它自己的事务里运行，一个新的事务将被启动，而如果有一个事务正在运行的话，则在这个方法运行期间被挂起。
	 * 而spring 中对于此种传播方式的处理，与新事物建立最大的不同点在于，使用 suspend 方法将原事务挂起。
	 * 将信息挂起的目当然是为了在当前事务执行完毕后，在将原来的事务还原。
	 *
	 * 如果有，挂起，自己新建。没有，自己新建，完事以后恢复原有的事务。
	 * 必须运行在自己的事务里。新老事务，互不相干，互不依赖。
	 * 需要使用 JtaTransactionManager作为事务管理器
	 */
	int PROPAGATION_REQUIRES_NEW = 3;

	/**
	 * Do not support a current transaction; rather always execute non-transactionally.
	 * Analogous to the EJB transaction attribute of the same name.
	 * <p><b>NOTE:</b> Actual transaction suspension will not work out-of-the-box
	 * on all transaction managers. This in particular applies to
	 * {@link org.springframework.transaction.jta.JtaTransactionManager},
	 * which requires the <code>javax.transaction.TransactionManager</code>
	 * to be made available it to it (which is server-specific in standard J2EE).
	 * <p>Note that transaction synchronization is <i>not</i> available within a
	 * <code>PROPAGATION_NOT_SUPPORTED</code> scope. Existing synchronizations
	 * will be suspended and resumed appropriately.
	 * @see org.springframework.transaction.jta.JtaTransactionManager#setTransactionManager
	 *
	 * 表示该方法不应该在事务中运行。
	 * 如果存在当前事务，在该方法运行期间，当前事务会被挂起。
	 * 如果使用 JtaTransactionManager，则需要访问 TransactionManager
	 *
	 * 不用事务，如果有事务，挂起。
	 * 需要使用JtaTransactionManager作为事务管理器。
	 */
	int PROPAGATION_NOT_SUPPORTED = 4;

	/**
	 * Do not support a current transaction; throw an exception if a current transaction
	 * exists. Analogous to the EJB transaction attribute of the same name.
	 * <p>Note that transaction synchronization is <i>not</i> available within a
	 * <code>PROPAGATION_NEVER</code> scope.
	 *
	 * 表示当前方法不应该运行在事务上下文中。
	 * 如果当前正有一个事务在运行，则会抛异常。
	 *
	 * 不用事务，如果有事务，抛异常。
	 */
	int PROPAGATION_NEVER = 5;

	/**
	 * Execute within a nested transaction if a current transaction exists,
	 * behave like {@link #PROPAGATION_REQUIRED} else. There is no analogous
	 * feature in EJB.
	 * <p><b>NOTE:</b> Actual creation of a nested transaction will only work on
	 * specific transaction managers. Out of the box, this only applies to the JDBC
	 * {@link org.springframework.jdbc.datasource.DataSourceTransactionManager}
	 * when working on a JDBC 3.0 driver. Some JTA providers might support
	 * nested transactions as well.
	 * @see org.springframework.jdbc.datasource.DataSourceTransactionManager
	 *
	 * PROPAGATION_NESTED：嵌入式事务处理
	 * 表示如果当前有一个事务在运行中，则该方法应该运行在一个嵌套事务中，
	 * 被嵌套的事务可以独立于封装的事务进行提交或者回滚，
	 * 如果封装事务不存在，行为就像 PROPAGATION_REQUIRES_NEW。
	 * 对于嵌入式事务的处理，spring中主要考虑了两种方式的处理：
	 * 1. Spring中允许嵌入式事务的时候，则首选设置保存点的方式作为异常处理的回滚。
	 * 2. 对于其他方式，比如JTA，无法使用保存点的方式，那么处理方式与 PROPAGATION_REQUIRES_NEW 相同，
	 * 而一旦出现异常，则由Spring的事务异常处理机制去完成后续的操作。
	 * 对于挂起的目的，主要是记录原有事务的状态，以便后续操作对事务的恢复。
	 *
	 * 外层事务失败，会回滚内层事务。
	 * 内层事务失败，不会回滚外层事务。
	 *
	 * 这是一个嵌套事务，使用JDBC 3.0驱动时，仅仅支持DataSourceTransactionManager作为事务管理器。
	 * 还需要把PlatformTransactionManager的nestedTransactionAllowed属性设为true(属性值默认为false)。
	 */
	int PROPAGATION_NESTED = 6;


	/**
	 * Use the default isolation level of the underlying datastore.
	 * All other levels correspond to the JDBC isolation levels.
	 * @see java.sql.Connection
	 */
	int ISOLATION_DEFAULT = -1;

	/**
	 * Indicates that dirty reads, non-repeatable reads and phantom reads
	 * can occur.
	 * <p>This level allows a row changed by one transaction to be read by another
	 * transaction before any changes in that row have been committed (a "dirty read").
	 * If any of the changes are rolled back, the second transaction will have
	 * retrieved an invalid row.
	 * @see java.sql.Connection#TRANSACTION_READ_UNCOMMITTED
	 */
	int ISOLATION_READ_UNCOMMITTED = Connection.TRANSACTION_READ_UNCOMMITTED;

	/**
	 * Indicates that dirty reads are prevented; non-repeatable reads and
	 * phantom reads can occur.
	 * <p>This level only prohibits a transaction from reading a row
	 * with uncommitted changes in it.
	 * @see java.sql.Connection#TRANSACTION_READ_COMMITTED
	 */
	int ISOLATION_READ_COMMITTED = Connection.TRANSACTION_READ_COMMITTED;

	/**
	 * Indicates that dirty reads and non-repeatable reads are prevented;
	 * phantom reads can occur.
	 * <p>This level prohibits a transaction from reading a row with uncommitted changes
	 * in it, and it also prohibits the situation where one transaction reads a row,
	 * a second transaction alters the row, and the first transaction re-reads the row,
	 * getting different values the second time (a "non-repeatable read").
	 * @see java.sql.Connection#TRANSACTION_REPEATABLE_READ
	 */
	int ISOLATION_REPEATABLE_READ = Connection.TRANSACTION_REPEATABLE_READ;

	/**
	 * Indicates that dirty reads, non-repeatable reads and phantom reads
	 * are prevented.
	 * <p>This level includes the prohibitions in {@link #ISOLATION_REPEATABLE_READ}
	 * and further prohibits the situation where one transaction reads all rows that
	 * satisfy a <code>WHERE</code> condition, a second transaction inserts a row
	 * that satisfies that <code>WHERE</code> condition, and the first transaction
	 * re-reads for the same condition, retrieving the additional "phantom" row
	 * in the second read.
	 * @see java.sql.Connection#TRANSACTION_SERIALIZABLE
	 */
	int ISOLATION_SERIALIZABLE = Connection.TRANSACTION_SERIALIZABLE;


	/**
	 * Use the default timeout of the underlying transaction system,
	 * or none if timeouts are not supported. 
	 */
	int TIMEOUT_DEFAULT = -1;


	/**
	 * Return the propagation behavior.
	 * <p>Must return one of the <code>PROPAGATION_XXX</code> constants
	 * defined on {@link TransactionDefinition this interface}.
	 * @return the propagation behavior
	 * @see #PROPAGATION_REQUIRED
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isActualTransactionActive()
	 */
	int getPropagationBehavior();

	/**
	 * Return the isolation level.
	 * <p>Must return one of the <code>ISOLATION_XXX</code> constants
	 * defined on {@link TransactionDefinition this interface}.
	 * <p>Only makes sense in combination with {@link #PROPAGATION_REQUIRED}
	 * or {@link #PROPAGATION_REQUIRES_NEW}.
	 * <p>Note that a transaction manager that does not support custom isolation levels
	 * will throw an exception when given any other level than {@link #ISOLATION_DEFAULT}.
	 * @return the isolation level
	 */
	int getIsolationLevel();

	/**
	 * Return the transaction timeout.
	 * <p>Must return a number of seconds, or {@link #TIMEOUT_DEFAULT}.
	 * <p>Only makes sense in combination with {@link #PROPAGATION_REQUIRED}
	 * or {@link #PROPAGATION_REQUIRES_NEW}.
	 * <p>Note that a transaction manager that does not support timeouts will throw
	 * an exception when given any other timeout than {@link #TIMEOUT_DEFAULT}.
	 * @return the transaction timeout
	 */
	int getTimeout();

	/**
	 * Return whether to optimize as a read-only transaction.
	 * <p>The read-only flag applies to any transaction context, whether
	 * backed by an actual resource transaction
	 * ({@link #PROPAGATION_REQUIRED}/{@link #PROPAGATION_REQUIRES_NEW}) or
	 * operating non-transactionally at the resource level
	 * ({@link #PROPAGATION_SUPPORTS}). In the latter case, the flag will
	 * only apply to managed resources within the application, such as a
	 * Hibernate <code>Session</code>.
<<	 * <p>This just serves as a hint for the actual transaction subsystem;
	 * it will <i>not necessarily</i> cause failure of write access attempts.
	 * A transaction manager which cannot interpret the read-only hint will
	 * <i>not</i> throw an exception when asked for a read-only transaction.
	 * @return <code>true</code> if the transaction is to be optimized as read-only 
	 * @see org.springframework.transaction.support.TransactionSynchronization#beforeCommit(boolean)
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isCurrentTransactionReadOnly()
	 */
	boolean isReadOnly();

	/**
	 * Return the name of this transaction. Can be <code>null</code>.
	 * <p>This will be used as the transaction name to be shown in a
	 * transaction monitor, if applicable (for example, WebLogic's).
	 * <p>In case of Spring's declarative transactions, the exposed name will be
	 * the <code>fully-qualified class name + "." + method name</code> (by default).
	 * @return the name of this transaction
	 * @see org.springframework.transaction.interceptor.TransactionAspectSupport
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#getCurrentTransactionName()
	 */
	String getName();

}
