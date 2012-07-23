/*
 * Copyright 2012 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.core

import java.io.File
import java.util.concurrent.{TimeUnit, Exchanger}

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

class Example extends WordSpec with MustMatchers {
  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journaler = system.actorOf(Props(new Journaler(journalDir)))

    def createExampleComponent(journaler: ActorRef, destination: ActorRef) = Component(0, journaler)
      .addDefaultOutputChannelToSelf("self")
      .addReliableOutputChannelToActor("dest", destination)
      .setProcessor(outputChannels => system.actorOf(Props(new ExampleAggregator(outputChannels))))

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  "An event-sourced component" must {
    "recover state from stored event messages" in { fixture =>
      import fixture._

      val exchanger = new Exchanger[Message]
      val destination = system.actorOf(Props(new ExampleDestination(exchanger)))
      var component = createExampleComponent(journaler, destination)

      component.deliver()

      // send InputAvailable event to event-sourced component
      component.inputProducer ! InputAvailable("category-a", "input-1") // no response expected
      component.inputProducer ! InputAvailable("category-a", "input-2") // no response expected
      component.inputProducer ! InputAvailable("category-b", "input-7") // no response expected

      // await aggregation response by business logic to initial sender
      var future = component.inputProducer ? InputAvailable("category-a", "input-3")
      Await.result(future, timeout.duration) must be("aggregated 3 messages of category-a")

      // obtain output event message delivered to destination
      var delivered = exchanger.exchange(null, 5, TimeUnit.SECONDS)
      delivered.event must be(InputAggregated("category-a", List("input-1", "input-2", "input-3")))
      delivered.senderMessageId must be(Some("aggregated-1"))

      // now drop all in-memory state by creating a new component
      component = createExampleComponent(journaler, destination)

      // recover in-memory state by initializing the new component
      component.replay()
      component.deliver()

      // now trigger the next aggregation (2 messages of category-b missing)
      component.inputProducer ! InputAvailable("category-b", "input-8") // no response expected
      future = component.inputProducer ? InputAvailable("category-b", "input-9")

      // await next aggregation response by business logic to initial sender
      Await.result(future, timeout.duration) must be("aggregated 3 messages of category-b")

      // obtain next output event message delivered to destination
      delivered = exchanger.exchange(null, 5, TimeUnit.SECONDS)
      delivered.event must be(InputAggregated("category-b", List("input-7", "input-8", "input-9")))
      delivered.senderMessageId must be(Some("aggregated-2"))
    }
  }
}

// Example events
case class InputAvailable(category: String, input: String)
case class InputAggregated(category: String, inputs: List[String])

// Example event-sourced processor
class ExampleAggregator(outputChannels: Map[String, ActorRef]) extends Actor {
  var inputAggregatedCounter = 0
  var inputs = Map.empty[String, List[String]] // category -> inputs

  def receive = {
    case msg: Message => msg.event match {
      case InputAggregated(category, inputs) => {
        // count number of InputAggregated receivced
        inputAggregatedCounter = inputAggregatedCounter + 1
        // emit InputAggregated event to destination with sender message id containing the counted aggregations
        outputChannels("dest") ! msg.copy(senderMessageId = Some("aggregated-%d" format inputAggregatedCounter))
        // reply to initial sender that message has been aggregated
        msg.sender.foreach(_ ! "aggregated %d messages of %s".format(inputs.size, category))
      }
      case InputAvailable(category, input) => inputs = inputs.get(category) match {
        case Some(List(i2, i1)) => {
          // emit InputAggregated event to this processor's component (i.e. self) when 3 events of same category exist
          outputChannels("self") ! msg.copy(event = InputAggregated(category, List(i1, i2, input))); inputs - category
        }
        case Some(is) => inputs + (category -> (input :: is))
        case None     => inputs + (category -> List(input))
      }
    }
  }
}

class ExampleDestination(exchanger: Exchanger[Message]) extends Actor {
  def receive = {
    case msg: Message => { exchanger.exchange(msg); sender ! () }
  }
}
