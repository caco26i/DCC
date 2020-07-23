package com.wavesplatform.events.repo

import java.nio.ByteBuffer
import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}

import cats.implicits._
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.events.protobuf.{BlockchainUpdated => PBBlockchainUpdated}
import com.wavesplatform.events.protobuf.serde._
import com.wavesplatform.events.{BlockAppended, BlockchainUpdated, MicroBlockAppended, MicroBlockRollbackCompleted, RollbackCompleted}
import com.wavesplatform.utils.ScorexLogging
import monix.eval.Task
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.OverflowStrategy.BackPressure
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject
import org.iq80.leveldb.DB

import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

class UpdatesRepoImpl(db: DB)(implicit val scheduler: Scheduler)
    extends UpdatesRepo.Read
    with UpdatesRepo.Write
    with UpdatesRepo.Stream
    with ScorexLogging {
  import UpdatesRepoImpl._

  // synchronization for state and db
  private val lock: ReadWriteLock = new ReentrantReadWriteLock()

  // no need to persist liquid state. Keeping it mutable in a class, like in node itself
  private[this] var liquidState: Option[LiquidState] = None

  // A buffered replayable stream of recent updates for synchronization.
  // A streaming subscriber will real leveldb + liquid state fully, then find exact place
  // in recent events to start from, and continue from there
  // todo buffer size establish
  // todo handle long rollback (big buffer? find tollback event?)
  val RECENT_UPDATES_BUFFER_SIZE  = 1024
  private[this] val recentUpdates = ConcurrentSubject.replayLimited[BlockchainUpdated](RECENT_UPDATES_BUFFER_SIZE)

  private[this] def sendToRecentUpdates(ba: BlockchainUpdated): Try[Unit] = {
    recentUpdates.onNext(ba) match {
      case Ack.Continue => Success(())
      case Ack.Stop     => Failure(new IllegalStateException("recentUpdates stream sent Ack.Stop"))
    }
  }

  private[this] def withReadLock[A](a: => A): A = {
    lock.readLock().lock()
    try {
      a
    } finally {
      lock.readLock().unlock()
    }
  }

  private[this] def withWriteLock[A](a: => A): A = {
    lock.writeLock().lock()
    try {
      a
    } finally {
      lock.writeLock().unlock()
    }
  }

  // Read
  override def height: Int = withReadLock {
    liquidState.map(_.keyBlock.toHeight).getOrElse(0)
  }

  override def updateForHeight(height: Int): Try[Option[BlockAppended]] = withReadLock {
    liquidState match {
      case Some(ls) if ls.keyBlock.toHeight == height =>
        log.debug(s"BlockchainUpdates extension requested liquid block at height $height")
        Success(Some(ls.solidify()))
      case Some(ls) if ls.keyBlock.toHeight < height =>
        log.debug(s"BlockchainUpdates extension requested non-existing block at height $height, current ${ls.keyBlock.toHeight}")
        Success(None)
      case _ =>
        val bytes = db.get(blockKey(height))
        if (bytes.isEmpty) {
          Success(None)
        } else {
          Try {
            log.debug(s"BlockchainUpdates extension parsing bytes from leveldb for height $height")
            PBBlockchainUpdated
              .parseFrom(bytes)
              .vanilla
              .toOption
              .collect { case ba: BlockAppended => ba }
          }
        }
    }
  }

  override def updatesRange(from: Int, to: Int): Try[Seq[BlockAppended]] = withReadLock {
    // todo error handling, limits and timeouts
    log.info("Requesting updatesRange")
    val cnt = to - from + 1
    Try(Await.result(stream(from).collect { case u: BlockAppended => u }.take(cnt).bufferTumbling(cnt).runAsyncGetLast, Duration.Inf).get)
  }

  // Write
  override def appendBlock(blockAppended: BlockAppended): Try[Unit] = withWriteLock {
    Try {
      liquidState.foreach { ls =>
        val solidBlock = ls.solidify()
        val key        = blockKey(solidBlock.toHeight)
        db.put(key, solidBlock.protobuf.toByteArray)
      }
      liquidState = Some(LiquidState(blockAppended, Seq.empty))
      sendToRecentUpdates(blockAppended)
    }
  }

  override def appendMicroBlock(microBlockAppended: MicroBlockAppended): Try[Unit] = withWriteLock {
    liquidState match {
      case Some(LiquidState(keyBlock, microBlocks)) =>
        liquidState = Some(LiquidState(keyBlock, microBlocks :+ microBlockAppended))
        sendToRecentUpdates(microBlockAppended)
      case None =>
        Failure(new IllegalStateException("Attempting to insert a microblock without a keyblock"))
    }
  }

  override def rollback(rollback: RollbackCompleted): Try[Unit] = ???

  override def rollbackMicroBlock(microBlockRollback: MicroBlockRollbackCompleted): Try[Unit] = ???

  // todo remove
  private[this] def dropLiquidState(afterId: Option[ByteStr]): Unit =
    (afterId, liquidState) match {
      case (Some(id), Some(LiquidState(keyBlock, microBlocks))) =>
        if (keyBlock.toId == id) {
          // rollback to key block
          liquidState = Some(LiquidState(keyBlock, Seq.empty))
        } else {
          // otherwise, rollback to a microblock
          val index = microBlocks.indexWhere(_.toId == id)
          if (index != -1) {
            liquidState = Some(LiquidState(keyBlock, microBlocks.dropRight(microBlocks.length - index - 1)))
          }
        }
      case (None, _) => liquidState = None
      case _         => ()
    }

  // Stream
  val BATCH_SIZE                = 10
  val BACK_PRESSURE_BUFFER_SIZE = 1000

  // I think the stream should be back-pressured
  // todo (maybe) parameterize strategy or at least buffer size
  // todo detect out-or-order events and throw exception
  override def stream(from: Int): Observable[BlockchainUpdated] = {
    // it guarantees to send solid and liquid states concatenated as they are at the moment of last call
    def syncTick(from: Option[Int]): Task[Option[(Seq[BlockchainUpdated], Option[Int])]] =
      from match {
        case None => Task.eval(None)
        case Some(f) =>
          Task.evalAsync {
            withReadLock {
              log.info(s"syncTick, $from, current height $height")
              val syncPhase = (height - f) <= BATCH_SIZE
              // if syncPhase take iterator, send all events, then send liquid state in its current form, then return None as next state

              val iterator = db.iterator()
              iterator.seek(blockKey(f))

              val res = Seq.newBuilder[BlockchainUpdated]
              res.sizeHint(BATCH_SIZE)

              @tailrec
              def go(remaining: Int): Unit = {
                if (remaining > 0 && iterator.hasNext) {
                  val next       = iterator.next()
                  val blockBytes = next.getValue
                  if (blockBytes.nonEmpty) {
                    PBBlockchainUpdated.parseFrom(blockBytes).vanilla match {
                      case Success(value) =>
                        res += value
                        go(remaining - 1)
                      case Failure(exception) => Task.raiseError(exception)
                    }
                  } else {
                    Task.raiseError(new IllegalStateException(s"Empty block bytes in the database at height ${fromBlockKey(next.getKey)}"))
                  }
                }
              }

              go(BATCH_SIZE)

              iterator.close()

              val lastHistoricalBatch = res.result()

              val liquidStateUpdates = if (syncPhase) {
                liquidState.toSeq.flatMap {
                  case LiquidState(keyBlock, microBlocks) =>
                    println(s"syncPhase, last from leveldb ${lastHistoricalBatch.last.toHeight}, liquidState at ${keyBlock.toHeight}")
                    Seq(keyBlock) ++ microBlocks
                }
              } else Seq.empty

              val nextState = if (syncPhase) None else lastHistoricalBatch.lastOption.map(_.toHeight + 1).orElse(from)

              log.info(s"syncTick over, $from, current (updated?) height $height")

              Some((lastHistoricalBatch ++ liquidStateUpdates, nextState))
            }
          }
      }

    val history: Observable[BlockchainUpdated] = Observable
      .unfoldEval(from.some)(syncTick)
      .flatMap(Observable.fromIterable) // this gets us all historical updates

    // todo do I need repeat? or smth like withLatestFrom
    val lastEvent = history.last.repeat

    // todo stream cancellation

    Observable(
      history,
      recentUpdates
        .zip(lastEvent)
        .dropWhile {
          case (recentUpd, lastHistorical) =>
            println(s"recent ${recentUpd.toHeight}, historical ${lastHistorical.toHeight}")
            recentUpd.toId != lastHistorical.toId
        }
        .tail
        .map(_._1)
    ).concat
//    todo async requests in Tasks
//    Task
//      .evalAsync {
//        def sendHistorical(from: Int): Unit = {
//          var batchStart = from
//          while (true) {
//            getRange(batchStart, batchStart + BATCH_SIZE - 1) match {
//              case Success(batch) =>
//                log.info(s"Requested batch from ${batch.head.toHeight} to ${batch.last.toHeight}")
//                for (ba <- batch) {
//                  val ack = subject.onNext(ba)
//                  ack match {
//                    case Ack.Continue => ()
//                    case Ack.Stop     => return
//                  }
//                }
//                if (batch.length < BATCH_SIZE) return
//                batchStart += BATCH_SIZE
//              case Failure(exception) =>
//                log.error("Failed to get range, [$from, ${from + BATCH_SIZE - 1}]")
//                subject.onError(exception)
//                return
//            }
//          }
//        }

//        sendHistorical(from)
  }
}

object UpdatesRepoImpl {
  private def blockKey(height: Int): Array[Byte] = ByteBuffer.allocate(8).putInt(height).array()

  private def fromBlockKey(key: Array[Byte]): Int = ByteBuffer.wrap(key).getInt
}