/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter15

import akka.actor._
import akka.pattern.pipe
import akka.persistence.{ PersistentActor, RecoveryCompleted }

import scala.concurrent.Future

object Saga {

  trait Account {
    def withdraw(amount: BigDecimal, id: Long): Future[Unit]

    def deposit(amount: BigDecimal, id: Long): Future[Unit]
  }

  final case class Transfer(amount: BigDecimal, x: Account, y: Account)

  sealed trait Event

  final case class TransferStarted(amount: BigDecimal, x: Account, y: Account) extends Event

  case object MoneyWithdrawn extends Event

  case object MoneyDeposited extends Event

  case object RolledBack extends Event

  class TransferSaga(id: Long) extends PersistentActor {

    import context.dispatcher

    override val persistenceId: String = s"transaction-$id"

    override def receiveCommand: Receive = {
      case Transfer(amount, x, y) ⇒
        persist(TransferStarted(amount, x, y))(withdrawMoney)
    }

    def withdrawMoney(t: TransferStarted): Unit = {
      t.x.withdraw(t.amount, id).map(_ ⇒ MoneyWithdrawn).pipeTo(self)
      context.become(awaitMoneyWithdrawn(t.amount, t.x, t.y))
    }

    def awaitMoneyWithdrawn(amount: BigDecimal, x: Account, y: Account): Receive = {
      case m @ MoneyWithdrawn ⇒ persist(m)(_ ⇒ depositMoney(amount, x, y))
    }

    def depositMoney(amount: BigDecimal, x: Account, y: Account): Unit = {
      y.deposit(amount, id) map (_ ⇒ MoneyDeposited) pipeTo self
      context.become(awaitMoneyDeposited(amount, x))
    }

    def awaitMoneyDeposited(amount: BigDecimal, x: Account): Receive = {
      case Status.Failure(ex) ⇒
        x.deposit(amount, id) map (_ ⇒ RolledBack) pipeTo self
        context.become(awaitRollback)
      case MoneyDeposited ⇒
        persist(MoneyDeposited)(_ ⇒ context.stop(self))
    }

    def awaitRollback: Receive = {
      case RolledBack ⇒
        persist(RolledBack)(_ ⇒ context.stop(self))
    }

    override def receiveRecover: Receive = {
      var start: TransferStarted = null
      var last: Event = null

      {
        case t: TransferStarted ⇒
          start = t
          last = t
        case e: Event ⇒ last = e
        case RecoveryCompleted ⇒
          last match {
            case null               ⇒ // wait for initialization
            case t: TransferStarted ⇒ withdrawMoney(t)
            case MoneyWithdrawn     ⇒ depositMoney(start.amount, start.x, start.y)
            case MoneyDeposited     ⇒ context.stop(self)
          }
      }
    }

  }

}
