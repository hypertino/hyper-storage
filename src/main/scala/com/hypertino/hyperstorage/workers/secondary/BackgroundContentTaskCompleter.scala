/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.hyperstorage.workers.secondary

import java.util.UUID

import akka.actor.ActorRef
import akka.pattern.ask
import com.datastax.driver.core.utils.UUIDs
import com.hypertino.binders.value.{Null, Number, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{DynamicRequest, _}
import com.hypertino.hyperbus.serialization.MessageReader
import com.hypertino.hyperstorage.api._
import com.hypertino.hyperstorage.db.{Transaction, _}
import com.hypertino.hyperstorage.indexing.{IndexLogic, ItemIndexer}
import com.hypertino.hyperstorage.metrics.Metrics
import com.hypertino.hyperstorage.sharding.WorkerTaskResult
import com.hypertino.hyperstorage.utils.FutureUtils
import com.hypertino.hyperstorage.workers.primary.{PrimaryContentTask, PrimaryWorkerTaskResult}
import com.hypertino.hyperstorage.{ResourcePath, _}
import com.hypertino.metrics.MetricsTracker
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, duration}
import scala.util.Success
import scala.util.control.NonFatal

@SerialVersionUID(1L) case class BackgroundContentTask(ttl: Long, documentUri: String, expectsResult: Boolean) extends SecondaryTaskTrait {
  def key = documentUri
}

@SerialVersionUID(1L) case class BackgroundContentTaskResult(documentUri: String, transactions: Seq[UUID])

@SerialVersionUID(1L) case class BackgroundContentTaskNoSuchResourceException(documentUri: String) extends RuntimeException(s"No such resource: $documentUri") with SecondaryTaskError

@SerialVersionUID(1L) case class BackgroundContentTaskFailedException(documentUri: String, reason: String) extends RuntimeException(s"Background task for $documentUri is failed: $reason") with SecondaryTaskError

trait BackgroundContentTaskCompleter extends ItemIndexer with SecondaryWorkerBase {
  def hyperbus: Hyperbus
  def db: Db
  def tracker: MetricsTracker
  implicit def scheduler: Scheduler

  def deleteIndexDefAndData(indexDef: IndexDef): Future[Unit]

  def executeBackgroundTask(owner: ActorRef, task: BackgroundContentTask): Future[WorkerTaskResult] = {
    try {
      val ResourcePath(documentUri, itemId) = ContentLogic.splitPath(task.documentUri)
      if (!itemId.isEmpty) {
        throw new IllegalArgumentException(s"Background task key ${task.key} doesn't correspond to $documentUri")
      }
      else {
        tracker.timeOfFuture(Metrics.SECONDARY_PROCESS_TIME) {
          db.selectContentStatic(task.documentUri) flatMap {
            case None ⇒
              logger.warn(s"Didn't found resource to background complete, dismissing task: $task")
              Future.failed(BackgroundContentTaskNoSuchResourceException(task.documentUri))
            case Some(content) ⇒
              try {
                logger.debug(s"Background task for $content")

                completeTransactions(task, content, owner, task.ttl)
              } catch {
                case e: Throwable ⇒
                  logger.error(s"Background task $task didn't complete", e)
                  Future.failed(e)
              }
          }
        }
      }
    }
    catch {
      case e: Throwable ⇒
        logger.error(s"Background task $task didn't complete", e)
        Future.failed(e)
    }
  }

