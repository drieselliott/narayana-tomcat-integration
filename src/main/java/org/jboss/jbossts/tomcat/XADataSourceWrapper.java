/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 *
 * (C) 2008,
 * @author JBoss Inc.
 */

package org.jboss.jbossts.tomcat;

import com.arjuna.ats.jdbc.TransactionalDriver;
import com.arjuna.ats.jdbc.common.jdbcPropertyManager;

import javax.naming.Context;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * This class provides a JNDI DataSource based approach to
 * management of transaction aware database connections.
 * Its got some vaguely tomcat6 specific JNDI weirdness in it,
 * so don't expect it to work in other environments.
 * <p/>
 * This class serves a dual purpose. To web applications that look it up via
 * their comp/env JNDI, it's a regular DataSource on which they can call getConnection().
 * To JBossTS TransactionalDriver and recovery code that looks it up via
 * the tomcat server's global JNDI context,
 * it's a XADataSource from which they can obtain a XAResource.
 * Hence it implements both DataSource and XADataSource.
 *
 * @author Jonathan Halliday jonathan.halliday@redhat.com
 * @version $Id$
 * @see http://tomcat.apache.org/tomcat-6.0-doc/config/globalresources.html
 * @since 2008-05
 */
public class XADataSourceWrapper implements XADataSource, DataSource {
  private XADataSource _theXADataSource;
  private final TransactionalDriver _theTransactionalDriver = new TransactionalDriver();
  private String _name;

  /**
   * Create a wrapper around the provided XADataSource implementation,
   * which should be registered in tomcat's global JNDI with the specified name.
   * Note: the registration is not done here, it's someone elses problem.
   * See TransactionalResourceFactory for example usage.
   *
   * @param name          should be the fully qualifed JNDI name of the XADataSource, in
   *                      tomcat's global JNDI, not a webapp specific JNDI context.
   * @param theDataSource
   */
  public XADataSourceWrapper(String name, XADataSource theDataSource) {
    _theXADataSource = theDataSource;
    _name = name;
  }

  /**
   * Obtain a direct reference to the wrapped object. This is not
   * recommended but may be necessary to e.g. call vendor specific methods.
   *
   * @return
   */
  public XADataSource getUnwrappedXADataSource() {
    return _theXADataSource;
  }

  ///////////////////////

  // Implementation of the DataSource API is done by reusing the arjuna
  // TransactionalDriver. Its already got all the smarts for checking tx
  // context, enlisting resources etc so we just delegate to it.
  // All we need is some fudging to make the JNDI name stuff behave.

  /**
   * Obtain a connection to the database.
   * Note: Pooling behaviour depends on the vendor's underlying XADataSource implementation.
   *
   * @return
   * @throws SQLException
   */
  public Connection getConnection() throws SQLException {
    String url = TransactionalDriver.arjunaDriver + _name;
    // although we are not setting any properties, the driver will barf if we pass 'null'.
    Properties properties = new Properties();
    return getTransactionalConnection(url, properties);
  }

  /**
   * Obtain a connection to the database using the supplied authentication credentials.
   *
   * @param username
   * @param password
   * @return
   * @throws SQLException
   */
  public Connection getConnection(String username, String password) throws SQLException {
    String url = TransactionalDriver.arjunaDriver + _name;
    Properties properties = new Properties();
    properties.setProperty(TransactionalDriver.userName, username);
    properties.setProperty(TransactionalDriver.password, password);
    return getTransactionalConnection(url, properties);
  }

  /*
   * This is where most of the tomcat specific weirdness resides. You probably
   * want to subclass and override this method for reuse in env other than tomcat.
   */
  protected Connection getTransactionalConnection(String url, Properties properties) throws SQLException {

    // For ref, the url the TransactionalDriver expects is the arjuna driver's
    // special prefix followed by a JNDI name.
    // via ConnectionImple the IndirectRecoverableConnection.createDataSource method
    // attempts to look it up in JNDI. There are two problems with this:

    //  First problem,
    // it always calls InitialContext(env), never InitalContext().
    // This we work around by copying into the arjuna config, the system
    // properties it needs to populate the env:

    // caution: ensure the tx lifecycle listener is configured in tomcat or there will be a
    // possible race here, as recovery needs these properties too and may start first
    jdbcPropertyManager.getJDBCEnvironmentBean().getJndiProperties().put("Context.INITIAL_CONTEXT_FACTORY", System.getProperty(Context.INITIAL_CONTEXT_FACTORY));
    jdbcPropertyManager.getJDBCEnvironmentBean().getJndiProperties().put("Context.URL_PKG_PREFIXES", System.getProperty(Context.URL_PKG_PREFIXES));

    // Second problem: this method has almost certainly been called by a webapp,
    // which has its own InitialContext. Whilst the datasource is in there, we
    // can't be certain it's under the same name as its global name. We also
    // don't want any hassle with the webapp classloader, which may go away
    // whilst recovery is still active. Hence we need to temporarily set things
    // such that we use the server's global InitialContext for the lookup
    // instead of the webapp one. Tomcat figures out the InitialContext based
    // on classloader, so we fool it by changing the Thread context from the
    // webapps classloader to its parent (the server's classloader):
    ClassLoader webappClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(webappClassLoader.getParent());
    Connection connection;
    try {
      connection = _theTransactionalDriver.connect(url, properties);
    } finally {
      Thread.currentThread().setContextClassLoader(webappClassLoader);
    }

    return connection;
  }

  ///////////////////////

  // Implementation of XADataSource API is just a straightforward wrap/delegate.
  // Note that some of these methods also appear in the DataSource API.
  // We don't really care, it's the underlying implementations problem
  // to disambiguate them if required.

  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(XADataSource.class);
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (isWrapperFor(iface)) {
      return (T) getUnwrappedXADataSource();
    } else {
      throw new SQLException("Not a wrapper for " + iface.getCanonicalName());
    }
  }

  public XAConnection getXAConnection() throws SQLException {
    return _theXADataSource.getXAConnection();
  }

  public XAConnection getXAConnection(String user, String password) throws SQLException {
    return _theXADataSource.getXAConnection(user, password);
  }

  public PrintWriter getLogWriter() throws SQLException {
    return _theXADataSource.getLogWriter();
  }

  public void setLogWriter(PrintWriter out) throws SQLException {
    _theXADataSource.setLogWriter(out);
  }

  public void setLoginTimeout(int seconds) throws SQLException {
    _theXADataSource.setLoginTimeout(seconds);
  }

  public int getLoginTimeout() throws SQLException {
    return _theXADataSource.getLoginTimeout();
  }
}