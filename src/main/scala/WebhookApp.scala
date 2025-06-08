import cats.effect.*
import cats.syntax.all.*
import cats.data.NonEmptyList
import com.comcast.ip4s.{host, port}
import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.{Challenge, Credentials}
import org.http4s.implicits.*
import org.typelevel.ci.CIString

// 1. Modelos de dados para o payload JSON
case class WebhookPayload(
    event: Option[String],
    transaction_id: Option[String],
    amount: Option[String],
    currency: Option[String],
    timestamp: Option[String]
)

case class CallbackPayload(transaction_id: String)

implicit val webhookPayloadDecoder: EntityDecoder[IO, WebhookPayload] = jsonOf[IO, WebhookPayload]
implicit val callbackPayloadEncoder: EntityEncoder[IO, CallbackPayload] = jsonEncoderOf[IO, CallbackPayload]

object WebhookApp extends IOApp.Simple {

  val validToken = "meu-token-secreto"

  // Função que define as rotas do nosso webhook
  def webhookRoutes(processedTx: Ref[IO, Set[String]], client: Client[IO]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      // 2. Mapeia requisições POST para /webhook
      case req @ POST -> Root / "webhook" =>
        val tokenOpt = req.headers.get(CIString("X-Webhook-Token")).map(_.head.value)

        // 3. Orquestra a lógica usando for-comprehension em IO
        val action: IO[Response[IO]] = for {
          payload <- req.as[WebhookPayload]
          response <- validateAndNotify(payload, tokenOpt, processedTx, client)
        } yield response

        // Lida com erros (ex: JSON inválido) e executa a ação
        action.handleErrorWith {
          case _: InvalidMessageBodyFailure =>
            IO.println("❌ Payload com JSON inválido. Rejeitando com 400 Bad Request.") *>
              BadRequest("Invalid or malformed JSON")
          case e =>
            IO.println(s"💥 Erro inesperado: ${e.getMessage}") *>
              InternalServerError("An unexpected error occurred")
        }
    }
  }

  /**
    * Valida o payload e dispara os callbacks necessários.
    */
  def validateAndNotify(
      payload: WebhookPayload,
      token: Option[String],
      processedTx: Ref[IO, Set[String]],
      client: Client[IO]
  ): IO[Response[IO]] = {
    // Test 4: Token inválido ou ausente
    if (!token.contains(validToken)) {
      IO.println("❌ Token inválido ou ausente. Rejeitando com 401 Unauthorized.") *>
        Unauthorized(`WWW-Authenticate`(NonEmptyList.one(Challenge("Bearer", "webhook"))))
    } else {
      (payload.transaction_id, payload.amount, payload.timestamp) match {
        // Test 6 & 5: Campos obrigatórios ausentes
        case (None, _, _) | (_, _, None) =>
          for {
            _ <- IO.println(s"❌ Payload com campos obrigatórios ausentes. ID: ${payload.transaction_id.getOrElse("N/A")}. Rejeitando com 400 Bad Request.")
            // Notifica o cancelamento se o ID da transação estiver presente
            _ <- payload.transaction_id.traverse_(id => notifyCallback("cancelar", id, client))
            response <- BadRequest("Missing required fields")
          } yield response

        case (Some(id), _, _) =>
          processedTx.get.flatMap { knownIds =>
            // Test 2: Transação duplicada
            if (knownIds.contains(id)) {
              IO.println(s"❌ ID de transação duplicado: $id. Rejeitando com 409 Conflict.") *>
                Conflict("Duplicate transaction")
            }
            // Test 3: Valor (amount) incorreto
            else if (!isValidAmount(payload.amount)) {
              for {
                _ <- IO.println(s"❌ Valor inválido para transação ID: $id. Notificando /cancelar.")
                _ <- notifyCallback("cancelar", id, client)
                response <- BadRequest("Invalid amount")
              } yield response
            }
            // Test 1: Fluxo de sucesso
            else {
              for {
                _ <- IO.println(s"✅ Transação bem-sucedida: $id. Notificando /confirmar.")
                _ <- processedTx.update(_ + id) // Adiciona ID ao conjunto de processados
                _ <- notifyCallback("confirmar", id, client)
                response <- Ok("Webhook processed")
              } yield response
            }
          }
      }
    }
  }

  /**
    * Verifica se o valor é um número positivo.
    */
  def isValidAmount(amountOpt: Option[String]): Boolean =
    amountOpt.flatMap(_.toDoubleOption).exists(_ > 0.0)

  /**
    * Realiza uma chamada POST assíncrona para os endpoints de callback do Python.
    */
  def notifyCallback(endpoint: String, transactionId: String, client: Client[IO]): IO[Unit] = {
    val targetUri = uri"http://127.0.0.1:5001".withPath(Path.fromString(s"/$endpoint"))
    val payload = CallbackPayload(transactionId)

    // Create the request properly using Method and Uri
    val request = Request[IO](
      method = Method.POST,
      uri = targetUri
    ).withEntity(payload) // Fixed POST usage
    
    client.successful(request).flatMap { success =>
      if (success) IO.println(s"📞 Callback para /$endpoint (ID: $transactionId) enviado com sucesso.")
      else IO.println(s"🔥 Falha ao enviar callback para /$endpoint (ID: $transactionId).")
    }
  }

  // 4. Ponto de entrada da aplicação
  def run: IO[Unit] = {
    for {
      // Cria o estado atômico para armazenar os IDs das transações
      processedTxRef <- Ref.of[IO, Set[String]](Set.empty)
      // Constrói o cliente e o servidor como Recursos gerenciados pelo Cats Effect
      _ <- EmberClientBuilder.default[IO].build.use { client =>
        EmberServerBuilder.default[IO]
          .withHost(host"localhost")
          .withPort(port"5000")
          .withHttpApp(webhookRoutes(processedTxRef, client).orNotFound)
          .build
          .use(_ => // Fixed: added .use here
            IO.println("🚀 Servidor online em http://localhost:5000/\nPressione CTRL+C para parar...") *>
            IO.never // Mantém o servidor rodando indefinidamente
          )
      }
    } yield ()
  }
}