
package com.maqicode.swing

import actors._
import swing._
import swing.Swing._
import swing.event._

object SwingWorker {
  /** Event values for the "state" property. */
  object States extends Enumeration {
    val Pending, Started, Done = State
    private def State = new State
    class State protected[States] (i: Int, s: String) extends Val(i, s) with Event {
      def this() = this(nextId, null)
    }
  }
}

/** Close emulation of javax.swing.SwingWorker using actors facility. */
abstract class SwingWorker[A, B] extends Publisher {

  import SwingWorker.States._
  @volatile private var etat: State = Pending
  def state = etat
  private def state_=(s: State) { if (s != etat) { etat = s; publishEvent(s) } }

  // for running progress and intermediate value broadcast on EDT
  lazy val runner = new TaskList[Runnable](SwingTaskRunner) {
    override protected def process(a: Seq[Runnable]) {
      for (r <- a) r.run()
    }
  }
  // adds progress and publish tasks to runner
  lazy val submitTaskRunner = new RunnableTaskRunner {
    def execute[T](task: Task[T]) { runner += task } // piggyback on runner
  }
  // publishes ProgressUpdate (from EDT)
  lazy val progresser = new TaskList[Int](submitTaskRunner) {
    override protected def coalesce(in: Seq[Int]) = in.head :: in.last :: Nil
    override protected def process(a: Seq[Int]) {
      assert(a.length == 2)
      publish(ProgressUpdate(a(0), a(1)))
    }
  }
  // invokes user process method (from EDT) for intermediate results
  lazy val processor = new TaskList[B](submitTaskRunner) {
    override protected def process(a: Seq[B]) {
      SwingWorker.this.process(a)
    }
  }

  // progress
  @volatile private var progres: Int = 0
  def progress = progres
  /** Changing this value causes a ProgressUpdate event to be published from the EDT. */
  protected def progress_=(v: Int) {
    require(v >= 0 && v <= 100, "Progress not in range")
    // synchronized in case doInBackground makes multithreaded updates to progress
    // (and in that case the application may want to make additional guarantees, if necessary).
    // Here, we guarantee only that event[i].newValue == event[i+1].oldValue
    if (v != progres) synchronized {
      if (!listeners.isEmpty) progresser += (progres, v)
      progres = v;
    }
  }

  @volatile private var f: Future[Option[A]] = _
  @volatile private var e: Option[Throwable] = None
  /** Execute this worker on a daemon thread. */
  def execute(): Unit = synchronized {
    f = Futures.future {
      state = Started
      try {
        Some(doInBackground()) // result
      } catch {
        case t: Throwable => e = Some(t); None
      } finally {
        doDoneEDT()
        state = Done
      }
    }
  }

  /** Subclasses must define work to be done. */
  protected def doInBackground(): A

  /** Background process may publish intermediate results. */
  protected final def publish(chunks: B*) { processor ++= chunks }

  /** Subclasses may override this method to process intermediate results on EDT. */
  protected def process(chunks: Seq[B]) { /*empty*/ }

  /** Subclasses may override this method to be invoked on EDT when background process is done. */
  protected def done() { /*empty*/ }

  /** Get the final result, blocking until available. */
  def get = {
    // for now, we must execute to obtain a future
    synchronized {
      if (f == null) execute()
    }
    f().getOrElse {
      throw new java.util.concurrent.ExecutionException(e.ensuring(e.isDefined).get)
    }
  }

  // Run user done method on EDT
  private def doDoneEDT() {
    onEDT {
      done()
    }
  }

  // Only used when setting State
  private def publishEvent(e: Event) {
    import javax.swing.SwingUtilities
    if (SwingUtilities.isEventDispatchThread) {
      publish(e);
    } else {
      import SwingTaskRunner._  // for implicit conversion
      SwingTaskRunner execute (() => publish(e))
    }
  }
}

/** A TaskRunner for Runnables. */
abstract class RunnableTaskRunner extends scala.concurrent.TaskRunner {
  type Task[T] = Runnable
  implicit def functionAsTask[T](f: () => T): Task[T] = new Runnable {
    def run() { f() }
  }
  def shutdown() { }
}

/** A TaskRunner that schedules Runnables on the EDT. */
object SwingTaskRunner extends RunnableTaskRunner {
  import java.awt.EventQueue
  def execute[S](task: Task[S]) { EventQueue.invokeLater(task) }
}

import scala.collection.generic.Growable
import scala.collection.mutable.{Buffer, ListBuffer}

/** Collects items for later processing. This Runnable is executed by the supplied runner. */
abstract class TaskList[A](runner: RunnableTaskRunner) extends Growable[A] with Runnable {

  private val items: Buffer[A] = new ListBuffer[A]

  /** Add (more) items. On first add, submit this processor for execution. */
  def +=(a: A): this.type = synchronized[this.type] { val first = items.isEmpty; items += a; if (first) execute(); this }

  override def +=(a: A, b: A, rest: A*): this.type = synchronized[this.type] { items.+=(a,b,rest:_*); this }
  
  override def ++=(xs: TraversableOnce[A]): this.type = synchronized[this.type] { items.++=(xs); this }

  /** Clear the list of items. */
  def clear(): Unit = synchronized { items.clear() }

  private def execute() { runner.execute(this) }

  /** Run this processor, clearing the argument list. */
  override final def run() {
    process(args())
  }

  private def args(): Seq[A] = synchronized {
    val a = coalesce(items)
    items.clear()
    a
  }

  /** Copy the list of items for processing. Subclasses may override to coalesce the list. */
  protected def coalesce(in: Seq[A]): Seq[A] = in.take(in.size)

  /** Subclasses must process the (optionally coalesced) values. */
  protected def process(chunks: Seq[A]): Unit
}

/** Event emitted for progress updates. */
case class ProgressUpdate(oldValue: Int, newValue: Int) extends Event

