package org.jboss.jbossts.tomcat;

import org.hibernate.transaction.JNDITransactionManagerLookup;

public class JBossTransactionManagerLookup extends JNDITransactionManagerLookup {
  protected String getName() {
    return "java:comp/env/TransactionManager";
  }

  public String getUserTransactionName() {
    return "UserTransaction";
  }
}
