/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

import java.util.Date

import com.hypertino.binders.value.Text
import com.hypertino.hyperstorage.db._
import monix.eval.Task
import org.scalatest.concurrent.Eventually
import org.scalatest.{FlatSpec, FreeSpec, Matchers}

import scala.concurrent.Future

class DbSpec extends FlatSpec with Matchers with CassandraFixture
  with TestHelpers
  with Eventually {

  "Db" should "Index collection with ordering by id" in {
    cleanUpCassandra()
    db.insertIndexItem("index_content", Seq.empty, IndexContent(
      "test~", "x1", "i1", 1l, Some(1l), Some("{}"), new Date(), None
    ), 0).runAsync.futureValue
    db.insertIndexItem("index_content", Seq.empty, IndexContent(
      "test~", "x1", "i2", 1l, Some(1l), Some("{}"), new Date(), None
    ), 0).runAsync.futureValue
    db.insertIndexItem("index_content", Seq.empty, IndexContent(
      "test~", "x1", "i3", 1l, Some(1l), Some("{}"), new Date(), None
    ), 0).runAsync.futureValue
    val ca = db.selectIndexCollection("index_content", "test~", "x1", Seq.empty,
      Seq(CkField("item_id", ascending = true)), 10).runAsync.futureValue.toSeq
    ca(0).itemId shouldBe "i1"
    ca(1).itemId shouldBe "i2"
    ca(2).itemId shouldBe "i3"

    val cd = db.selectIndexCollection("index_content", "test~", "x1",
      Seq.empty,
      Seq(CkField("item_id", ascending = false)),
      10).runAsync.futureValue.toSeq
    cd(0).itemId shouldBe "i3"
    cd(1).itemId shouldBe "i2"
    cd(2).itemId shouldBe "i1"

    val ca2 = db.selectIndexCollection("index_content", "test~", "x1",
      Seq(FieldFilter("item_id", Text("i1"), FilterGt)),
      Seq(CkField("item_id", ascending = true)),
      10).runAsync.futureValue.toSeq
    ca2(0).itemId shouldBe "i2"
    ca2(1).itemId shouldBe "i3"
    ca2.size shouldBe 2

    val cd2 = db.selectIndexCollection("index_content", "test~", "x1",
      Seq(FieldFilter("item_id", Text("i3"), FilterLt)),
      Seq(CkField("item_id", ascending = false)),
      10).runAsync.futureValue.toSeq
    cd2(0).itemId shouldBe "i2"
    cd2(1).itemId shouldBe "i1"
    cd2.size shouldBe 2
  }

  it should "Index collection with ordering by text field" in {
    cleanUpCassandra()
    val tasks = Seq(db.insertIndexItem("index_content_ta0", Seq("t0" → "aa00"), IndexContent(
      "test~", "x1", "i1", 1l, Some(1l), Some("{}"), new Date(), None
    ), 0),
      db.insertIndexItem("index_content_ta0", Seq("t0" → "aa01"), IndexContent(
        "test~", "x1", "i2", 1l, Some(1l), Some("{}"), new Date(), None
      ), 0),
      db.insertIndexItem("index_content_ta0", Seq("t0" → "aa02"), IndexContent(
        "test~", "x1", "i3", 1l, Some(1l), Some("{}"), new Date(), None
      ), 0),
      db.insertIndexItem("index_content_ta0", Seq("t0" → "aa02"), IndexContent(
        "test~", "x1", "i4", 1l, Some(1l), Some("{}"), new Date(), None
      ), 0))
    Task.sequence(tasks).runAsync.futureValue
    val ca = db.selectIndexCollection("index_content_ta0", "test~", "x1", Seq.empty, Seq.empty, 10).runAsync.futureValue.toSeq
    ca.size shouldBe 4
    ca(0).itemId shouldBe "i1"
    ca(1).itemId shouldBe "i2"
    ca(2).itemId shouldBe "i3"
    ca(3).itemId shouldBe "i4"

    val cd = db.selectIndexCollection("index_content_ta0", "test~", "x1",
      Seq.empty,
      Seq(CkField("t0", ascending = false)),
      10).runAsync.futureValue.toSeq
    cd.size shouldBe 4
    cd(0).itemId shouldBe "i4"
    cd(1).itemId shouldBe "i3"
    cd(2).itemId shouldBe "i2"
    cd(3).itemId shouldBe "i1"

    val ca2 = db.selectIndexCollection("index_content_ta0", "test~", "x1",
      Seq(FieldFilter("t0", Text("aa00"), FilterGt)),
      Seq(CkField("t0", ascending = true)),
      10).runAsync.futureValue.toSeq
    ca2.size shouldBe 3
    ca2(0).itemId shouldBe "i2"
    ca2(1).itemId shouldBe "i3"
    ca2(2).itemId shouldBe "i4"


    val cd2 = db.selectIndexCollection("index_content_ta0", "test~", "x1",
      Seq(FieldFilter("t0", Text("aa02"), FilterLt)),
      Seq(CkField("t0", ascending = false)),
      10).runAsync.futureValue.toSeq
    cd2.size shouldBe 2
    cd2(0).itemId shouldBe "i2"
    cd2(1).itemId shouldBe "i1"

    val ca3 = db.selectIndexCollection("index_content_ta0", "test~", "x1",
      Seq(FieldFilter("t0", Text("aa02"), FilterEq), FieldFilter("item_id", Text("i3"), FilterGt)),
      Seq(CkField("t0", ascending = true), CkField("item_id", ascending = true)),
      10).runAsync.futureValue.toSeq
    ca3.size shouldBe 1
    ca3(0).itemId shouldBe "i4"

    val cd3 = db.selectIndexCollection("index_content_ta0", "test~", "x1",
      Seq(FieldFilter("t0", Text("aa02"), FilterEq), FieldFilter("item_id", Text("i4"), FilterLt)),
      Seq(CkField("t0", ascending = false), CkField("item_id", ascending = false)),
      10).runAsync.futureValue.toSeq
    cd3.size shouldBe 1
    cd3(0).itemId shouldBe "i3"
  }

  it should "insert using ttl if specified" in {
    cleanUpCassandra()
    db.insertContent(Content(
      "test", "", 1l, List.empty, None, None, None, Some("{}"), new Date(), None, Some(10), Some(10)
    )).runAsync.futureValue
    val c = db.selectContent("test", "").runAsync.futureValue
    c should not be empty
    c.get.ttlLeft should not be empty
    c.get.ttl should contain(10)
    c.get.ttlLeft.get should be > 0
    c.get.ttlLeft.get should be <= 10
  }

  it should "insert using ttl if specified for collection item" in {
    cleanUpCassandra()
    db.insertContent(Content(
      "test~", "x1", 1l, List.empty, None, None, None, Some("{}"), new Date(), None, Some(10), Some(10)
    )).runAsync.futureValue
    val c = db.selectContent("test~", "x1").runAsync.futureValue
    c should not be empty
    c.get.ttl should contain(10)
    c.get.ttlLeft.get should be > 0
    c.get.ttlLeft.get should be <= 10
  }
}