  // todo: split & refactor method
  private def completeTransactions(task: BackgroundContentTask,
                                   content: ContentStatic,
                                   owner: ActorRef,
                                   ttl: Long): Future[WorkerTaskResult] = {
    if (content.transactionList.isEmpty) {
      Future.successful(WorkerTaskResult(task.key, task.group, BackgroundContentTaskResult(task.documentUri, Seq.empty)))
    }
    else {
      selectIncompleteTransactions(content) flatMap { incompleteTransactions ⇒
        val updateIndexFuture: Future[Any] = updateIndexes(content, incompleteTransactions, owner, ttl).runAsync
        updateIndexFuture.flatMap { _ ⇒
          FutureUtils.serial(incompleteTransactions) { it ⇒
            val event = it.unwrappedBody
            val viewTask: Task[Unit] = event.headers.hrl.location match {
              case l if l == ViewPut.location ⇒
                val templateUri = event.headers.getOrElse(TransactionLogic.HB_HEADER_TEMPLATE_URI, Null).toString
                val filter = event.headers.getOrElse(TransactionLogic.HB_HEADER_FILTER, Null) match {
                  case Null ⇒ None
                  case other ⇒ Some(other.toString)
                }
                Task.fromFuture(db.insertViewDef(ViewDef(key="*", task.documentUri, templateUri, filter)))
              case l if l == ViewDelete.location ⇒
                Task.fromFuture(db.deleteViewDef(key="*", task.documentUri))
              case _ ⇒
                Task.unit
            }

            viewTask
              .flatMap( _ ⇒ hyperbus.publish(event))
              .runAsync
              .flatMap{ publishResult ⇒
              logger.debug(s"Event $event is published with result $publishResult")

              db.completeTransaction(it.transaction) map { _ ⇒
                logger.debug(s"${it.transaction} is complete")

                it.transaction
              }
            }
          }
        } map { updatedTransactions ⇒
          WorkerTaskResult(task.key, task.group, BackgroundContentTaskResult(task.documentUri, updatedTransactions.map(_.uuid)))
        } recover {
          case e: Throwable ⇒
            logger.error(s"Task failed: $task",e)
            WorkerTaskResult(task.key, task.group, BackgroundContentTaskFailedException(task.documentUri, e.toString))
        } andThen {
          case Success(WorkerTaskResult(_, _, BackgroundContentTaskResult(documentUri, updatedTransactions))) ⇒
            logger.debug(s"Removing completed transactions $updatedTransactions from $documentUri")
            db.removeCompleteTransactionsFromList(documentUri, updatedTransactions.toList) recover {
              case e: Throwable ⇒
                logger.error(s"Can't remove complete transactions $updatedTransactions from $documentUri", e)
            }
        }
      }
    }
  }

  private def selectIncompleteTransactions(content: ContentStatic): Future[Seq[UnwrappedTransaction]] = {
    import ContentLogic._
    val transactionsFStream = content.transactionList.toStream.map { transactionUuid ⇒
      val quantum = TransactionLogic.getDtQuantum(UUIDs.unixTimestamp(transactionUuid))
      db.selectTransaction(quantum, content.partition, content.documentUri, transactionUuid)
    }
    FutureUtils.collectWhile(transactionsFStream) {
      case Some(transaction) ⇒ UnwrappedTransaction(transaction)
    } map (_.reverse)
  }

