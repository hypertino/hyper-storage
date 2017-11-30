/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import com.hypertino.binders.value._
import com.hypertino.hyperbus.model.{NotFound, _}
import com.hypertino.hyperbus.serialization.SerializationOptions
import com.hypertino.hyperstorage._
import com.hypertino.hyperstorage.api._
import com.hypertino.hyperstorage.sharding._
import com.hypertino.hyperstorage.sharding.akkacluster.{AkkaClusterTransport, AkkaClusterTransportActor}
import com.hypertino.hyperstorage.sharding.consulzmq.ZMQCClusterTransport
import com.hypertino.hyperstorage.utils.SortBy
import com.hypertino.hyperstorage.workers.HyperstorageWorkerSettings
import com.typesafe.config.Config
import monix.execution.Ack.Continue
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Promise
import scala.concurrent.duration._

class IntegratedSpec extends FlatSpec
  with Matchers
  with ScalaFutures
  with CassandraFixture
  with TestHelpers
  with Eventually {

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(20000, Millis)))

  import MessagingContext.Implicits.emptyContext

  "HyperStorageIntegrated" should "Test hyperstorage PUT+GET simple example" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    val create = hyperbus.ask(ContentPut("abc/123", DynamicBody(Obj.from("a" → 10, "x" → "hello"))))
      .runAsync
      .futureValue

    create shouldBe a[Created[_]]

    val ok = hyperbus.ask(ContentGet("abc/123"))
      .runAsync
      .futureValue

    ok shouldBe a[Ok[_]]
    ok.body shouldBe DynamicBody(Obj.from("a" → 10, "x" → "hello"))

    val delete = hyperbus.ask(ContentDelete("abc/123"))
      .runAsync
      .futureValue

    delete shouldBe a[Ok[_]]
  }

  it should "Test hyperstorage POST+GET with smart-id for document" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    val create = hyperbus.ask(ContentPost("users", DynamicBody(Obj.from("a" → 10, "x" → "hello"))))
      .runAsync
      .futureValue

    create shouldBe a[Created[_]]
    create.body shouldBe a[HyperStorageTransactionCreated]
    val userId = create.body.target.dynamic.user_id
    userId shouldBe a[Text]

    val ok = hyperbus.ask(ContentGet(s"users/${userId.toString}"))
      .runAsync
      .futureValue

    ok shouldBe a[Ok[_]]
    ok.body shouldBe DynamicBody(Obj.from("a" → 10, "x" → "hello", "user_id" → userId))
  }

  it should "Test hyperstorage PUT+GET+Event" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    val putEventPromise = Promise[ContentFeedPut]()

    hyperbus.events[ContentFeedPut](None).subscribe { put ⇒
      putEventPromise.success(put)
      Continue
    }

    Thread.sleep(2000)

    val path = UUID.randomUUID().toString
    implicit val mcx = MessagingContext("abc123")
    val f1 = hyperbus.ask(ContentPut(path, DynamicBody(Text("Hello")))(mcx)).runAsync
    whenReady(f1) { response ⇒
      response.headers.statusCode should equal(Status.CREATED)
      response.headers.correlationId should equal("abc123")
    }

    val putEventFuture = putEventPromise.future
    whenReady(putEventFuture) { putEvent ⇒
      putEvent.headers.method should equal(Method.FEED_PUT)
      putEvent.body should equal(DynamicBody(Text("Hello")))
      putEvent.headers.get(Header.REVISION) shouldNot be(None)
    }

    whenReady(hyperbus.ask(ContentGet(path)(mcx)).runAsync) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(Text("Hello"))
      response.headers.correlationId shouldBe "abc123"
    }
  }

  it should "Null patch with hyperstorage (integrated)" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    val patchEventPromise = Promise[ContentFeedPatch]()

    hyperbus.events[ContentFeedPatch](None).subscribe { p ⇒
      patchEventPromise.success(p)
      Continue
    }

    Thread.sleep(2000)

    val path = UUID.randomUUID().toString
    whenReady(hyperbus.ask(ContentPut(path, DynamicBody(
      Obj.from("a" → "1", "b" → "2", "c" → "3")
    ))).runAsync) { response ⇒
      response.headers.statusCode should equal(Status.CREATED)
    }
    implicit val so = SerializationOptions.forceOptionalFields
    val r = ContentPatch(path, DynamicBody(Obj.from("b" → Null)))
    //println(s"making request ${r.serializeToString}")
    val f = hyperbus.ask(r).runAsync
    whenReady(f) { response ⇒
      response.headers.statusCode should equal(Status.OK)
    }

    val patchEventFuture = patchEventPromise.future
    whenReady(patchEventFuture) { patchEvent ⇒
      patchEvent.headers.method should equal(Method.FEED_PATCH)
      patchEvent.body should equal(DynamicBody(Obj.from("b" → Null)))
      patchEvent.headers.get(Header.REVISION) shouldNot be(None)
    }

    whenReady(hyperbus.ask(ContentGet(path)).runAsync) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(Obj.from("a" → "1", "c" → "3"))
    }
  }

  it should "Test hyperstorage PUT+GET+GET Collection+Event" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    val putEventPromise = Promise[ContentFeedPut]()
    hyperbus.events[ContentFeedPut](None).subscribe { put ⇒
      putEventPromise.success(put)
      Continue
    }

    Thread.sleep(2000)

    val c1 = Obj.from("a" → "hello", "b" → 100500)
    val c2 = Obj.from("a" → "goodbye", "b" → 654321)
    val c1x = c1 + Obj.from("id" → "item1")
    val c2x = c2 + Obj.from("id" → "item2")

    val path = "collection-1~/item1"
    val f = hyperbus.ask(ContentPut(path, DynamicBody(c1))).runAsync
    whenReady(f) { case response: Response[Body] ⇒
      response.headers.statusCode should equal(Status.CREATED)
    }

    val putEventFuture = putEventPromise.future
    whenReady(putEventFuture) { putEvent ⇒
      putEvent.headers.method should equal(Method.FEED_PUT)
      putEvent.body should equal(DynamicBody(c1x))
      putEvent.headers.get(Header.REVISION) shouldNot be(None)
    }

    val f2 = hyperbus.ask(ContentGet(path)).runAsync
    whenReady(f2) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(c1x)
    }

    val path2 = "collection-1~/item2"
    val f3 = hyperbus.ask(ContentPut(path2, DynamicBody(c2x))).runAsync
    whenReady(f3) { response ⇒
      response.headers.statusCode should equal(Status.CREATED)
    }

    val f4 = hyperbus.ask(ContentGet("collection-1~", perPage = Some(50))).runAsync

    whenReady(f4) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(
        Lst.from(c1x, c2x)
      )
    }

    import com.hypertino.hyperstorage.utils.Sort._

    val f5 = hyperbus.ask(ContentGet("collection-1~",
      perPage = Some(50),
      sortBy = Some(generateQueryParam(Seq(SortBy("id", true)))))
    ).runAsync

    whenReady(f5) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(
        Lst.from(c2x, c1x)
      )
    }
  }

  it should "Test hyperstorage POST+GET+GET Collection+Event" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    val putEventPromise = Promise[ContentFeedPut]()
    hyperbus.events[ContentFeedPut](None).subscribe { put ⇒
      putEventPromise.success(put)
      Continue
    }

    Thread.sleep(2000)

    val c1 = Obj.from("a" → "hello", "b" → Number(100500))
    val c2 = Obj.from("a" → "goodbye", "b" → Number(654321))

    val path = "collection-2~"
    val f = hyperbus.ask(ContentPost(path, DynamicBody(c1))).runAsync
    val tr1: HyperStorageTransactionCreated = whenReady(f) { case response: Created[HyperStorageTransactionCreated] ⇒
      response.headers.statusCode should equal(Status.CREATED)
      response.body
    }

    val id1 = tr1.target.dynamic.id
    val c1x = c1 + Obj.from("id" → id1)

    val putEventFuture = putEventPromise.future
    whenReady(putEventFuture) { putEvent ⇒
      putEvent.headers.method should equal(Method.FEED_PUT)
      putEvent.body should equal(DynamicBody(c1x))
      putEvent.headers.get(Header.REVISION) shouldNot be(None)
    }

    val f2 = hyperbus.ask(ContentGet(tr1.path)).runAsync
    whenReady(f2) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(c1x)
    }

    val f3 = hyperbus.ask(ContentPost(path, DynamicBody(c2))).runAsync
    val tr2: HyperStorageTransactionCreated = whenReady(f3) { case response: Created[Body] ⇒
      response.headers.statusCode should equal(Status.CREATED)
      response.body
    }

    val id2 = tr2.target.dynamic.id
    val c2x = c2 + Obj.from("id" → id2)

    val f4 = hyperbus.ask(ContentGet("collection-2~", perPage = Some(50))).runAsync

    whenReady(f4) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(
        Lst.from(c1x, c2x)
      )
      response.headers.get(Header.COUNT) shouldBe Some(Number(2))
    }

    import com.hypertino.hyperstorage.utils.Sort._

    val f5 = hyperbus.ask(ContentGet("collection-2~",
      perPage = Some(50),
      sortBy = Some(generateQueryParam(Seq(SortBy("id", false)))))
    ).runAsync

    whenReady(f5) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(
        Lst.from(c1x, c2x)
      )
    }

    val f6 = hyperbus.ask(ContentGet("collection-2~", perPage = Some(0))).runAsync

    whenReady(f6) { response ⇒
      response.headers.statusCode should equal(Status.OK)
      response.body.content should equal(
        Lst.empty
      )
      response.headers.get(Header.COUNT) shouldBe Some(Number(2))
    }
  }

  it should "support view on documents" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    hyperbus.ask(ViewPut("abcs~", HyperStorageView("abc/{*}")))
      .runAsync
      .futureValue shouldBe a[Created[_]]

    eventually {
      val h = db.selectViewDefs().runAsync.futureValue.toSeq.head
      h.documentUri shouldBe "abcs~"
      h.templateUri shouldBe "abc/{*}"
    }

    hyperbus.ask(ContentPut("abc/123", DynamicBody(Obj.from("a" → 10, "x" → "hello"))))
      .runAsync
      .futureValue shouldBe a[Created[_]]

    eventually {
      val ok = hyperbus.ask(ContentGet("abcs~/123"))
        .runAsync
        .futureValue

      ok shouldBe a[Ok[_]]
      ok.body shouldBe DynamicBody(Obj.from("a" → 10, "x" → "hello", "abc_id" → "123"))
    }

    hyperbus.ask(ContentDelete("abc/123"))
      .runAsync
      .futureValue shouldBe a[Ok[_]]

    eventually {
      hyperbus.ask(ContentGet("abcs~/123"))
        .runAsync
        .failed
        .futureValue shouldBe a[NotFound[_]]
    }
  }

  it should "support view on collections" in {
    val hyperbus = testHyperbus()
    val tk = testKit()
    import tk._

    cleanUpCassandra()

    val workerSettings = HyperstorageWorkerSettings(hyperbus, db, tracker, 1, 1, 10.seconds, self, scheduler)
    val processor = shardProcessor(workerSettings)
    val distributor = new HyperbusAdapter(hyperbus, processor, db, tracker, 20.seconds)
    // wait while subscription is completes
    Thread.sleep(2000)

    hyperbus.ask(ViewPut("abcs~", HyperStorageView("collection~/{*}")))
      .runAsync
      .futureValue shouldBe a[Created[_]]

    eventually {
      val h = db.selectViewDefs().runAsync.futureValue.toSeq.head
      h.documentUri shouldBe "abcs~"
      h.templateUri shouldBe "collection~/{*}"
    }

    hyperbus.ask(ContentPut("collection~/123", DynamicBody(Obj.from("a" → 10, "x" → "hello"))))
      .runAsync
      .futureValue shouldBe a[Created[_]]

    eventually {
      val ok = hyperbus.ask(ContentGet("abcs~/123"))
        .runAsync
        .futureValue

      ok shouldBe a[Ok[_]]
      ok.body shouldBe DynamicBody(Obj.from("a" → 10, "x" → "hello", "collection_id" → "123", "abc_id" → "123"))
    }

    hyperbus.ask(ContentDelete("collection~/123"))
      .runAsync
      .futureValue shouldBe a[Ok[_]]

    eventually {
      hyperbus.ask(ContentGet("abcs~/123"))
        .runAsync
        .failed
        .futureValue shouldBe a[NotFound[_]]
    }
  }

  def shardProcessor(workerSettings: Map[String, WorkerGroupSettings])(implicit as: ActorSystem) = {
    val clusterTransport = if (zmqDefault) {
      val config = inject[Config]
      new ZMQCClusterTransport(
        config.getConfig(s"hyperstorage.zmq-cluster-manager")
      )
    } else {
      val clusterTransportRef = TestActorRef(AkkaClusterTransportActor.props("hyperstorage"))
      new AkkaClusterTransport(clusterTransportRef)
    }

    TestActorRef(ShardProcessor.props(clusterTransport, workerSettings, tracker))
  }
}

