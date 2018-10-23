package renderers

import conf.Configuration
import controllers.ArticlePage
import model.Cached.RevalidatableResult
import model.dotcomponents.DotcomponentsDataModel
import model.{ApplicationContext, Cached}
import play.api.libs.ws.WSClient
import play.api.mvc.{RequestHeader, Result}
import play.twirl.api.Html

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class RemoteRender(implicit context: ApplicationContext) {

  private[this] def get(
    ws:WSClient,
    payload: String,
    article: ArticlePage,
    endpoint: String
  )(implicit request: RequestHeader): Future[Result] = {

    ws.url(endpoint)
      .withRequestTimeout(2000.millis)
      .addHttpHeaders("Content-Type" -> "application/json")
      .post(payload)
      .map(response => {
        response.status match {
          case 200 =>
            Cached(article)(RevalidatableResult.Ok(Html(response.body)))
          case _ =>
            throw new Exception(response.body)
        }
      })
  }

  def getAMPArticle(ws: WSClient, payload: String, article: ArticlePage)(implicit request: RequestHeader): Future[Result] = {
    val dataModel: DotcomponentsDataModel = DotcomponentsDataModel.fromArticle(article, request)
    val dataString: String = DotcomponentsDataModel.toJsonString(dataModel)
    get(ws, dataString, article, Configuration.rendering.AMPArticleEndpoint)
  }

  def getArticle(ws:WSClient, path: String, article: ArticlePage)(implicit request: RequestHeader): Future[Result] = {
    val dataModel: DotcomponentsDataModel = DotcomponentsDataModel.fromArticle(article, request)
    val dataString: String = DotcomponentsDataModel.toJsonString(dataModel)
    get(ws, dataString, article, Configuration.rendering.renderingEndpoint)
  }

}