  private def updateIndexes(contentStatic: ContentStatic,
                            incompleteTransactions: Seq[UnwrappedTransaction],
                            owner: ActorRef,
                            ttl: Long): Task[Any] = {
    if (ContentLogic.isCollectionUri(contentStatic.documentUri)) {
      val idFieldName = ContentLogic.getIdFieldName(contentStatic.documentUri)
      val isCollectionDelete = incompleteTransactions.exists { it ⇒
        it.transaction.itemId.isEmpty && it.unwrappedBody.headers.method == Method.FEED_DELETE
      }

      // todo: cache index meta
      val indexDefsTask = createIndexFromTemplateOrLoad(contentStatic.documentUri).memoize

      if (isCollectionDelete) {
        // todo: what if after collection delete, there is a transaction with insert?
        // todo: delete view if collection is deleted
        // todo: cache index meta
        indexDefsTask
          .flatMap { indexDefs ⇒
            Task.wander(indexDefs) { indexDef ⇒
              logger.debug(s"Removing index $indexDef")
              Task.fromFuture(deleteIndexDefAndData(indexDef))
            }
          }
      }
      else {
        // todo: refactor, this is crazy
        val itemIds = incompleteTransactions.collect {
          case it if it.transaction.itemId.nonEmpty ⇒
            import com.hypertino.binders.json.JsonBinders._
            val obsoleteMap = it.transaction.obsoleteIndexItems.map(_.parseJson[Map[String, Map[String, Value]]])
            val obsoleteSeq = obsoleteMap.map(_.map(kv ⇒ kv._1 → kv._2.toSeq).toSeq).getOrElse(Seq.empty)
            it.transaction.itemId → obsoleteSeq
        }.groupBy(_._1).mapValues(_.map(_._2)).map(kv ⇒ kv._1 → kv._2.flatten)

        Task.traverse(itemIds.keys) { itemId ⇒
          // todo: cache content
          val contentTask = Task.eval(Task.fromFuture{
            logger.debug(s"Looking for content ${contentStatic.documentUri}/$itemId to index/update view")

            db.selectContent(contentStatic.documentUri, itemId)
          }).flatten.memoize
          val lastTransaction = incompleteTransactions.filter(_.transaction.itemId == itemId).last
          logger.debug(s"Update view/index for ${contentStatic.documentUri}/$itemId, lastTransaction=$lastTransaction, contentTask=$contentTask")

          updateView(contentStatic.documentUri + "/" + itemId, contentTask, lastTransaction, owner, ttl)
            .flatMap { _ ⇒
              indexDefsTask
                .flatMap { indexDefs ⇒
                  Task.traverse(indexDefs) { indexDef ⇒
                    logger.debug(s"Indexing content ${contentStatic.documentUri}/$itemId for $indexDef")

                    Task.fromFuture(db.selectIndexContentStatic(indexDef.tableName, indexDef.documentUri, indexDef.indexId))
                      .flatMap { indexContentStaticO ⇒
                        logger.debug(s"Index $indexDef static data: $indexContentStaticO")
                        val countBefore: Long = indexContentStaticO.flatMap(_.count).getOrElse(0l)
                        // todo: refactor, this is crazy
                        val seq: Seq[Seq[(String, Value)]] = itemIds(itemId).filter(_._1 == indexDef.indexId).map(_._2)
                        val deleteObsoleteFuture = FutureUtils.serial(seq) { s ⇒
                          db.deleteIndexItem(indexDef.tableName, indexDef.documentUri, indexDef.indexId, itemId, s)
                        }.map { deleted: Seq[Long] ⇒
                          Math.max(countBefore - deleted.sum, 0)
                        }

                        contentTask
                          .flatMap {
                            case Some(item) if !item.isDeleted.contains(true) ⇒
                              Task.fromFuture(deleteObsoleteFuture.flatMap { countAfterDelete ⇒
                                val count = if (indexDef.status == IndexDef.STATUS_NORMAL) Some(countAfterDelete + 1) else None
                                indexItem(indexDef, item, idFieldName, count).map(_._2)
                              })

                            case _ ⇒
                              logger.debug(s"No content to index for ${contentStatic.documentUri}/$itemId")
                              Task.now(false)
                          }
                          .flatMap {
                            case true ⇒
                              Task.now(Unit)

                            case false ⇒
                              Task.fromFuture(
                                deleteObsoleteFuture.flatMap { countAfterDelete ⇒
                                  val revision = incompleteTransactions.map(t ⇒ t.transaction.revision).max
                                  db.updateIndexRevisionAndCount(indexDef.tableName, indexDef.documentUri, indexDef.indexId, revision, countAfterDelete)
                                })
                          }
                      }
                  }
                }
            }
        }
      }
    } else {
      val contentTask = Task.eval(Task.fromFuture(db.selectContent(contentStatic.documentUri, ""))).flatten.memoize
      updateView(contentStatic.documentUri,contentTask,incompleteTransactions.last, owner, ttl)
    }
  }

  private def createIndexFromTemplateOrLoad(documentUri: String): Task[List[IndexDef]] = {
    Task.zip2(
      Task.eval(Task.fromFuture(db.selectIndexDefs(documentUri).map(_.toList))).flatten,
      Task.eval(Task.fromFuture(db.selectTemplateIndexDefs().map(_.toList))).flatten
    ).flatMap { case (existingIndexes, templateDefs) ⇒
      val newIndexTasks = templateDefs.filterNot(t ⇒ existingIndexes.exists(_.indexId == t.indexId)).map { templateDef ⇒
        ContentLogic.pathAndTemplateToId(documentUri, templateDef.templateUri).map { _ ⇒
          Task.fromFuture(insertIndexDef(documentUri, templateDef.indexId, templateDef.sortByParsed, templateDef.filter, templateDef.materialize))
            .map(Some(_))
        } getOrElse {
          Task.now(None) // todo: cache that this document doesn't match to template
        }
      }
      Task.gatherUnordered(newIndexTasks).map { newIndexes ⇒
        existingIndexes ++ newIndexes.flatten
      }
    }
  }

