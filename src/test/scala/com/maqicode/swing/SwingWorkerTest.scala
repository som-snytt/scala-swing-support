package com.maqicode.swing

import java.util.concurrent.{CyclicBarrier, ExecutionException, TimeUnit}

import org.junit.Test
import org.junit.Assert._

//import com.maqicode.junit._

class SwingWorkerTest extends UnitTest with Shoulder {

  val Timeout: Long = 1000
  val Pause: Long = 10

  def waitFor(b: CyclicBarrier) {
    b.await(Timeout, TimeUnit.MILLISECONDS)
  }

  def pause() { try { Thread.sleep(Pause) } catch { case i: InterruptedException => } }

  def isEDT = java.awt.EventQueue.isDispatchThread

  @Test def futureRuns() {
    val barrier = new CyclicBarrier(2)
    val sut = new SwingWorker[Int, Int]() {
      override def doInBackground(): Int = 3
      override def done() {
        super.done()
        waitFor(barrier)
        //println("Good job!")
      }
    }
    sut.execute
    waitFor(barrier)
    val result: Int = sut.get
    result should equal (3)
  }

  @Test def initialStateIsPending() {
    val sut = new SwingWorker[Nothing, Nothing]() {
      override def doInBackground(): Nothing = { fail() }
    }
    sut.state should equal (SwingWorker.States.Pending)
  }

  @Test(expected = classOf[IllegalArgumentException]) def progressIsCheckedLow() {
    val sut = new SwingWorker[Int, Nothing]() {
      override def doInBackground(): Int = { progress = -1; 7 }
    }
    try {
      sut.get
    } catch {
      case e: ExecutionException => throw e.getCause
      case t => throw t
    }
  }

  @Test(expected = classOf[IllegalArgumentException]) def progressIsCheckedHigh() {
    val sut = new SwingWorker[Int, Nothing]() {
      override def doInBackground(): Int = { progress = 101; 7 }
    }
    try {
      sut.get
    } catch {
      case e: ExecutionException => throw e.getCause
      case t => throw t
    }
  }

  @Test def progressIsSet() {
    import java.util.concurrent.CyclicBarrier
    val barrier = new CyclicBarrier(2)
    val sut = new SwingWorker[Int, Int]() {
      override def doInBackground(): Int = { progress = 100; 3 }
      override def done() {
        waitFor(barrier)
      }
    }
    sut.execute
    waitFor(barrier)
    sut.progress should equal (100)
  }

  @Test def progressEmitsEvent() {
    import java.util.concurrent.CyclicBarrier
    val barrier = new CyclicBarrier(2)
    val sut = new SwingWorker[Int, Int]() {
      override def doInBackground(): Int = { progress = 100; 3 }
      override def done() {
        barrier.await()
      }
    }
    import swing.Reactor
    import concurrent.SyncVar
    val listener = new Reactor {
      var updated: SyncVar[Boolean] = new SyncVar
      updated set false
      reactions += {
        case ProgressUpdate(a,b) => assert(isEDT); updated set true
        case x => //println(x+" on? " + java.awt.EventQueue.isDispatchThread)
        //case _ => // ignore State changes
      }
      listenTo(sut)
    }
    sut.execute
    barrier.await()
    sut.progress should equal (100)
    val result = listener.updated.get(Timeout)
    assert(result.isDefined)
    result.getOrElse(false) should equal (true)
  }

