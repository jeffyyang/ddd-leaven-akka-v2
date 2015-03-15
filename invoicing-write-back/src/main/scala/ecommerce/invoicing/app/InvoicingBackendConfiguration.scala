package ecommerce.invoicing.app

import java.net.InetAddress

import akka.actor._
import com.typesafe.config.Config
import ecommerce.invoicing.{Invoice, InvoicingSaga}
import org.json4s.Formats
import org.slf4j.Logger
import org.slf4j.LoggerFactory._
import pl.newicom.dddd.actor.{CreationSupport, PassivationConfig}
import pl.newicom.dddd.aggregate.AggregateRootActorFactory
import pl.newicom.dddd.cluster._
import pl.newicom.dddd.eventhandling.EventPublisher
import pl.newicom.dddd.messaging.event.DomainEventMessage
import pl.newicom.dddd.process.SagaSupport._
import pl.newicom.dddd.process.{SagaActorFactory, SagaManager}
import pl.newicom.eventstore.EventstoreSubscriber

import scala.io.Source
import scala.util.Try

trait LocalPublisher extends EventPublisher {
  this: Actor =>
  lazy val _log: Logger = getLogger(this.getClass.getName)

  override def publish(em: DomainEventMessage): Unit = {
    context.system.eventStream.publish(em.event)
    _log.debug(s"Published: $em")
  }
}

trait InvoicingBackendConfiguration {

  def log: Logger
  def config: Config
  implicit def system: ActorSystem
  def creationSupport = implicitly[CreationSupport]

  def invoiceOffice: ActorPath


  //
  // Invoicing
  //
  implicit object InvoiceARFactory extends AggregateRootActorFactory[Invoice] {
    override def props(pc: PassivationConfig) = Props(new Invoice(pc) with LocalPublisher)
  }
  implicit object InvoiceShardResolution extends DefaultShardResolution[Invoice]
  implicit object InvoicingSagaShardResolution extends DefaultShardResolution[InvoicingSaga]

  implicit object InvoicingSagaActorFactory extends SagaActorFactory[InvoicingSaga] {
    def props(pc: PassivationConfig): Props = {
      Props(new InvoicingSaga(pc, invoiceOffice))
    }
  }

  //
  // SagaManager factory
  //

  implicit lazy val sagaManagerFactory: SagaManagerFactory = (sagaConfig, sagaOffice) => {
    new SagaManager(sagaConfig, sagaOffice) with EventstoreSubscriber {
      override implicit val formats: Formats = config.serializationHints ++ defaultFormats
    }
  }

  def seeds(config: Config) = {
    // Read cluster seed nodes from the file specified in the configuration
    Try(config.getString("app.cluster.seedsFile")).toOption match {
      case Some(seedsFile) =>
        // Seed file was specified, read it
        log.info(s"reading seed nodes from file: $seedsFile")
        Source.fromFile(seedsFile).getLines().map { address =>
          AddressFromURIString.parse(s"akka.tcp://invoicing@$address")
        }.toList
      case None =>
        // No seed file specified, use this node as the first seed
        log.info("no seed file found, using default seeds")
        val port = config.getInt("app.port")
        val localAddress = Try(config.getString("app.host"))
          .toOption.getOrElse(InetAddress.getLocalHost.getHostAddress)
        List(AddressFromURIString.parse(s"akka.tcp://invoicing@$localAddress:$port"))
    }
  }

}
