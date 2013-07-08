package com.github.pk11.rnio.scala

import com.github.pk11.rnio
import com.github.pk11.rnio.HttpServer.{ HTTPRequest, HTTPResponse }
import concurrent.{ Future, ExecutionContext }
import java.util.concurrent.Executor
import java.nio.channels._
import reactor.function._
import reactor.event.Event
import reactor.core.Reactor
import reactor.core.composable.Promises
import concurrent.ExecutionContext.Implicits.global

// ~~ helpers

object SAM {
  implicit def fn2Function[T, V](fn: (T) => V) = new Function[T, V] { def apply(t: T): V = fn(t) }
  implicit def fn2Supplier[T](fn: () => T) = new Supplier[T] { def get(): T = fn() }
  implicit def fn2Consumer[T <: Event[_]](fn: T => Unit) = new Consumer[T] { def accept(t: T): Unit = fn(t) }
}

class ReactorExecutionContext(r: Reactor) extends ExecutionContext with Executor {

  /**
   * Runs a block of code on this execution context.
   */

  def execute(runnable: Runnable): Unit = {
    import SAM.fn2Consumer
    val handler: (Event[Runnable]) => Unit = (ev) => ev.getData.run()
    Promises.success(Event.wrap(runnable))
      .reactor(r)
      .get()
      .consume(handler)
      .resolve()
  }

  /**
   * Reports that an asynchronous computation failed.
   */
  def reportFailure(cause: Throwable): Unit = {
    r.notify("failure", Event.wrap(cause))
  }

  /**
   * Prepares for the execution of a task. Returns the prepared
   *  execution context. A valid implementation of `prepare` is one
   *  that simply returns `this`.
   */
  override def prepare(): ExecutionContext = this

}

//~~ main server implementation ~~

class HttpServer(port: Int) extends rnio.HttpServer(port) {
  import SAM.fn2Consumer
  def run(handlerFn: (HTTPRequest, HTTPResponse) => Future[HTTPResponse]): Unit = {
    while (true) {
      selector.selectNow()
      //we need to remove the processed key from iterator so converting the java iterator to a scala one
      //does not buy us much here
      val iter = selector.selectedKeys.iterator
      while (iter.hasNext) {
        val key = iter.next
        iter.remove()
        if (key.isValid) {
          try {
            if (key.isAcceptable) {
              val client = server.accept
              client.configureBlocking(false)
              client.register(selector, SelectionKey.OP_READ)
            } else if (key.isReadable) {
              val channel = key.channel.asInstanceOf[SocketChannel]
              val handler: (Event[SocketChannel]) => Unit = (ev) => {
                val c = ev.getData
                if (c.isOpen) {
                  handlerFn(new HTTPRequest(read(toBuffer(c))), new HTTPResponse(c))
                    .map { res =>
                      res.end()
                      c.close()
                    }
                }
              }
              //this is non-blocking
              Promises.success(Event.wrap(channel))
                .reactor(reactor)
                .get()
                .consume(handler)
                .resolve()
              /*
              reactor.on(Functions.$("failure"), { (ev: Event[Throwable]) => ev.getData.printStackTrace() })
              val context = new ReactorExecutionContext(reactor)
              Future {
                if (channel.isOpen) {
                  handlerFn(new HTTPRequest(read(toBuffer(channel))), new HTTPResponse(channel))
                    .map { res =>
                      res.end()
                      channel.close()
                    }
                }
              }(context)
             */
            }
          } catch {
            case ex: Throwable => {
              val s = key.channel
              println("client: " + s)
              ex.printStackTrace()
              s.close()
            }
          }
        }
      }
    }
  }
}

object DemoServer {
  def main(args: Array[String]): Unit = {
    val port = 9999
    println("server is launched at port:" + port)
    new HttpServer(port).run { (req, res) =>
      Future { res.content("Hello Scala Reactor!") }
    }
  }
}
