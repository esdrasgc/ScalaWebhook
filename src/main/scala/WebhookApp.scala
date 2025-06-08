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

final case class WebhookPayload(
    event: Option[String],
    transaction_id: Option[String],
    amount: Option[String],
    currency: Option[String],
    timestamp: Option[String]
)

final case class CallbackPayload(transaction_id: String)

object WebhookPayload {
  implicit val decoder: EntityDecoder[IO, WebhookPayload] = jsonOf[IO, WebhookPayload]
}

object CallbackPayload {
  implicit val encoder: EntityEncoder[IO, CallbackPayload] = jsonEncoderOf[IO, CallbackPayload]
}

opaque type TransactionId = String
object TransactionId {
  def apply(value: String): TransactionId = value
  extension (id: TransactionId) def value: String = id
}

opaque type WebhookToken = String
object WebhookToken {
  def apply(value: String): WebhookToken = value
  extension (token: WebhookToken) def value: String = token
}

final case class WebhookConfig(
    validToken: WebhookToken,
    serverHost: String = "localhost",
    serverPort: Int = 5000,
    callbackHost: String = "127.0.0.1",
    callbackPort: Int = 5001
)

object WebhookApp extends IOApp.Simple {
  
  private val config = WebhookConfig(WebhookToken("meu-token-secreto"))

  def webhookRoutes(processedTx: Ref[IO, Set[TransactionId]], client: Client[IO]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ POST -> Root / "webhook" =>
        val tokenOpt = req.headers.get(CIString("X-Webhook-Token")).map(h => WebhookToken(h.head.value))

        val action: IO[Response[IO]] = for {
          payload <- req.as[WebhookPayload]
          response <- validateAndNotify(payload, tokenOpt, processedTx, client)
        } yield response

        action.handleErrorWith(handleWebhookErrors)
    }
  }

  private def handleWebhookErrors: Throwable => IO[Response[IO]] = {
    case _: InvalidMessageBodyFailure =>
      IO.println("❌ Payload com JSON inválido. Rejeitando com 400 Bad Request.") *>
        BadRequest("Invalid or malformed JSON")
    case e =>
      IO.println(s"💥 Erro inesperado: ${e.getMessage}") *>
        InternalServerError("An unexpected error occurred")
  }

  /**
    * Valida o payload e dispara os callbacks necessários.
    */
  def validateAndNotify(
      payload: WebhookPayload,
      token: Option[WebhookToken],
      processedTx: Ref[IO, Set[TransactionId]],
      client: Client[IO]
  ): IO[Response[IO]] = {
    if (!isValidToken(token)) {
      IO.println("❌ Token inválido ou ausente. Rejeitando com 401 Unauthorized.") *>
        Unauthorized(`WWW-Authenticate`(NonEmptyList.one(Challenge("Bearer", "webhook"))))
    } else {
      validatePayloadAndProcess(payload, processedTx, client)
    }
  }

  private def isValidToken(token: Option[WebhookToken]): Boolean =
    token.contains(config.validToken)

  private def validatePayloadAndProcess(
      payload: WebhookPayload,
      processedTx: Ref[IO, Set[TransactionId]],
      client: Client[IO]
  ): IO[Response[IO]] = {
    (payload.transaction_id, payload.amount, payload.timestamp) match {
      case (None, _, _) | (_, _, None) =>
        handleMissingFields(payload, client)
      case (Some(idStr), _, _) =>
        val id = TransactionId(idStr)
        processTransaction(id, payload, processedTx, client)
    }
  }

  private def handleMissingFields(payload: WebhookPayload, client: Client[IO]): IO[Response[IO]] =
    for {
      _ <- IO.println(s"❌ Payload com campos obrigatórios ausentes. ID: ${payload.transaction_id.getOrElse("N/A")}.")
      _ <- payload.transaction_id.traverse_(id => notifyCallback("cancelar", TransactionId(id), client))
      response <- BadRequest("Missing required fields")
    } yield response

  private def processTransaction(
      id: TransactionId,
      payload: WebhookPayload,
      processedTx: Ref[IO, Set[TransactionId]],
      client: Client[IO]
  ): IO[Response[IO]] = {
    processedTx.get.flatMap { knownIds =>
      if (knownIds.contains(id)) {
        IO.println(s"❌ ID de transação duplicada: ${id.value}. Rejeitando com 409 Conflict.") *>
          Conflict("Duplicate transaction")
      }
      else if (!isValidAmount(payload.amount)) {
        handleInvalidAmount(id, client)
      }
      else {
        handleSuccessfulTransaction(id, processedTx, client)
      }
    }
  }

  private def handleInvalidAmount(id: TransactionId, client: Client[IO]): IO[Response[IO]] =
    for {
      _ <- IO.println(s"❌ Valor inválido para transação ID: ${id.value}. Notificando /cancelar.")
      _ <- notifyCallback("cancelar", id, client)
      response <- BadRequest("Invalid amount")
    } yield response

  private def handleSuccessfulTransaction(
      id: TransactionId,
      processedTx: Ref[IO, Set[TransactionId]],
      client: Client[IO]
  ): IO[Response[IO]] =
    for {
      _ <- IO.println(s"✅ Transação bem-sucedida: ${id.value}. Notificando /confirmar.")
      _ <- processedTx.update(_ + id)
      _ <- notifyCallback("confirmar", id, client)
      response <- Ok("Webhook processed")
    } yield response

  private def isValidAmount(amountOpt: Option[String]): Boolean =
    amountOpt
      .flatMap(_.toDoubleOption)
      .exists(amount => amount > 0.0 && amount.isFinite)

  /**
    * Realiza uma chamada POST assíncrona para os endpoints de callback do Python.
    */
  def notifyCallback(endpoint: String, transactionId: TransactionId, client: Client[IO]): IO[Unit] = {
    val targetUri = uri"http://127.0.0.1:5001".withPath(Path.fromString(s"/$endpoint"))
    val payload = CallbackPayload(transactionId.value)

    val request = Request[IO](
      method = Method.POST,
      uri = targetUri
    ).withEntity(payload) 
    
    client.successful(request).flatMap { success =>
      if (success) {
        IO.println(s"📞 Callback para /$endpoint (ID: ${transactionId.value}) enviado com sucesso.")
      } else {
        IO.println(s"🔥 Falha ao enviar callback para /$endpoint (ID: ${transactionId.value}).")
      }
    }.handleErrorWith { error =>
      IO.println(s"💥 Erro ao enviar callback: ${error.getMessage}")
    }
  }

  def run: IO[Unit] = {
    val resources = for {
      processedTxRef <- Resource.eval(Ref.of[IO, Set[TransactionId]](Set.empty))
      client <- EmberClientBuilder.default[IO].build
      server <- EmberServerBuilder.default[IO]
        .withHost(host"localhost")
        .withPort(port"5000")
        .withHttpApp(webhookRoutes(processedTxRef, client).orNotFound)
        .build
    } yield server

    resources.use(_ =>
      IO.println("🚀 Servidor online em http://localhost:5000/\nPressione CTRL+C para parar...") *>
        IO.never
    )
  }
}