  private def updateView(path: String,
                         contentTask: Task[Option[Content]],
                         lastTransaction: UnwrappedTransaction,
                         owner: ActorRef,
                         ttl: Long): Task[Any] = {
    val viewDefsTask = Task.eval(Task.fromFuture(db.selectViewDefs().map(_.toList))).flatten
    viewDefsTask.flatMap { viewDefs ⇒
      Task.wander(viewDefs) { viewDef ⇒
        ContentLogic.pathAndTemplateToId(path, viewDef.templateUri).map { id ⇒
          implicit val mcx = lastTransaction.unwrappedBody
          if (lastTransaction.unwrappedBody.headers.method == Method.FEED_DELETE) {
            deleteViewItem(viewDef.documentUri, id, owner, ttl)
          }
          else {
            contentTask.flatMap {
              case Some(content) ⇒
                val matches = viewDef.filter.map { filter ⇒
                  try {
                    IndexLogic.evaluateFilterExpression(filter, content.bodyValue)
                  } catch {
                    case e: Throwable ⇒
                      logger.debug(s"Can't evaluate expression: `$filter` for $path", e)
                      false
                  }
                } getOrElse {
                  true
                }
                if (matches) {
                  addViewItem(viewDef.documentUri, id, content, owner, ttl)
                } else {
                  deleteViewItem(viewDef.documentUri, id, owner, ttl)
                }
              case None ⇒ // удалить
                deleteViewItem(viewDef.documentUri, id, owner, ttl)
            }
          }
        } getOrElse {
          Task.unit // todo: cache that this document doesn't match to template
        }
      }
    }
  }


  private def addViewItem(documentUri: String, itemId: String, content: Content, owner: ActorRef, ttl: Long)
                         (implicit mcx: MessagingContext): Task[Any] = {
    implicit val timeout = akka.util.Timeout(Duration(ttl - System.currentTimeMillis() + 3000, duration.MILLISECONDS))

    val contentTtl = content.realTtl
    val headers = if (contentTtl>0) {
      Headers(HyperStorageHeader.HYPER_STORAGE_TTL → Number(contentTtl))
    } else {
      Headers.empty
    }

    Task.fromFuture(owner ? PrimaryContentTask(
      documentUri,
      ttl,
      ContentPut(
        documentUri + "/" + itemId,
        DynamicBody(content.bodyValue),
        headers=headers
      ).serializeToString,
      expectsResult = true,
      isClientOperation = false
    )).map{
      _ ⇒ handlePrimaryWorkerTaskResult
    }
  }

  private def deleteViewItem(documentUri: String, itemId: String, owner: ActorRef, ttl: Long)(implicit mcx: MessagingContext): Task[Any] = {
    implicit val timeout = akka.util.Timeout(Duration(ttl - System.currentTimeMillis() + 3000, duration.MILLISECONDS))
    Task.fromFuture(owner ? PrimaryContentTask(documentUri, ttl, ContentDelete(documentUri + "/" + itemId).serializeToString, expectsResult = true, isClientOperation = false)).map{
      _ ⇒ handlePrimaryWorkerTaskResult
    }
  }

  private def handlePrimaryWorkerTaskResult: PartialFunction[PrimaryWorkerTaskResult, Unit] = {
    case result: PrimaryWorkerTaskResult ⇒ MessageReader.fromString(result.content, StandardResponse.apply) match {
      case e: Throwable ⇒ throw e
    }
  }
}

case class UnwrappedTransaction(transaction: Transaction, unwrappedBody: DynamicRequest)

object UnwrappedTransaction {
  def apply(transaction: Transaction): UnwrappedTransaction = UnwrappedTransaction(
    transaction, MessageReader.fromString(transaction.body, DynamicRequest.apply)
  )
}
