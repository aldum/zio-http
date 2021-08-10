package zhttp.experiment

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http._
import zhttp.experiment.HttpMessage.HResponse
import zhttp.experiment.internal.HttpMessageAssertion
import zhttp.http._
import zhttp.service.EventLoopGroup
import zio._
import zio.duration._
import zio.test.Assertion.equalTo
import zio.test.TestAspect._
import zio.test._

/**
 * Be prepared for some real nasty runtime tests.
 */
object HEndpointSpec extends DefaultRunnableSpec with HttpMessageAssertion {
  private val env = EventLoopGroup.auto(1)

  def spec =
    suite("HEndpoint")(
      EmptySpec,
      SucceedEmptySpec,
      SucceedOkSpec,
      SucceedFailSpec,
      FailCauseSpec,
      suite("request")(
        CompleteRequestSpec,
        BufferedRequestSpec,
        AnyRequestSpec,
      ),
    ).provideCustomLayer(env) @@ timeout(10 second)

  /**
   * Spec for asserting AnyRequest fields and behaviour
   */
  private def AnyRequestSpec = {
    suite("succeed(AnyRequest)")(
      testM("status is 200") {
        assertResponse(HEndpoint.from(Http.collect[AnyRequest](_ => HResponse())))(
          isResponse(status(200)),
        )
      },
      testM("status is 500") {
        assertResponse(
          HEndpoint.from(Http.collectM[AnyRequest](_ => ZIO.fail(new Error("SERVER ERROR")))),
        )(
          isResponse(status(500)),
        )
      },
      testM("status is 404") {
        assertResponse(HEndpoint.from(Http.empty.contramap[AnyRequest](i => i)))(
          isResponse(status(404)),
        )
      },
      testM("status is 200") {
        assertResponse(HEndpoint.from(Http.collectM[AnyRequest](_ => UIO(HResponse()))))(
          isResponse(status(200)),
        )
      },
      testM("req.url is '/abc'") {
        assertBufferedRequest("/abc", HttpMethod.GET)(isRequest(url("/abc")))
      },
      testM("req.method is 'GET'") {
        assertBufferedRequest(method = HttpMethod.GET)(isRequest(method(Method.GET)))
      },
      testM("req.method is 'POST'") {
        assertBufferedRequest(method = HttpMethod.POST)(isRequest(method(Method.POST)))
      },
      testM("req.header is 'H1: K1'") {
        assertBufferedRequest(header = header.set("H1", "K1"))(
          isRequest(header(Header("H1", "K1"))),
        )
      },
    )
  }

  /**
   * Spec for asserting BufferedRequest fields and behaviour
   */

  private def BufferedRequestSpec = {
    suite("succeed(Buffered)")(
      testM("status is 200") {
        assertResponse(HEndpoint.from(Http.collect[BufferedRequest[ByteBuf]](_ => HResponse())))(
          isResponse(status(200)),
        )
      },
      testM("status is 500") {
        assertResponse(
          HEndpoint.from(Http.collectM[BufferedRequest[ByteBuf]](_ => ZIO.fail(new Error("SERVER ERROR")))),
        )(
          isResponse(status(500)),
        )
      },
      testM("status is 404") {
        assertResponse(HEndpoint.from(Http.empty.contramap[BufferedRequest[ByteBuf]](i => i)))(
          isResponse(status(404)),
        )
      },
      testM("status is 200") {
        assertResponse(HEndpoint.from(Http.collectM[BufferedRequest[ByteBuf]](_ => UIO(HResponse()))))(
          isResponse(status(200)),
        )
      },
      testM("req.content is 'ABCDE'") {
        assertBufferedByteRequestContent(content = "ABCDE".split(""))(equalTo(List("A", "B", "C", "D", "E")))
      } @@ nonFlaky,
      testM("req.content is 'ABCDE'") {
        assertBufferedChunkRequestContent(content = "ABCDE".split(""))(equalTo(List("A", "B", "C", "D", "E")))
      } @@ nonFlaky,
      testM("req.url is '/abc'") {
        assertBufferedRequest("/abc", HttpMethod.GET)(isRequest(url("/abc")))
      },
      testM("req.method is 'GET'") {
        assertBufferedRequest(method = HttpMethod.GET)(isRequest(method(Method.GET)))
      },
      testM("req.method is 'POST'") {
        assertBufferedRequest(method = HttpMethod.POST)(isRequest(method(Method.POST)))
      },
      testM("req.header is 'H1: K1'") {
        assertBufferedRequest(header = header.set("H1", "K1"))(
          isRequest(header(Header("H1", "K1"))),
        )
      },
    )
  }

  /**
   * Spec for asserting CompleteRequest fields and behaviour
   */

