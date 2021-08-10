package zhttp.experiment.internal

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http._
import zhttp.experiment.HttpMessage.{HRequest, HResponse}
import zhttp.experiment.{BufferedRequest, CompleteRequest, HEndpoint}
import zhttp.http.{Header, Http, HTTP_CHARSET, Method}
import zhttp.service.EventLoopGroup
import zio.test.Assertion.anything
import zio.test.AssertionM.Render.param
import zio.test.{assert, assertM, Assertion, TestResult}
import zio.{Chunk, Promise, UIO, ZIO}

import java.nio.charset.Charset

trait HttpMessageAssertion {
  implicit final class HttpMessageSyntax(m: HttpObject) {
    def asString: String = m.toString.dropWhile(_ != '\n')
  }

  implicit final class HEndpointSyntax[R, E](app: HEndpoint[R, Throwable]) {
    def ===(assertion: Assertion[HttpObject]): ZIO[R with EventLoopGroup, Nothing, TestResult] =
      assertM(execute(app)(_.request("/")))(assertion)

    def proxy: ZIO[R with EventLoopGroup, Nothing, ChannelProxy] = ChannelProxy.make(app)
  }

  def isResponse[A](assertion: Assertion[HttpResponse]): Assertion[A] =
    Assertion.assertionRec("isResponse")(param(assertion))(assertion)({
      case msg: HttpResponse => Option(msg)
      case _                 => None
    })

  def isRequest[A](assertion: Assertion[HRequest]): Assertion[A] =
    Assertion.assertionRec("isRequest")(param(assertion))(assertion)({
      case msg: HRequest => Option(msg)
      case _             => None
    })

  def isCompleteByteRequest[A](assertion: Assertion[CompleteRequest[ByteBuf]]): Assertion[A] =
    Assertion.assertionRec("isCompleteByteRequest")(param(assertion))(assertion)({
      case msg: CompleteRequest[_] if msg.content.isInstanceOf[ByteBuf] =>
        Option(msg.asInstanceOf[CompleteRequest[ByteBuf]])
      case _                                                            => None
    })

  def isCompleteChunkRequest[A](assertion: Assertion[CompleteRequest[Chunk[Byte]]]): Assertion[A] =
    Assertion.assertionRec("isCompleteChunkRequest")(param(assertion))(assertion)({
      case msg: CompleteRequest[_] if msg.content.isInstanceOf[Chunk[_]] =>
        Option(msg.asInstanceOf[CompleteRequest[Chunk[Byte]]])
      case _                                                             => None
    })

  def isBufferedRequest[A](assertion: Assertion[BufferedRequest[ByteBuf]]): Assertion[A] =
    Assertion.assertionRec("isBufferedRequest")(param(assertion))(assertion)({
      case msg: BufferedRequest[_] => Option(msg.asInstanceOf[BufferedRequest[ByteBuf]])
      case _                       => None
    })

  def isContent[A](assertion: Assertion[HttpContent]): Assertion[A] =
    Assertion.assertionRec("isContent")(param(assertion))(assertion)({
      case msg: HttpContent => Option(msg)
      case _                => None
    })

  def isLastContent[A](assertion: Assertion[LastHttpContent]): Assertion[A] =
    Assertion.assertionRec("isLastContent")(param(assertion))(assertion)({
      case msg: LastHttpContent => Option(msg)
      case _                    => None
    })

  def status(code: Int): Assertion[HttpResponse] =
    Assertion.assertion("status")(param(code))(_.status().code() == code)

  def byteBufContent[A](text: String): Assertion[CompleteRequest[ByteBuf]] =
    Assertion.assertion("byteBufContent")(param(text))(_.content.toString(HTTP_CHARSET) == text)

  def chunkedContent[A](text: String): Assertion[CompleteRequest[Chunk[Byte]]] =
    Assertion.assertion("chunkedContent")(param(text))(_.content.map(_.toChar).mkString("").trim == text)

  def url(url: String): Assertion[HRequest] =
    Assertion.assertion("status")(param(url))(_.url.asString == url)

  def method(method: Method): Assertion[HRequest] =
    Assertion.assertion("method")(param(method))(_.method == method)

  def header(header: Header): Assertion[HRequest] =
    Assertion.assertion("header")(param(header))(_.headers.contains(header))

