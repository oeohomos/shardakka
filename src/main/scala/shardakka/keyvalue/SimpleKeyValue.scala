package shardakka.keyvalue

import java.util
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

import akka.actor._
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings, ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.pattern.ask
import akka.util.Timeout
import im.actor.serialization.ActorSerializer
import shardakka.{Codec, ShardakkaExtension, StringCodec}

import scala.collection.JavaConverters._
import scala.compat.java8.FunctionConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

private case object End

final case class SimpleKeyValueJava[A](underlying: SimpleKeyValue[A], defaultTimeout: Timeout) {

  import underlying.system.dispatcher

  /**
    * Asynchronously upsert
    *
    * @param key
    * @param value
    * @param timeout
    * @return
    */
  def upsert(key: String, value: A, timeout: Timeout): Future[Unit] = underlying.upsert(key, value)(timeout)

  /**
    * Synchronously upsert
    *
    * @param key
    * @param value
    * @param timeout
    */
  def syncUpsert(key: String, value: A, timeout: Timeout): Unit = Await.result(upsert(key, value, timeout), timeout.duration)

  /**
    * Synchronously upsert with default timeout
    * @param key
    * @param value
    */
  def syncUpsert(key: String, value: A): Unit = syncUpsert(key, value, defaultTimeout)

  /**
    * Asynchronously delete
    *
    * @param key
    * @param timeout
    * @return
    */
  def delete(key: String, timeout: Timeout): Future[Unit] = underlying.delete(key)(timeout)

  /**
    * Synchronously delete
    *
    * @param key
    * @param timeout
    */
  def syncDelete(key: String, timeout: Timeout): Unit = Await.result(delete(key, timeout), timeout.duration)

  /**
    * Synchronously delete with default timeout
    *
    * @param key
    */
  def syncDelete(key: String): Unit = syncDelete(key, defaultTimeout)

  /**
    * Asynchronously get
    *
    * @param key
    * @param timeout
    * @return
    */
  def get(key: String, timeout: Timeout): Future[Optional[A]] = underlying.get(key)(timeout) map (_.asJava)

  /**
    * Synchronously get
    *
    * @param key
    * @param timeout
    * @return
    */
  def syncGet(key: String, timeout: Timeout): Optional[A] = Await.result(get(key, timeout), timeout.duration)

  /**
    * Synchronously get with default timeout
    *
    * @param key
    * @return
    */
  def syncGet(key: String): Optional[A] = syncGet(key, defaultTimeout)

  /**
    * Asynchronously get keys
    *
    * @param timeout
    * @return
    */
  def getKeys(timeout: Timeout): Future[util.List[String]] = underlying.getKeys()(timeout) map (_.asJava)

  /**
    * Synchronously get keys
    *
    * @param timeout
    * @return
    */
  def syncGetKeys(timeout: Timeout): util.List[String] = Await.result(getKeys(timeout), timeout.duration)

  /**
    * Synchronously get keys with default timeout
    *
    * @return
    */
  def syncGetKeys(): util.List[String] = syncGetKeys(defaultTimeout)
}