  @Test def progressEventsAreCoalesced() {
    for (i <- 0 to 50) _progressEventsAreCoalesced()
  }
  def _progressEventsAreCoalesced() {
    import java.util.concurrent.CyclicBarrier
    val barrier = new CyclicBarrier(2)
    val sut = new SwingWorker[Int, Int]() {
      //def progressOf(x: Int) = if (x < 0) 0 else if (x > 100) 100 else x
      def progressOf(x: Int) = x.min(100).max(0)
      override def doInBackground(): Int = {
        var p: Int = 0
        // simulate some work with progress
        while (p <= 1000) {
          pause()
          for (i <- 0 to 7) {
            progress = progressOf(p/10)
            p += 3
          }
        }
        7
      }
      override def done() {
        waitFor(barrier)
      }
    }
    import swing.Reactor
    import concurrent.SyncVar
    val listener = new Reactor {
      var updated: SyncVar[Boolean] = new SyncVar
      import collection.mutable._
      val events = new ArrayBuffer[ProgressUpdate] with SynchronizedBuffer[ProgressUpdate]
      updated set false
      reactions += {
        case p: ProgressUpdate => assert(isEDT); updated set true; events += p
        case x => //println(x+" on? " + java.awt.EventQueue.isDispatchThread)
        //case _ => // ignore State changes
      }
      listenTo(sut)
    }
    sut.execute
    barrier.await()
    sut.progress should equal (100)
    assert(listener.events.length > 0)
    // starts at zero
    assert(listener.events(0).oldValue == 0)
    assert(listener.events.last.newValue == 100)
    println("Got events " + listener.events.length)
    println("Got events " + listener.events)
    verifyNoRaceOnAdd(listener.events)
  }

  private def verifyNoRaceOnAdd(events: Seq[ProgressUpdate]) {
    var i: Int = 0
    while (i < events.length - 1) {
      assert(events(i).newValue == events(i+1).oldValue)
      i += 1
    }
  }

  @Test def emitsIntermediateResults() {
    import java.util.concurrent.CyclicBarrier
    val barrier = new CyclicBarrier(2)
    val sut = new SwingWorker[Int, Int]() {
      override def doInBackground(): Int = { publish(34); publish(51); progress = 100; 3 }
      override def done() {
        waitFor(barrier)
      }
      var results: List[Int] = List()
      override def process(v: Seq[Int]) {
        assert(java.awt.EventQueue.isDispatchThread)
        results ++= v
      }
    }
    import swing.Reactor
    import concurrent.SyncVar
    val listener = new Reactor {
      var updated: SyncVar[Boolean] = new SyncVar
      reactions += {
        case ProgressUpdate(a,b) => updated set true
        case _ => // ignore State changes
      }
      listenTo(sut)
      def isUpdated = updated.isSet && updated.get == true
    }
    sut.execute
    waitFor(barrier)
    sut.progress should equal (100)
    val result = listener.updated.get(Timeout)
    assert(result.isDefined)
    result.getOrElse(false) should equal (true)
    sut.results.length should equal (2)
  }

  @Test def doneIsCalledAfterException() {
    import java.util.concurrent.CyclicBarrier
    val barrier = new CyclicBarrier(2)
    val sut = new SwingWorker[Int, Int]() {
      import concurrent.SyncVar
      val wasDone: SyncVar[Boolean] = new SyncVar
      wasDone set false
      override def doInBackground(): Int = { throw new RuntimeException }
      override def done() {
        wasDone set true
        waitFor(barrier)
      }
    }
    sut.execute
    waitFor(barrier)
    assert(sut.wasDone.get)
    //sut.get // hangs
  }

  @Test def coalescerEmpty() {
    val sut = new MyCoalescer
    sut.received.length should equal (0)
    sut.run()
    sut.received.length should equal (0)
  }

  @Test def coalescerGathers() {
    val sut = new MyCoalescer
    sut += "hello"
    sut.run()
    sut.received.length should equal (1)
    sut += ("hello", "world")
    sut.run()
    sut.received.length should equal (3)
  }

  import collection.mutable.ListBuffer
  import concurrent.TaskRunner

  class MyCoalescer extends TaskList[String](NonRunner) {
    val received = new ListBuffer[String]
    override protected def process(what: Seq[String]) {
      received ++= what
    }
  }

  object NonRunner extends RunnableTaskRunner {
    def execute[T](task: Task[T]) { }
  }

  @inline final private def required(requirement: Boolean, message: => Any) {
    if (!requirement) throw new IllegalStateException("requirement failed: "+ message)
  }
}