  def bodyText(data: String, charset: Charset = Charset.defaultCharset()): Assertion[HttpContent] =
    Assertion.assertion("body")(param(data))(_.content().toString(charset).contains(data))

  def header(name: String, value: String, ignoreCase: Boolean = true): Assertion[HttpResponse] =
    Assertion.assertion("header")(param(s"$name: $value"))(_.headers().contains(name, value, ignoreCase))

  def header(name: String): Assertion[HttpResponse] =
    Assertion.assertion("header")(param(s"$name: ???"))(_.headers().contains(name))

  def noHeader: Assertion[HttpResponse] = Assertion.assertion("no header")()(_.headers().size() == 0)

  def version(name: String): Assertion[HttpResponse] =
    Assertion.assertion("version")(param(name))(_.protocolVersion().toString == name)

  def isAnyResponse: Assertion[Any] = isResponse(anything)

  def execute[R](
    app: HEndpoint[R, Throwable],
  )(f: ChannelProxy => UIO[Any]): ZIO[R with EventLoopGroup, Nothing, HttpObject] =
    for {
      proxy <- ChannelProxy.make(app)
      _     <- f(proxy)
      res   <- proxy.receive
    } yield res

  /**
   * Helper to create empty headers
   */
  def header = { new DefaultHttpHeaders() }

  /**
   * Creates an HEndpoint internally that requires a BufferedRequest. Allows asserting on any field of the request using
   * the `assertion` parameter.
   */
  def assertBufferedRequest[R, E](
    f: ChannelProxy => ZIO[R, E, Any],
  )(
    assertion: Assertion[BufferedRequest[ByteBuf]],
  ): ZIO[EventLoopGroup with R, E, TestResult] = for {
    promise <- Promise.make[Nothing, BufferedRequest[ByteBuf]]
    proxy   <- ChannelProxy.make(
      HEndpoint.from(Http.collectM[BufferedRequest[ByteBuf]](req => promise.succeed(req) as HResponse())),
    )
    _       <- f(proxy)
    req     <- promise.await
  } yield assert(req)(assertion)

  /**
   * Creates an HEndpoint internally that requires a BufferedRequest using a `ByteBuf` as an encoding for it's data.
   * Allows asserting on the content of the request using the `assertion` parameter.
   */

  def assertBufferedByteRequestContent[R, E](
    url: String = "/",
    method: HttpMethod = HttpMethod.POST,
    header: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    content: Iterable[String] = Nil,
  )(
    assertion: Assertion[Iterable[String]],
  ): ZIO[EventLoopGroup with R, E, TestResult] = for {
    promise <- Promise.make[Nothing, Chunk[ByteBuf]]
    proxy   <- ChannelProxy.make(
      HEndpoint.from(
        Http.collectM[BufferedRequest[ByteBuf]](req => req.content.runCollect.tap(promise.succeed) as HResponse()),
      ),
    )

    _ <- proxy.request(url, method, header)
    _ <- proxy.data(content)
    _ <- proxy.end

    req <- promise.await
  } yield assert(req.toList.map(bytes => bytes.toString(HTTP_CHARSET)))(assertion)

  /**
   * Creates an HEndpoint internally that requires a BufferedRequest using a `Chunk[Byte]` as an encoding for it's data.
   * Allows asserting on the content of the request using the `assertion` parameter.
   */
  def assertBufferedChunkRequestContent[R, E](
    url: String = "/",
    method: HttpMethod = HttpMethod.POST,
    header: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    content: Iterable[String] = Nil,
  )(
    assertion: Assertion[Iterable[String]],
  ): ZIO[EventLoopGroup with R, E, TestResult] = for {
    promise <- Promise.make[Nothing, Chunk[Chunk[Byte]]]
    proxy   <- ChannelProxy.make(
      HEndpoint.chunked(
        Http.collectM[BufferedRequest[Chunk[Byte]]](req => req.content.runCollect.tap(promise.succeed) as HResponse()),
      ),
    )

    _ <- proxy.request(url, method, header)
    _ <- proxy.data(content)
    _ <- proxy.end

    req <- promise.await
  } yield assert(req.map(_.map(_.toChar).mkString("")))(assertion)