final case class SimpleKeyValue[A](
                                    name: String,
                                    private val commandDest: ActorRef,
                                    private val root: Option[ActorRef],
                                    private val codec: Codec[A]
                                  )(implicit private[keyvalue] val system: ActorSystem) {

  import system.dispatcher

  private def DefaultTimeout = 5.seconds

  private val ext = ShardakkaExtension(system)

  lazy val queryDest =
    if (ext.isCluster)
      ValueActor.startRegion(name)
    else
      commandDest

  def upsert(key: String, value: A)(implicit timeout: Timeout): Future[Unit] =
    (commandDest ? ValueCommands.Upsert(key, codec.toBytes(value))) map (_ ⇒ ())

  def delete(key: String)(implicit timeout: Timeout): Future[Unit] =
    (commandDest ? ValueCommands.Delete(key)) map (_ ⇒ ())

  def get(key: String)(implicit timeout: Timeout): Future[Option[A]] =
    (queryDest ? ValueQueries.Get(key)).mapTo[ValueQueries.GetResponse] map (_.value.map(codec.fromBytes))

  def getKeys()(implicit timeout: Timeout): Future[Seq[String]] =
    (commandDest ? RootQueries.GetKeys()).mapTo[RootQueries.GetKeysResponse] map (_.keys)

  def exists(key: String)(implicit timeout: Timeout): Future[Boolean] =
    (commandDest ? RootQueries.Exists(key)).mapTo[RootQueries.ExistsResponse] map (_.exists)

  /**
    * Get Java interface with default operation timeout 5 seconds
    *
    * @return
    */
  def asJava(): SimpleKeyValueJava[A] = SimpleKeyValueJava(this, DefaultTimeout)

  /**
    * Get Java interface
    *
    * @param defaultOperationTimeout
    * @return
    */
  def asJava(defaultOperationTimeout: Timeout): SimpleKeyValueJava[A] = SimpleKeyValueJava(this, defaultOperationTimeout)

  private[keyvalue] def shutdown(): Unit = {
    commandDest ! PoisonPill
    root foreach (_ ! PoisonPill)
  }
}

trait SimpleKeyValueExtension {
  this: ShardakkaExtension ⇒
  ActorSerializer.register(5201, classOf[RootEvents.KeyCreated])
  ActorSerializer.register(5202, classOf[RootEvents.KeyDeleted])

  ActorSerializer.register(5301, classOf[ValueCommands.Upsert])
  ActorSerializer.register(5302, classOf[ValueCommands.Delete])
  ActorSerializer.register(5303, classOf[ValueCommands.Ack])

  ActorSerializer.register(5401, classOf[ValueQueries.Get])
  ActorSerializer.register(5402, classOf[ValueQueries.GetResponse])

  ActorSerializer.register(5501, classOf[ValueEvents.ValueUpdated])
  ActorSerializer.register(5502, classOf[ValueEvents.ValueDeleted])

  private val kvs = new ConcurrentHashMap[String, SimpleKeyValue[_]]

  private def compute[A](codec: Codec[A])(name: String): SimpleKeyValue[A] = {
    try {
      val actorName = s"SimpleKeyValueRoot-$name"

      val (dest, root) =
        if (isCluster) {
          val mgr = system.actorOf(
            ClusterSingletonManager.props(
              singletonProps = SimpleKeyValueRoot.props(name),
              terminationMessage = End,
              settings = ClusterSingletonManagerSettings(system)
            ), name = actorName
          )

          val dest = system.actorOf(
            ClusterSingletonProxy.props(
              s"/user/$actorName",
              ClusterSingletonProxySettings(system)),
            name = s"SimpleKeyValueRoot-$name-Proxy"
          )

          (dest, Some(mgr))
        } else {
          val dest = system.actorOf(SimpleKeyValueRoot.props(name), actorName)
          (dest, None)
        }

      SimpleKeyValue(name, dest, root, codec)
    } catch {
      case e: Exception =>
        system.log.error(e, s"Failed to create KeyValue $name")
        throw e
    }
  }

  def computeFn[A](codec: Codec[A]): Function[String, SimpleKeyValue[A]] = asJavaFunction(compute(codec) _)

  def simpleKeyValue[A](name: String, codec: Codec[A]): SimpleKeyValue[A] =
    Option(kvs.get(name))
      .getOrElse(kvs.computeIfAbsent(name, computeFn(codec)))
      .asInstanceOf[SimpleKeyValue[A]]

  def simpleKeyValue(name: String): SimpleKeyValue[String] =
    simpleKeyValue(name, StringCodec)

  def shutdownKeyValue(name: String) = Option(kvs.get(name)) foreach { kv =>
    kv.shutdown()
    kvs.remove(name)
  }
}