  private def CompleteRequestSpec = {
    suite("succeed(CompleteRequest)")(
      testM("status is 200") {
        assertResponse(HEndpoint.from(Http.collect[CompleteRequest[ByteBuf]](_ => HResponse())))(
          isResponse(status(200)),
        )
      },
      testM("status is 500") {
        assertResponse(
          HEndpoint.from(Http.collectM[CompleteRequest[ByteBuf]](_ => ZIO.fail(new Error("SERVER ERROR")))),
        )(
          isResponse(status(500)),
        )
      },
      testM("status is 404") {
        assertResponse(HEndpoint.from(Http.empty.contramap[CompleteRequest[ByteBuf]](i => i)))(
          isResponse(status(404)),
        )
      },
      testM("req.content is 'ABCD'") {
        assertCompleteByteRequest(content = List("A", "B", "C", "D"))(
          isCompleteByteRequest(byteBufContent("ABCD")),
        )
      },
      testM("req.content is 'ABCD'") {
        assertCompleteChunkRequest(content = List("A", "B", "C", "D"))(
          isCompleteChunkRequest(chunkedContent("ABCD")),
        )
      },
      testM("req.url is '/abc'") {
        assertCompleteByteRequest("/abc", HttpMethod.GET)(isRequest(url("/abc")))
      },
      testM("req.method is 'GET'") {
        assertCompleteByteRequest(method = HttpMethod.GET)(isRequest(method(Method.GET)))
      },
      testM("req.method is 'POST'") {
        assertCompleteByteRequest(method = HttpMethod.POST)(isRequest(method(Method.POST)))
      },
      testM("req.header is 'H1: K1'") {
        assertCompleteByteRequest(header = header.set("H1", "K1"))(
          isRequest(header(Header("H1", "K1"))),
        )
      },
    )
  }

  /**
   * Spec for asserting behaviour of an failing endpoint
   */
  private def FailCauseSpec = {
    suite("fail(cause)")(
      testM("status is 500") {
        assertResponse(HEndpoint.fail(new Error("SERVER_ERROR")))(isResponse(status(500)))
      },
      testM("content is SERVER_ERROR") {
        assertResponse(HEndpoint.fail(new Error("SERVER_ERROR")))(isResponse(isContent(bodyText("SERVER_ERROR"))))
      },
      testM("headers are set") {
        assertResponse(HEndpoint.fail(new Error("SERVER_ERROR")))(isResponse(header("content-length")))
      },
    )
  }

  /**
   * Spec for an Endpoint that succeed with a failing Http
   */
  private def SucceedFailSpec = {
    suite("succeed(fail)")(
      testM("status is 500") {
        assertResponse(HEndpoint.from(Http.fail(new Error("SERVER_ERROR"))))(isResponse(status(500)))
      },
      testM("content is SERVER_ERROR") {
        assertResponse(HEndpoint.from(Http.fail(new Error("SERVER_ERROR"))))(
          isResponse(isContent(bodyText("SERVER_ERROR"))),
        )
      },
      testM("headers are set") {
        assertResponse(HEndpoint.from(Http.fail(new Error("SERVER_ERROR"))))(isResponse(header("content-length")))
      },
    )
  }

  /**
   * Spec for an Endpoint that succeeds with a succeeding Http
   */
  private def SucceedOkSpec = {
    suite("succeed(ok)")(
      testM("status is 200") {
        assertResponse(HEndpoint.from(Http.succeed(HResponse())))(isResponse(status(200)))
      },
      suite("POST")(
        testM("status is 200") {
          assertResponse(
            HEndpoint.from(Http.succeed(HResponse())),
            method = HttpMethod.POST,
            content = List("A", "B", "C"),
          )(
            isResponse(status(200)),
          )
        },
      ),
      testM("headers are empty") {
        assertResponse(HEndpoint.from(Http.succeed(HResponse())))(isResponse(noHeader))
      },
      testM("headers are set") {
        assertResponse(HEndpoint.from(Http.succeed(HResponse(headers = List(Header("key", "value"))))))(
          isResponse(header("key", "value")),
        )
      },
      testM("version is 1.1") {
        assertResponse(HEndpoint.from(Http.succeed(HResponse())))(isResponse(version("HTTP/1.1")))
      },
      testM("version is 1.1") {
        assertResponse(HEndpoint.from(Http.succeed(HResponse())))(isResponse(version("HTTP/1.1")))
      },
    )
  }

  /**
   * Spec for an Endpoint that succeeds with an empty Http
   */
  private def SucceedEmptySpec = {
    suite("succeed(empty)")(
      testM("status is 404") {
        assertResponse(HEndpoint.empty)(isResponse(status(404)))
      },
      testM("headers are empty") {
        assertResponse(HEndpoint.empty)(isResponse(noHeader))
      },
      testM("version is 1.1") {
        assertResponse(HEndpoint.empty)(isResponse(version("HTTP/1.1")))
      },
      testM("version is 1.1") {
        assertResponse(HEndpoint.empty)(isResponse(version("HTTP/1.1")))
      },
    )
  }

  /**
   * Spec for an Endpoint that is empty
   */
  private def EmptySpec = {
    suite("empty")(
      suite("GET")(
        testM("status is 404") {
          assertResponse(HEndpoint.empty)(isResponse(status(404)))
        },
        testM("headers are empty") {
          assertResponse(HEndpoint.empty)(isResponse(noHeader))
        },
        testM("version is 1.1") {
          assertResponse(HEndpoint.empty)(isResponse(version("HTTP/1.1")))
        },
        testM("version is 1.1") {
          assertResponse(HEndpoint.empty)(isResponse(version("HTTP/1.1")))
        },
      ),
      suite("POST")(
        testM("status is 404") {
          assertResponse(HEndpoint.empty, method = HttpMethod.POST, content = List("A", "B", "C"))(
            isResponse(status(404)),
          )
        },
      ),
    )
  }
}
