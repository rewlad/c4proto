
package ee.cone.c4http

import ee.cone.c4proto._

class HttpGatewayApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with InternetForwarderApp
  with HttpServerApp
  with SSEServerApp
  with KafkaApp
{
  def bootstrapServers: String = Option(System.getenv("C4BOOTSTRAP_SERVERS")).get
  def httpPort: Int = Option(System.getenv("C4HTTP_PORT")).get.toInt
  def ssePort: Int = Option(System.getenv("C4SSE_PORT")).get.toInt
  lazy val worldProvider: WorldProvider with Executable =
    actorFactory.create(ActorName("http-gate"), messageMappers)
  override def toStart: List[CanStart] = serverFactory.toServer(worldProvider) :: super.toStart
}

object HttpGateway extends Main((new HttpGatewayApp).execution.run)

// I>P -- to agent, cmd>evl
// >P -- post, sse status
// Sn> -- to neo
// S0>W -- static content