  /**
   * Creates an HEndpoint internally that requires a BufferedRequest. Allows asserting on any field of the request using
   * the `assertion` parameter.
   */
  def assertBufferedRequest(
    url: String = "/",
    method: HttpMethod = HttpMethod.GET,
    header: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    content: Iterable[String] = Nil,
  )(
    assertion: Assertion[BufferedRequest[ByteBuf]],
  ): ZIO[EventLoopGroup, Nothing, TestResult] =
    assertBufferedRequest(proxy =>
      proxy.request(url, method, header) *>
        ZIO.foreach(content)(proxy.data(_)) *>
        proxy.end,
    )(assertion)

  /**
   * Creates an HEndpoint internally that requires a CompleteRequest. The request to be sent can be configured via the
   * `f` function. Allows any kind of custom assertion on the CompleteRequest.
   */
  def assertCompleteByteRequest[R, E](f: ChannelProxy => ZIO[R, E, Any])(
    assertion: Assertion[CompleteRequest[ByteBuf]],
  ): ZIO[EventLoopGroup with R, E, TestResult] = for {
    promise <- Promise.make[Nothing, CompleteRequest[ByteBuf]]
    proxy   <- ChannelProxy.make(
      HEndpoint.from(Http.collectM[CompleteRequest[ByteBuf]](req => promise.succeed(req) as HResponse())),
    )
    _       <- f(proxy)
    req     <- promise.await
  } yield assert(req)(assertion)

  /**
   * Creates an HEndpoint internally that requires a CompleteRequest. The request to be sent can be configured via the
   * `f` function. Allows any kind of custom assertion on the CompleteRequest.
   */
  def assertCompleteChunkRequest[R, E](f: ChannelProxy => ZIO[R, E, Any])(
    assertion: Assertion[CompleteRequest[Chunk[Byte]]],
  ): ZIO[EventLoopGroup with R, E, TestResult] = for {
    promise <- Promise.make[Nothing, CompleteRequest[Chunk[Byte]]]
    proxy   <- ChannelProxy.make(
      HEndpoint.chunked(Http.collectM[CompleteRequest[Chunk[Byte]]](req => promise.succeed(req) as HResponse())),
    )
    _       <- f(proxy)
    req     <- promise.await
  } yield assert(req)(assertion)

  /**
   * Creates an HEndpoint internally that requires a CompleteRequest. Allows asserting on any field of the request using
   * the `assertion` parameter.
   */
  def assertCompleteByteRequest(
    url: String = "/",
    method: HttpMethod = HttpMethod.GET,
    header: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    content: Iterable[String] = Nil,
  )(
    assertion: Assertion[CompleteRequest[ByteBuf]],
  ): ZIO[EventLoopGroup, Nothing, TestResult] =
    assertCompleteByteRequest(proxy =>
      proxy.request(url, method, header) *>
        ZIO.foreach(content)(proxy.data(_)) *>
        proxy.end,
    )(assertion)

  /**
   * Creates an HEndpoint internally that requires a CompleteRequest. Allows asserting on any field of the request using
   * the `assertion` parameter.
   */
  def assertCompleteChunkRequest(
    url: String = "/",
    method: HttpMethod = HttpMethod.GET,
    header: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    content: Iterable[String] = Nil,
  )(
    assertion: Assertion[CompleteRequest[Chunk[Byte]]],
  ): ZIO[EventLoopGroup, Nothing, TestResult] =
    assertCompleteChunkRequest(proxy =>
      proxy.request(url, method, header) *>
        ZIO.foreach(content)(proxy.data(_)) *>
        proxy.end,
    )(assertion)

  /**
   * Dispatches a request with some content and asserts on the response received on an HEndpoint
   */
  def assertResponse[R](
    app: HEndpoint[R, Throwable],
    url: String = "/",
    method: HttpMethod = HttpMethod.GET,
    header: HttpHeaders = EmptyHttpHeaders.INSTANCE,
    content: Iterable[String] = Nil,
  )(
    assertion: Assertion[HttpObject],
  ): ZIO[R with EventLoopGroup, Nothing, TestResult] =
    assertM(
      execute(app)(proxy =>
        proxy.request(url, method, header) *>
          ZIO.foreach(content)(proxy.data(_)) *>
          proxy.end,
      ),
    )(assertion)
}
