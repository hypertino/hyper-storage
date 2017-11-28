/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

import com.datastax.driver.core.Session
import com.hypertino.binders.cassandra.GuavaSessionQueryCache
import com.hypertino.hyperstorage.CassandraConnector
import com.hypertino.hyperstorage.db.Db
import com.hypertino.inflector.naming.CamelCaseToSnakeCaseConverter
import com.typesafe.scalalogging.StrictLogging
import monix.execution.Scheduler
import org.cassandraunit.CassandraCQLUnit
import org.cassandraunit.dataset.cql.ClassPathCQLDataSet
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.ExecutionContext

trait CassandraFixture extends BeforeAndAfterAll with ScalaFutures with StrictLogging {
  this: Suite =>
  //var session: Session = _
  var db: Db = _
  var dbOriginal: Db = _
  implicit var sessionQueryCache: GuavaSessionQueryCache[CamelCaseToSnakeCaseConverter.type] = _

  implicit def scheduler: Scheduler

  override def beforeAll() {
    Cassandra.start
    val connector = new CassandraConnector {
      override def connect(): Session = Cassandra.session
    }
    dbOriginal = new Db(connector)
    db = spy(dbOriginal)
    sessionQueryCache = new GuavaSessionQueryCache[CamelCaseToSnakeCaseConverter.type](Cassandra.session)
  }

  override def afterAll() {
    db = null
    dbOriginal = null
    sessionQueryCache = null
    //EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
  }

  def cleanUpCassandra(): Unit = {
    logger.info("------- CLEANING UP C* -------- ")
    if (Cassandra.session != null) {
      import scala.collection.JavaConversions._
      val cleanDs = new ClassPathCQLDataSet("cleanup.cql", "hyper_storage_test")
      cleanDs.getCQLStatements.foreach(c ⇒ Cassandra.session.execute(c))
      Thread.sleep(1000)
    }
  }

  import com.hypertino.binders.cassandra._
  def removeContent(documentUri: String) = cql"delete from content where document_uri=$documentUri".execute()
  def removeContent(documentUri: String, itemId: String) = cql"delete from content where document_uri=$documentUri and item_id=$itemId".execute()
}

object Cassandra extends CassandraCQLUnit(
  new ClassPathCQLDataSet("schema.cql", "hyper_storage_test"), null, 60000l
) {
  readTimeoutMillis = 30000
  //this.startupTimeoutMillis = 60000l
  lazy val start = {
    before()
  }
}
