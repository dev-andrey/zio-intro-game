package net.degoes.zio

import zio.console._
import zio.duration._
import zio.stm.{ STM, TRef }
import zio.{ UIO, ZIO }

import scala.language.postfixOps

object StmSemaphore extends zio.App {
  type Semaphore = TRef[Int]

  object SemaphoreOps {
    def make(n: Int): UIO[Semaphore] = TRef.make(n).commit

    def acquire(semaphore: Semaphore, n: Int): UIO[Unit] =
      (for {
        permits <- semaphore.get
        _       <- STM.check(permits >= n)
        _       <- semaphore.set(permits - n)
      } yield ()).commit

    def release(semaphore: Semaphore, n: Int): UIO[Unit] =
      semaphore.update(_ + n).unit.commit
  }

  import SemaphoreOps._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    (for {
      semaphore <- make(2)
      work1 = SemaphoreOps
        .acquire(semaphore, 1)
        .bracket_(
          release(semaphore, 1),
          putStrLn("Taking a permit for 2 sec") *>
            ZIO.sleep(2 seconds) *>
            putStrLn("Releasing a permit")
        )
      work2 = SemaphoreOps
        .acquire(semaphore, 1)
        .bracket_(
          release(semaphore, 1),
          putStrLn("Fiber 2 - Sleeping") *>
            ZIO.sleep(2 second) *>
            putStrLn("Fiber 2 - Wake Up")
        )
      fiber1 <- ZIO.forkAll(List.fill(10)(work1))
      fiber2 <- ZIO.forkAll(List.fill(10)(work2))
      _      <- (fiber1 zip fiber2).join
    } yield 0) as 1
}

object StmPromise extends zio.App {
  type Promise[A] = TRef[Option[A]]

  object PromiseOps {

    def make[A]: UIO[Promise[A]] =
      TRef.make(None: Option[A]).commit

    def complete[A](promise: Promise[A], result: A): UIO[Boolean] =
      STM.atomically {
        for {
          value <- promise.get
          change <- value match {
                     case Some(_) => STM.succeed(false)
                     case None    => promise.set(Option(result)) *> STM.succeed(true)
                   }
        } yield change
      }

    def await[A](promise: Promise[A]): UIO[A] =
      promise.get.collect { case Some(a) => a }.commit
  }
  import PromiseOps._

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] =
    (for {
      promise <- make[Int]
      fiber1  <- (ZIO.sleep(5 second) *> putStrLn("Completing promise") *> complete(promise, 5)).fork
      result  <- await(promise)
      _       <- putStrLn(result.toString)
    } yield ()) as 0
}
