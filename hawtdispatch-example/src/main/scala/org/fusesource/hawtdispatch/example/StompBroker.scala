/**
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 */
package org.fusesource.hawtdispatch.example

import _root_.java.util.concurrent.TimeUnit
import _root_.java.util.LinkedList
import java.nio.channels.SelectionKey._
import org.fusesource.hawtdispatch.ScalaSupport._

import java.net.{InetAddress, InetSocketAddress}

import buffer._
import java.nio.channels.{ServerSocketChannel}
/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
object StompBroker {

  type HeaderMap = LinkedList[(AsciiBuffer, AsciiBuffer)]

  case class Delivery(headers:HeaderMap, content:Buffer) extends ServiceRetainer
  trait Producer {
    def setTargetQueue(queue:DispatchQueue):Unit
  }

  trait Consumer extends QueuedRetained {
    def deliver(delivery:Delivery)
  }
  

  def main(args:Array[String]) = {
    println("Starting stomp broker...")
    val broker = new StompBroker();
    println("Startup complete.")
    System.in.read
    println("Shutting down...")
    broker.close
    println("Shutdown complete.")
  }
}

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class StompBroker extends Queued {
  import StompBroker._

  val router = new Router[AsciiBuffer,Producer,Consumer](createSerialQueue("router"))
  val queue = createSerialQueue("broker")

    // Create the nio server socket...
  val channel = ServerSocketChannel.open();
  channel.configureBlocking(false);
  channel.socket().bind(address("0.0.0.0", 61613), 500);

  // Create a source attached to the server socket to deal with new connections...
  val accept_source = createSource(channel, OP_ACCEPT, queue);
  accept_source.setEventHandler(^{

    // Accept a new socket connection
    var socket = channel.accept();
    try {
      socket.configureBlocking(false);
      socket.socket.setSoLinger(true,0);
      var connection = new StompConnection(socket, router)
    } catch {
      case e:Exception=>
        socket.close
    }

  });
  accept_source.setCancelHandler(^{
    channel.close();
  });

  // Start listening for accept events..
  accept_source.resume();

  def close = {
    accept_source.cancel
    accept_source.release
    queue.release
  }

  private  def address(host: String, port: Int): InetSocketAddress = {
    return new InetSocketAddress(ip(host), port)
  }

  private def ip(host: String): InetAddress = {
    return InetAddress.getByName(host)
  }

  // Try to periodically re-balance connections so that consumers/producers
  // are on the same thread.
  val reblance = ^{
    router.each {
      (destination, routes, targets) =>
      // for now just move the producer to the consumer's thread..
      if( !targets.isEmpty ) {
        for( route <- routes ) {
          route.producer.setTargetQueue( targets.head.queue.getTargetQueue )
        }
      }

    }
    schedualRebalance
  }
  def schedualRebalance:Unit = router.queue.dispatchAfter(reblance, 1000, TimeUnit.MILLISECONDS)
//  schedualRebalance
}


